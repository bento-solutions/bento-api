package com.bento.crm.expense.service;

import com.bento.crm.common.context.TenantContext;
import com.bento.crm.common.dto.PageResponse;
import com.bento.crm.common.exception.ResourceNotFoundException;
import com.bento.crm.common.pdf.PdfDocumentService;
import com.bento.crm.common.service.BusinessNumberService;
import com.bento.crm.expense.dto.ExpenseDtos.ExpenseRequest;
import com.bento.crm.expense.dto.ExpenseReportDtos.*;
import com.bento.crm.expense.model.Expense;
import com.bento.crm.expense.model.ExpenseReport;
import com.bento.crm.expense.repository.ExpenseReportRepository;
import com.bento.crm.expense.repository.ExpenseRepository;
import com.bento.crm.file.model.StoredFile;
import com.bento.crm.file.repository.StoredFileRepository;
import com.bento.crm.file.service.FileStorageService;
import com.bento.crm.invoice.service.InvoiceTemplateService;
import com.bento.crm.ledger.service.LedgerAutoPostingService;
import com.bento.crm.ledger.service.LedgerPostingService;
import com.bento.crm.organization.repository.OrganizationRepository;
import com.bento.crm.payment.model.Payment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Expense reports (notes de frais): a DRAFT report collects the expenses an employee paid; posting
 * numbers it NF-YYYY-NNNN and books it (OD: lines to their accounts, the total to 4438); unposting
 * reverses that and goes back to DRAFT keeping the number; reimbursing books 4438 against the bank
 * or the cash register. Receipts are files attached to each line (owner EXPENSE); documents for the
 * whole report hang off the report (owner EXPENSE_REPORT).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExpenseReportService {

    public static final String LINE_FILES = "EXPENSE";
    public static final String REPORT_FILES = "EXPENSE_REPORT";

    private final ExpenseReportRepository reports;
    private final ExpenseRepository expenses;
    private final ExpenseService expenseService;
    private final LedgerAutoPostingService ledger;
    private final LedgerPostingService posting;
    private final BusinessNumberService numbers;
    private final StoredFileRepository files;
    private final FileStorageService fileStorage;
    private final PdfDocumentService pdf;
    private final InvoiceTemplateService templates;
    private final OrganizationRepository organizations;
    private final JdbcTemplate jdbc;

    private static UUID org() {
        return TenantContext.getCurrentOrganizationId();
    }

    // Reading

    @Transactional(readOnly = true)
    public PageResponse<ExpenseReportResponse> list(String status, String employee, LocalDate from, LocalDate to,
                                                    int page, int size) {
        UUID orgId = org();
        int s = Math.min(Math.max(size, 1), 200);
        int p = Math.max(page, 0);
        StringBuilder where = new StringBuilder(" WHERE r.organization_id = ? AND r.deleted_at IS NULL");
        List<Object> args = new ArrayList<>(List.of(orgId));
        if (status != null && !status.isBlank()) {
            where.append(" AND r.status = ?");
            args.add(status(status).name());
        }
        if (employee != null && !employee.isBlank()) {
            UUID userId = uuidOrNull(employee.trim());
            if (userId != null) {
                where.append(" AND r.employee_user_id = ?");
                args.add(userId);
            } else {
                where.append(" AND r.employee_name ILIKE ?");
                args.add("%" + employee.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
            }
        }
        // The report's span: its period, else its lines' dates, else the day it was created.
        String start = "COALESCE(r.period_from, t.first_date, r.period_to, CAST(r.created_at AS date))";
        String end = "COALESCE(r.period_to, t.last_date, r.period_from, CAST(r.created_at AS date))";
        if (from != null) {
            where.append(" AND ").append(end).append(" >= ?");
            args.add(from);
        }
        if (to != null) {
            where.append(" AND ").append(start).append(" <= ?");
            args.add(to);
        }
        String body = """
                 FROM expense_report r
                LEFT JOIN LATERAL (SELECT COUNT(*) AS lines, COALESCE(SUM(e.amount), 0) AS ttc,
                       COALESCE(SUM(COALESCE(e.vat_amount, 0)), 0) AS vat, MIN(e.expense_date) AS first_date,
                       MAX(e.expense_date) AS last_date
                    FROM expense e WHERE e.expense_report_id = r.id AND e.deleted_at IS NULL) t ON TRUE
                """ + where;
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + body, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(s);
        pageArgs.add((long) p * s);
        List<ExpenseReportResponse> content = jdbc.query("""
                SELECT r.*, t.lines, t.ttc, t.vat, t.last_date,
                       (SELECT COUNT(*) FROM stored_file f WHERE f.organization_id = r.organization_id
                          AND f.owner_entity_type = 'EXPENSE_REPORT' AND f.owner_entity_id = r.id AND f.deleted_at IS NULL) AS files
                """ + body + " ORDER BY r.created_at DESC LIMIT ? OFFSET ?", this::row, pageArgs.toArray());
        return PageResponse.fromPage(new PageImpl<>(content, PageRequest.of(p, s), total == null ? 0 : total));
    }

    @Transactional(readOnly = true)
    public ExpenseReportResponse get(UUID id) {
        return view(find(org(), id));
    }

    // Writing a draft

    @Transactional
    public ExpenseReportResponse create(ExpenseReportRequest r) {
        if (r == null) throw new IllegalArgumentException("The expense report is required");
        ExpenseReport report = new ExpenseReport();
        report.setOrganizationId(org());
        report.setStatus(ExpenseReport.Status.DRAFT);
        apply(report, r);
        return view(reports.saveAndFlush(report));
    }

    @Transactional
    public ExpenseReportResponse update(UUID id, ExpenseReportRequest r) {
        ExpenseReport report = draft(org(), id);
        if (r == null) return view(report);
        apply(report, r);
        return view(reports.saveAndFlush(report));
    }

    /** Deletes a DRAFT report never posted, with its lines. A numbered one is cancelled instead. */
    @Transactional
    public void delete(UUID id) {
        UUID orgId = org();
        ExpenseReport report = draft(orgId, id);
        if (report.getNumber() != null) {
            throw new IllegalArgumentException("This expense report has a number: cancel it instead");
        }
        Instant now = Instant.now();
        for (Expense e : expenses.findReportLines(orgId, id)) {
            e.setDeletedAt(now);
            expenses.save(e);
        }
        report.setDeletedAt(now);
        reports.save(report);
    }

    @Transactional
    public ExpenseReportResponse addLine(UUID id, LineRequest r) {
        UUID orgId = org();
        ExpenseReport report = draft(orgId, id);
        Expense e = new Expense();
        e.setOrganizationId(orgId);
        e.setExpenseReportId(report.getId());
        applyLine(report, e, r);
        expenses.saveAndFlush(e);
        touch(report);
        return view(report);
    }

    @Transactional
    public ExpenseReportResponse updateLine(UUID id, UUID expenseId, LineRequest r) {
        UUID orgId = org();
        ExpenseReport report = draft(orgId, id);
        Expense e = line(orgId, report, expenseId);
        applyLine(report, e, r);
        expenses.saveAndFlush(e);
        touch(report);
        return view(report);
    }

    @Transactional
    public ExpenseReportResponse deleteLine(UUID id, UUID expenseId) {
        UUID orgId = org();
        ExpenseReport report = draft(orgId, id);
        Expense e = line(orgId, report, expenseId);
        e.setDeletedAt(Instant.now());
        expenses.saveAndFlush(e);
        touch(report);
        return view(report);
    }

    // Status changes

    /** DRAFT to POSTED: numbers the report (once) and posts its entry. Refused on a locked date. */
    @Transactional
    public ExpenseReportResponse post(UUID id) {
        UUID orgId = org();
        ExpenseReport report = draft(orgId, id);
        List<Expense> lines = expenses.findReportLines(orgId, id);
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("Add at least one expense to the report before posting it");
        }
        // Each line needs a cost center: its own, else the report's.
        if (report.getCostCenterId() == null && lines.stream().anyMatch(l -> l.getCostCenterId() == null)) {
            throw new IllegalArgumentException(ExpenseService.COST_CENTER_REQUIRED);
        }
        LocalDate date = LedgerAutoPostingService.expenseReportDate(report, lines);
        posting.checkNotLocked(orgId, date);
        if (report.getNumber() == null) {
            report.setNumber(numbers.next(orgId, "NF"));
        }
        report.setStatus(ExpenseReport.Status.POSTED);
        report.setPostedAt(Instant.now());
        ExpenseReport saved = reports.saveAndFlush(report);
        ledger.expenseReport(saved);
        return view(saved);
    }

    /** POSTED (not reimbursed) back to DRAFT: its entry is reversed, its number kept. */
    @Transactional
    public ExpenseReportResponse unpost(UUID id) {
        ExpenseReport report = find(org(), id);
        if (report.getStatus() != ExpenseReport.Status.POSTED) {
            throw new IllegalArgumentException(report.getStatus() == ExpenseReport.Status.REIMBURSED
                    ? "This expense report has been reimbursed: it cannot be unposted"
                    : "Only a posted expense report can be unposted");
        }
        report.setStatus(ExpenseReport.Status.DRAFT);
        report.setPostedAt(null);
        ExpenseReport saved = reports.saveAndFlush(report);
        ledger.expenseReport(saved);
        return view(saved);
    }

    /** POSTED to REIMBURSED: posts 4438 against the bank account (BQ) or the cash register (CA) on the date. */
    @Transactional
    public ExpenseReportResponse reimburse(UUID id, ReimburseRequest r) {
        UUID orgId = org();
        ExpenseReport report = find(orgId, id);
        if (report.getStatus() != ExpenseReport.Status.POSTED) {
            throw new IllegalArgumentException("Only a posted expense report can be reimbursed");
        }
        if (r == null || r.date() == null) throw new IllegalArgumentException("The reimbursement date is required");
        if (r.method() == null) throw new IllegalArgumentException("Choose how the employee is reimbursed");
        posting.checkNotLocked(orgId, r.date());
        UUID bankAccountId = null;
        if (r.method() == ExpenseReport.ReimbursementMethod.BANK_TRANSFER) {
            bankAccountId = r.bankAccountId();
            if (bankAccountId != null) {
                Boolean known = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM bank_account WHERE organization_id = ?"
                        + " AND id = ? AND deleted_at IS NULL)", Boolean.class, orgId, bankAccountId);
                if (!Boolean.TRUE.equals(known)) throw new IllegalArgumentException("Unknown bank account");
            } else {
                bankAccountId = jdbc.queryForList("SELECT id FROM bank_account WHERE organization_id = ? AND deleted_at IS NULL"
                        + " ORDER BY is_active DESC, created_at LIMIT 1", UUID.class, orgId).stream().findFirst().orElse(null);
            }
        } else if (r.bankAccountId() != null) {
            throw new IllegalArgumentException("A cash reimbursement has no bank account");
        }
        report.setStatus(ExpenseReport.Status.REIMBURSED);
        report.setReimbursedAt(Instant.now());
        report.setReimbursementDate(r.date());
        report.setReimbursementMethod(r.method());
        report.setReimbursementBankAccountId(bankAccountId);
        ExpenseReport saved = reports.saveAndFlush(report);
        ledger.expenseReport(saved);
        return view(saved);
    }

    /** DRAFT to CANCELLED. Its lines stay on the report but leave the expenses list. */
    @Transactional
    public ExpenseReportResponse cancel(UUID id) {
        ExpenseReport report = draft(org(), id);
        report.setStatus(ExpenseReport.Status.CANCELLED);
        ExpenseReport saved = reports.saveAndFlush(report);
        ledger.expenseReport(saved);
        return view(saved);
    }

    // PDF

    /**
     * The printable report: the table of lines with totals and signatures, then the receipts in line
     * order (images embedded, then PDF receipts' pages appended; other files listed by name), then
     * the report-level documents.
     */
    @Transactional(readOnly = true)
    public byte[] pdf(UUID id) {
        UUID orgId = org();
        ExpenseReport report = find(orgId, id);
        List<Expense> lines = expenses.findReportLines(orgId, id);
        List<PdfDocumentService.ExpenseReportPdf.Row> rows = new ArrayList<>();
        List<PdfDocumentService.ExpenseReportPdf.Proof> proofs = new ArrayList<>();
        BigDecimal ht = BigDecimal.ZERO, vat = BigDecimal.ZERO, ttc = BigDecimal.ZERO;
        int k = 0;
        for (Expense e : lines) {
            BigDecimal t = money(e.getAmount());
            BigDecimal v = money(e.getVatAmount());
            rows.add(new PdfDocumentService.ExpenseReportPdf.Row(e.getExpenseDate(), e.getCategory(), e.getDescription(),
                    e.getVendorName(), e.getReference(), t.subtract(v), v, t));
            ht = ht.add(t.subtract(v));
            vat = vat.add(v);
            ttc = ttc.add(t);
            List<StoredFile> receipts = new ArrayList<>(files.findByOrganizationIdAndOwnerEntityTypeAndOwnerEntityId(orgId,
                    LINE_FILES, e.getId()));
            receipts.sort(Comparator.comparing(StoredFile::getUploadedAt, Comparator.nullsLast(Comparator.naturalOrder())));
            for (StoredFile f : receipts) {
                k++;
                String what = e.getDescription() != null ? e.getDescription()
                        : e.getVendorName() != null ? e.getVendorName() : e.getCategory();
                String caption = "Justificatif n° " + k + " : " + PdfDocumentService.formatDateFr(e.getExpenseDate()) + " "
                        + what + " " + PdfDocumentService.formatAmountFr(t) + " MAD";
                proofs.add(proof(f, caption));
            }
        }
        List<StoredFile> documents = new ArrayList<>(files.findByOrganizationIdAndOwnerEntityTypeAndOwnerEntityId(orgId,
                REPORT_FILES, id));
        documents.sort(Comparator.comparing(StoredFile::getUploadedAt, Comparator.nullsLast(Comparator.naturalOrder())));
        for (StoredFile f : documents) {
            k++;
            proofs.add(proof(f, "Justificatif n° " + k + " : " + f.getFileName()));
        }
        var doc = new PdfDocumentService.ExpenseReportPdf(report.getNumber(), report.getEmployeeName(), report.getTitle(),
                report.getPeriodFrom(), report.getPeriodTo(), report.getStatus().name(), report.getNotes(),
                report.getReimbursementDate(), rows, ht, vat, ttc, proofs);
        return pdf.generateExpenseReportPdf(doc, organizations.findById(orgId).orElse(null),
                templates.brandingFor(orgId, (com.bento.crm.partner.model.Partner) null));
    }

    private PdfDocumentService.ExpenseReportPdf.Proof proof(StoredFile f, String caption) {
        byte[] content = null;
        String type = f.getContentType() == null ? "" : f.getContentType().toLowerCase(Locale.ROOT);
        if (type.startsWith("image/") || type.equals("application/pdf")) {
            try {
                content = fileStorage.readAll(f);
            } catch (RuntimeException ex) {
                log.warn("[expense-report] receipt {} could not be read: {}", f.getId(), ex.getMessage());
            }
        }
        return new PdfDocumentService.ExpenseReportPdf.Proof(caption, f.getFileName(), type, content);
    }

    // Helpers

    private ExpenseReport find(UUID orgId, UUID id) {
        return reports.findForOrganization(orgId, id)
                .orElseThrow(() -> new ResourceNotFoundException("Expense report not found"));
    }

    private ExpenseReport draft(UUID orgId, UUID id) {
        ExpenseReport report = find(orgId, id);
        if (report.getStatus() != ExpenseReport.Status.DRAFT) {
            throw new IllegalArgumentException("Only a draft expense report can be changed");
        }
        return report;
    }

    private Expense line(UUID orgId, ExpenseReport report, UUID expenseId) {
        Expense e = expenses.findForOrganization(orgId, expenseId)
                .orElseThrow(() -> new ResourceNotFoundException("Expense not found"));
        if (!report.getId().equals(e.getExpenseReportId())) {
            throw new ResourceNotFoundException("Expense not found on this report");
        }
        return e;
    }

    /** Bumps the report's version and updated_at when its lines change. */
    private void touch(ExpenseReport report) {
        report.setUpdatedAt(Instant.now());
        reports.saveAndFlush(report);
    }

    private void apply(ExpenseReport report, ExpenseReportRequest r) {
        UUID orgId = report.getOrganizationId();
        String name = blank(r.employeeName());
        if (r.employeeUserId() != null) {
            List<String> names = jdbc.queryForList("SELECT display_name FROM app_user WHERE organization_id = ? AND id = ?",
                    String.class, orgId, r.employeeUserId());
            if (names.isEmpty()) throw new IllegalArgumentException("Unknown user");
            if (name == null) name = blank(names.get(0));
        }
        if (name == null) throw new IllegalArgumentException("The employee is required");
        if (r.periodFrom() != null && r.periodTo() != null && r.periodFrom().isAfter(r.periodTo())) {
            throw new IllegalArgumentException("The period starts after it ends");
        }
        checkCostCenter(orgId, r.costCenterId());
        report.setEmployeeUserId(r.employeeUserId());
        report.setEmployeeName(name.length() > 160 ? name.substring(0, 160) : name);
        report.setTitle(blank(r.title()));
        report.setPeriodFrom(r.periodFrom());
        report.setPeriodTo(r.periodTo());
        report.setNotes(blank(r.notes()));
        report.setCostCenterId(r.costCenterId());
    }

    private void applyLine(ExpenseReport report, Expense e, LineRequest r) {
        if (r == null) throw new IllegalArgumentException("The expense is required");
        if (r.costCenterId() == null && report.getCostCenterId() == null) {
            throw new IllegalArgumentException(ExpenseService.COST_CENTER_REQUIRED);
        }
        BigDecimal vat = r.vatAmount();
        String code = r.vatCode() == null || r.vatCode().isBlank() ? null : r.vatCode().trim().toUpperCase(Locale.ROOT);
        if (vat == null && code != null && r.amount() != null) {
            List<BigDecimal> rates = jdbc.queryForList("SELECT rate FROM ledger_vat_setup WHERE organization_id = ? AND code = ?",
                    BigDecimal.class, e.getOrganizationId(), code);
            if (!rates.isEmpty() && rates.get(0) != null && rates.get(0).signum() > 0) {
                BigDecimal rate = rates.get(0);
                vat = r.amount().multiply(rate).divide(rate.add(BigDecimal.valueOf(100)), 2, RoundingMode.HALF_UP);
            }
        }
        expenseService.apply(e, new ExpenseRequest(r.expenseDate(), r.category(), r.description(), r.amount(), vat,
                Payment.Method.EMPLOYEE, r.vendorPartnerId(), r.vendorName(), r.reference(), r.costCenterId(), r.vatCode(),
                r.missionId(), r.dealId(), r.customerId()));
    }

    private void checkCostCenter(UUID orgId, UUID costCenterId) {
        if (costCenterId == null) return;
        Boolean known = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM ledger_cost_center WHERE organization_id = ?"
                + " AND id = ? AND deleted_at IS NULL)", Boolean.class, orgId, costCenterId);
        if (!Boolean.TRUE.equals(known)) throw new IllegalArgumentException("Unknown cost center");
    }

    private ExpenseReportResponse view(ExpenseReport r) {
        UUID orgId = r.getOrganizationId();
        List<Expense> items = expenses.findReportLines(orgId, r.getId());
        Map<UUID, Long> counts = new HashMap<>();
        if (!items.isEmpty()) {
            jdbc.query("SELECT owner_entity_id, COUNT(*) FROM stored_file WHERE organization_id = ? AND owner_entity_type = ?"
                    + " AND owner_entity_id = ANY(?) AND deleted_at IS NULL GROUP BY owner_entity_id", rs -> {
                counts.put(rs.getObject(1, UUID.class), rs.getLong(2));
            }, orgId, LINE_FILES, items.stream().map(Expense::getId).toArray(UUID[]::new));
        }
        com.bento.crm.expense.dto.ExpenseDtos.Names names = expenseService.names(items);
        List<Line> lines = items.stream().map(e -> Line.of(e, counts.getOrDefault(e.getId(), 0L), names)).toList();
        BigDecimal ht = BigDecimal.ZERO, vat = BigDecimal.ZERO, ttc = BigDecimal.ZERO;
        for (Line l : lines) {
            ht = ht.add(l.amountHt());
            vat = vat.add(l.vatAmount());
            ttc = ttc.add(l.amount());
        }
        Long docs = jdbc.queryForObject("SELECT COUNT(*) FROM stored_file WHERE organization_id = ? AND owner_entity_type = ?"
                + " AND owner_entity_id = ? AND deleted_at IS NULL", Long.class, orgId, REPORT_FILES, r.getId());
        return new ExpenseReportResponse(r.getId(), r.getNumber(), r.getEmployeeUserId(), r.getEmployeeName(), r.getTitle(),
                r.getPeriodFrom(), r.getPeriodTo(), r.getNotes(), r.getStatus(), r.getPostedAt(), r.getReimbursedAt(),
                r.getReimbursementDate(), r.getReimbursementMethod(), r.getReimbursementBankAccountId(), r.getCostCenterId(),
                LedgerAutoPostingService.expenseReportDate(r, items), lines.size(), new Totals(money(ht), money(vat), money(ttc)),
                docs == null ? 0 : docs, lines, r.getCreatedBy(), r.getCreatedAt(), r.getUpdatedAt());
    }

    private ExpenseReportResponse row(ResultSet rs, int i) throws SQLException {
        BigDecimal ttc = money(rs.getBigDecimal("ttc"));
        BigDecimal vat = money(rs.getBigDecimal("vat"));
        String method = rs.getString("reimbursement_method");
        LocalDate periodTo = rs.getObject("period_to", LocalDate.class);
        return new ExpenseReportResponse(rs.getObject("id", UUID.class), rs.getString("number"),
                rs.getObject("employee_user_id", UUID.class), rs.getString("employee_name"), rs.getString("title"),
                rs.getObject("period_from", LocalDate.class), periodTo, rs.getString("notes"),
                ExpenseReport.Status.valueOf(rs.getString("status")), instant(rs.getTimestamp("posted_at")),
                instant(rs.getTimestamp("reimbursed_at")), rs.getObject("reimbursement_date", LocalDate.class),
                method == null ? null : ExpenseReport.ReimbursementMethod.valueOf(method),
                rs.getObject("reimbursement_bank_account_id", UUID.class), rs.getObject("cost_center_id", UUID.class),
                periodTo != null ? periodTo : rs.getObject("last_date", LocalDate.class), rs.getInt("lines"),
                new Totals(ttc.subtract(vat), vat, ttc), rs.getLong("files"), null,
                rs.getObject("created_by", UUID.class), instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("updated_at")));
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static ExpenseReport.Status status(String s) {
        try {
            return ExpenseReport.Status.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("The status is DRAFT, POSTED, REIMBURSED or CANCELLED");
        }
    }

    private static UUID uuidOrNull(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static BigDecimal money(BigDecimal v) {
        return v == null ? BigDecimal.ZERO.setScale(2) : v.setScale(2, RoundingMode.HALF_UP);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
