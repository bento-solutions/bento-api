package com.bento.crm.bank.service;

import com.bento.crm.bank.dto.BankDtos;
import com.bento.crm.bank.model.BankTransaction;
import com.bento.crm.bank.repository.BankTransactionRepository;
import com.bento.crm.bank.statement.StatementText;
import com.bento.crm.common.exception.ResourceNotFoundException;
import com.bento.crm.expense.model.Expense;
import com.bento.crm.expense.repository.ExpenseRepository;
import com.bento.crm.invoice.model.Invoice;
import com.bento.crm.invoice.repository.InvoiceRepository;
import com.bento.crm.payment.dto.CreatePaymentRequest;
import com.bento.crm.payment.model.Payment;
import com.bento.crm.payment.repository.PaymentRepository;
import com.bento.crm.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Ties bank lines to what they settle. Matching a received transfer to a customer invoice records
 * the payment (so the invoice shows paid); unmatching removes that payment again. Lines are only
 * matched on their own when there is exactly one open invoice for the exact amount and its number
 * is written in the bank label.
 */
@Service
@RequiredArgsConstructor
public class ReconciliationService {

    private static final BigDecimal TOLERANCE = new BigDecimal("0.01");
    private static final int DAYS = 20;

    private final BankTransactionRepository transactionRepository;
    private final InvoiceRepository invoiceRepository;
    private final PaymentRepository paymentRepository;
    private final ExpenseRepository expenseRepository;
    private final PaymentService paymentService;
    private final BankCategorizer categorizer;
    private final JdbcTemplate jdbc;

    public BankTransaction transaction(UUID orgId, UUID id) {
        return transactionRepository.find(orgId, id).orElseThrow(() -> new ResourceNotFoundException("Bank line not found"));
    }

    /** What this line could be paying or receiving, best first. */
    public List<BankDtos.Candidate> candidates(UUID orgId, BankTransaction t) {
        BigDecimal abs = t.getAmount().abs();
        String label = alnum(t.getDescription() + " " + (t.getReference() == null ? "" : t.getReference()));
        String text = StatementText.norm(t.getDescription());
        List<BankDtos.Candidate> out = new ArrayList<>();
        String type = t.getAmount().signum() > 0 ? "CUSTOMER" : "VENDOR";

        for (Map<String, Object> r : openInvoices(orgId, type)) {
            BigDecimal outstanding = (BigDecimal) r.get("outstanding");
            String number = (String) r.get("invoice_number");
            String partner = (String) r.get("partner_name");
            boolean exact = outstanding.subtract(abs).abs().compareTo(TOLERANCE) <= 0;
            boolean numbered = numberIn(number, label);
            boolean named = partner != null && partner.length() >= 3 && text.contains(StatementText.norm(partner));
            if (!exact && !((numbered || named) && abs.compareTo(outstanding.add(TOLERANCE)) <= 0)) continue;
            int score = (exact ? 3 : 0) + (numbered ? 5 : 0) + (named ? 2 : 0);
            List<String> why = new ArrayList<>();
            if (exact) why.add("same amount");
            else why.add("part of what is owed");
            if (numbered) why.add("invoice number in the label");
            if (named) why.add("name in the label");
            out.add(new BankDtos.Candidate("INVOICE", (UUID) r.get("id"), number == null ? "Invoice" : number, partner,
                    date(r.get("due_date")), (BigDecimal) r.get("total"), outstanding, score, String.join(", ", why)));
        }

        jdbc.queryForList("""
                SELECT p.id, p.amount, p.payment_date, p.reference, i.invoice_number, pa.name AS partner_name
                FROM payment p LEFT JOIN invoice i ON i.id = p.invoice_id LEFT JOIN partner pa ON pa.id = p.partner_id
                WHERE p.organization_id = ? AND p.deleted_at IS NULL AND p.amount = ?
                  AND p.payment_date BETWEEN ? AND ?
                  AND (i.id IS NULL OR i.type = ?)
                  AND NOT EXISTS (SELECT 1 FROM bank_transaction b WHERE b.payment_id = p.id AND b.deleted_at IS NULL)
                ORDER BY p.payment_date DESC LIMIT 10
                """, orgId, abs, t.getBookingDate().minusDays(DAYS), t.getBookingDate().plusDays(DAYS), type)
                .forEach(r -> out.add(new BankDtos.Candidate("PAYMENT", (UUID) r.get("id"),
                        "Payment" + (r.get("invoice_number") != null ? " of " + r.get("invoice_number") : ""),
                        (String) r.get("partner_name"), date(r.get("payment_date")), (BigDecimal) r.get("amount"), null,
                        4, "payment already recorded for this amount")));

        if (t.getAmount().signum() < 0) {
            jdbc.queryForList("""
                    SELECT e.id, e.amount, e.expense_date, e.category, e.description, e.vendor_name
                    FROM expense e
                    WHERE e.organization_id = ? AND e.deleted_at IS NULL AND e.amount = ?
                      AND e.expense_date BETWEEN ? AND ?
                      AND NOT EXISTS (SELECT 1 FROM bank_transaction b WHERE b.expense_id = e.id AND b.deleted_at IS NULL)
                    ORDER BY e.expense_date DESC LIMIT 10
                    """, orgId, abs, t.getBookingDate().minusDays(DAYS), t.getBookingDate().plusDays(DAYS))
                    .forEach(r -> out.add(new BankDtos.Candidate("EXPENSE", (UUID) r.get("id"),
                            r.get("description") != null ? (String) r.get("description") : (String) r.get("category"),
                            (String) r.get("vendor_name"), date(r.get("expense_date")), (BigDecimal) r.get("amount"), null,
                            3, "expense of the same amount")));
        }
        out.sort(Comparator.comparingInt(BankDtos.Candidate::score).reversed());
        return out.size() > 12 ? out.subList(0, 12) : out;
    }

    @Transactional
    public BankTransaction match(UUID orgId, UUID id, String kind, UUID targetId) {
        BankTransaction t = transaction(orgId, id);
        if (t.getStatus() != BankTransaction.Status.UNMATCHED) {
            throw new IllegalStateException("This line is already matched or set aside");
        }
        if (targetId == null || kind == null) {
            throw new IllegalArgumentException("Choose what this line pays");
        }
        BigDecimal abs = t.getAmount().abs();
        switch (kind.toUpperCase(Locale.ROOT)) {
            case "INVOICE" -> {
                Invoice inv = invoiceRepository.findByOrganizationIdAndId(orgId, targetId)
                        .orElseThrow(() -> new ResourceNotFoundException("Invoice not found"));
                boolean in = t.getAmount().signum() > 0;
                if (in != (inv.getType() == Invoice.InvoiceType.CUSTOMER)) {
                    throw new IllegalArgumentException(in ? "Money received pays a customer invoice"
                            : "Money paid out settles a supplier invoice");
                }
                if (inv.getStatus() == Invoice.Status.DRAFT) {
                    throw new IllegalArgumentException("This invoice is still a draft");
                }
                if (inv.getPartnerId() == null) {
                    throw new IllegalArgumentException("This invoice has no customer or supplier");
                }
                BigDecimal outstanding = outstanding(inv);
                if (abs.compareTo(outstanding.add(TOLERANCE)) > 0) {
                    throw new IllegalArgumentException("This line is more than what is still owed on the invoice ("
                            + outstanding.toPlainString() + ")");
                }
                Payment p = paymentService.createPayment(new CreatePaymentRequest(inv.getPartnerId(), inv.getId(),
                        t.getBookingDate(), abs, Payment.Method.BANK_TRANSFER,
                        t.getReference() != null ? t.getReference() : cut(t.getDescription(), 100),
                        "Bank statement: " + cut(t.getDescription(), 200)));
                t.setInvoiceId(inv.getId());
                t.setPaymentId(p.getId());
                t.setPaymentCreated(true);
                setMatchCategory(t, in ? "SALES" : "SUPPLIERS");
            }
            case "PAYMENT" -> {
                Payment p = paymentRepository.findByOrganizationIdAndId(orgId, targetId)
                        .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));
                if (p.getAmount() == null || p.getAmount().subtract(abs).abs().compareTo(TOLERANCE) > 0) {
                    throw new IllegalArgumentException("The payment is not for the same amount");
                }
                if (transactionRepository.countByPayment(orgId, p.getId()) > 0) {
                    throw new IllegalStateException("This payment is already matched to another bank line");
                }
                t.setPaymentId(p.getId());
                t.setInvoiceId(p.getInvoiceId());
                t.setPaymentCreated(false);
                setMatchCategory(t, t.getAmount().signum() > 0 ? "SALES" : "SUPPLIERS");
            }
            case "EXPENSE" -> {
                if (t.getAmount().signum() > 0) {
                    throw new IllegalArgumentException("Money received cannot pay an expense");
                }
                Expense e = expenseRepository.findForOrganization(orgId, targetId)
                        .orElseThrow(() -> new ResourceNotFoundException("Expense not found"));
                if (transactionRepository.countByExpense(orgId, e.getId()) > 0) {
                    throw new IllegalStateException("This expense is already matched to another bank line");
                }
                t.setExpenseId(e.getId());
                String c = e.getCategory() == null ? null : e.getCategory().toUpperCase(Locale.ROOT);
                setMatchCategory(t, BankCategories.ALL.contains(c) ? c : "OTHER");
            }
            default -> throw new IllegalArgumentException("Match to an INVOICE, a PAYMENT or an EXPENSE");
        }
        t.setStatus(BankTransaction.Status.MATCHED);
        return transactionRepository.save(t);
    }

    @Transactional
    public BankTransaction unmatch(UUID orgId, UUID id) {
        BankTransaction t = transaction(orgId, id);
        if (t.getStatus() != BankTransaction.Status.MATCHED) {
            throw new IllegalStateException("This line is not matched");
        }
        if (t.isPaymentCreated() && t.getPaymentId() != null) {
            // The payment exists only because of this match: remove it so the invoice is open again.
            paymentRepository.findByOrganizationIdAndId(orgId, t.getPaymentId())
                    .ifPresent(p -> paymentService.deletePayment(p.getId()));
        }
        t.setInvoiceId(null);
        t.setPaymentId(null);
        t.setExpenseId(null);
        t.setPaymentCreated(false);
        t.setStatus(BankTransaction.Status.UNMATCHED);
        if (t.getCategorySource() == BankTransaction.CategorySource.MATCH) {
            t.setCategory(null);
            t.setCategorySource(null);
            categorizer.categorize(orgId, List.of(t), false);
        }
        return transactionRepository.save(t);
    }

    @Transactional
    public BankTransaction ignore(UUID orgId, UUID id, boolean ignore) {
        BankTransaction t = transaction(orgId, id);
        if (t.getStatus() == BankTransaction.Status.MATCHED) {
            throw new IllegalStateException("Unmatch the line first");
        }
        t.setStatus(ignore ? BankTransaction.Status.IGNORED : BankTransaction.Status.UNMATCHED);
        return transactionRepository.save(t);
    }

    /** Lines that pay one open invoice whose number is in the label, for exactly what is owed. */
    @Transactional
    public int autoMatch(UUID orgId, List<BankTransaction> txs) {
        int matched = 0;
        List<Map<String, Object>> customer = null, vendor = null;
        for (BankTransaction t : txs) {
            if (t.getStatus() != BankTransaction.Status.UNMATCHED || t.getId() == null) continue;
            List<Map<String, Object>> open;
            if (t.getAmount().signum() > 0) {
                open = customer = customer == null ? openInvoices(orgId, "CUSTOMER") : customer;
            } else {
                open = vendor = vendor == null ? openInvoices(orgId, "VENDOR") : vendor;
            }
            String label = alnum(t.getDescription() + " " + (t.getReference() == null ? "" : t.getReference()));
            BigDecimal abs = t.getAmount().abs();
            List<Map<String, Object>> hits = open.stream()
                    .filter(r -> ((BigDecimal) r.get("outstanding")).subtract(abs).abs().compareTo(TOLERANCE) <= 0)
                    .filter(r -> numberIn((String) r.get("invoice_number"), label))
                    .toList();
            if (hits.size() == 1) {
                match(orgId, t.getId(), "INVOICE", (UUID) hits.get(0).get("id"));
                open.remove(hits.get(0));
                matched++;
            }
        }
        return matched;
    }

    private List<Map<String, Object>> openInvoices(UUID orgId, String type) {
        return new ArrayList<>(jdbc.queryForList("""
                SELECT * FROM (
                  SELECT i.id, i.invoice_number, i.total, i.due_date, COALESCE(pa.name, i.customer_name) AS partner_name,
                         i.total - COALESCE((SELECT SUM(p.amount) FROM payment p
                                             WHERE p.invoice_id = i.id AND p.deleted_at IS NULL), 0) AS outstanding
                  FROM invoice i LEFT JOIN partner pa ON pa.id = i.partner_id
                  WHERE i.organization_id = ? AND i.deleted_at IS NULL AND i.type = ?
                    AND i.status IN ('SENT', 'PARTIALLY_PAID', 'OVERDUE') AND i.total IS NOT NULL
                ) x WHERE outstanding > 0
                """, orgId, type));
    }

    private BigDecimal outstanding(Invoice inv) {
        BigDecimal paid = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM payment WHERE invoice_id = ? AND deleted_at IS NULL",
                BigDecimal.class, inv.getId());
        return (inv.getTotal() == null ? BigDecimal.ZERO : inv.getTotal()).subtract(paid);
    }

    private static void setMatchCategory(BankTransaction t, String category) {
        // A category a person chose stays.
        if (t.getCategorySource() != BankTransaction.CategorySource.MANUAL) {
            t.setCategory(category);
            t.setCategorySource(BankTransaction.CategorySource.MATCH);
        }
    }

    /** The invoice number, or its numeric tail ("FA-2024-001" -> "2024001"), appears in the label. */
    static boolean numberIn(String number, String alnumLabel) {
        if (number == null) return false;
        String n = alnum(number);
        if (n.length() >= 4 && alnumLabel.contains(n)) return true;
        String tail = n.replaceFirst("^[A-Z]+", "");
        return tail.length() >= 5 && !tail.equals(n) && alnumLabel.contains(tail);
    }

    private static String alnum(String s) {
        return StatementText.norm(s).toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    private static LocalDate date(Object o) {
        if (o instanceof java.sql.Date d) return d.toLocalDate();
        if (o instanceof LocalDate d) return d;
        return null;
    }

    private static String cut(String s, int max) {
        return s == null ? null : s.length() > max ? s.substring(0, max) : s;
    }
}
