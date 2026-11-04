package com.bento.crm.expense.service;

import com.bento.crm.common.context.TenantContext;
import com.bento.crm.common.exception.ResourceNotFoundException;
import com.bento.crm.expense.dto.ExpenseDtos.*;
import com.bento.crm.expense.model.Expense;
import com.bento.crm.expense.repository.ExpenseRepository;
import com.bento.crm.partner.repository.PartnerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ExpenseService {

    private static final LocalDate EARLIEST = LocalDate.of(1900, 1, 1);
    private static final LocalDate LATEST = LocalDate.of(9999, 12, 31);

    private final ExpenseRepository repository;
    private final PartnerRepository partnerRepository;
    private final com.bento.crm.ledger.service.LedgerAutoPostingService ledger;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final com.bento.crm.ledger.service.LedgerPostingSetupService postingSetup;

    @Transactional(readOnly = true)
    public Page<Expense> list(LocalDate from, LocalDate to, String category, Pageable pageable) {
        return repository.search(TenantContext.getCurrentOrganizationId(), from != null ? from : EARLIEST,
                to != null ? to : LATEST, category == null || category.isBlank() ? null : category.trim().toUpperCase(java.util.Locale.ROOT), pageable);
    }

    /** The number and status of the reports these expenses belong to, by report id. */
    @Transactional(readOnly = true)
    public java.util.Map<UUID, ReportRef> reportRefs(java.util.Collection<Expense> expenses) {
        List<UUID> ids = expenses.stream().map(Expense::getExpenseReportId).filter(java.util.Objects::nonNull).distinct().toList();
        java.util.Map<UUID, ReportRef> out = new java.util.HashMap<>();
        if (ids.isEmpty()) return out;
        jdbc.query("SELECT id, number, status FROM expense_report WHERE organization_id = ? AND id = ANY(?)", rs -> {
            out.put(rs.getObject(1, UUID.class), new ReportRef(rs.getString(2), rs.getString(3)));
        }, TenantContext.getCurrentOrganizationId(), ids.toArray(new UUID[0]));
        return out;
    }

    @Transactional
    public Expense create(ExpenseRequest r) {
        if (r.paymentMethod() == com.bento.crm.payment.model.Payment.Method.EMPLOYEE) {
            throw new IllegalArgumentException("Add expenses paid by an employee to an expense report");
        }
        Expense e = new Expense();
        e.setOrganizationId(TenantContext.getCurrentOrganizationId());
        apply(e, r);
        Expense saved = repository.save(e);
        ledger.expense(saved);
        return saved;
    }

    @Transactional
    public Expense update(UUID id, ExpenseRequest r) {
        Expense e = get(id);
        notInReport(e);
        if (r.paymentMethod() == com.bento.crm.payment.model.Payment.Method.EMPLOYEE) {
            throw new IllegalArgumentException("Add expenses paid by an employee to an expense report");
        }
        apply(e, r);
        Expense saved = repository.save(e);
        ledger.expense(saved);
        return saved;
    }

    @Transactional
    public void delete(UUID id) {
        Expense e = get(id);
        notInReport(e);
        e.setDeletedAt(Instant.now());
        repository.save(e);
        ledger.expense(e);
    }

    @Transactional(readOnly = true)
    public Summary summary(LocalDate from, LocalDate to) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        LocalDate f = from != null ? from : EARLIEST;
        LocalDate t = to != null ? to : LATEST;
        List<Summary.CategoryTotal> rows = repository.totalsByCategory(orgId, f, t).stream()
                .map(r -> new Summary.CategoryTotal((String) r[0], (BigDecimal) r[1], (BigDecimal) r[2], (Long) r[3]))
                .toList();
        BigDecimal total = rows.stream().map(Summary.CategoryTotal::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal vat = rows.stream().map(Summary.CategoryTotal::vat).reduce(BigDecimal.ZERO, BigDecimal::add);
        long count = rows.stream().mapToLong(Summary.CategoryTotal::count).sum();
        return new Summary(from, to, total, vat, count, rows, Expense.CATEGORIES);
    }

    private Expense get(UUID id) {
        return repository.findForOrganization(TenantContext.getCurrentOrganizationId(), id)
                .orElseThrow(() -> new ResourceNotFoundException("Expense not found"));
    }

    /** A line of an expense report is changed through its report. */
    private static void notInReport(Expense e) {
        if (e.getExpenseReportId() != null) {
            throw new IllegalArgumentException("This expense is a line of an expense report: change it from the report");
        }
    }

    /** Checks and copies a request onto an expense (a standalone one or a line of an expense report). */
    public void apply(Expense e, ExpenseRequest r) {
        if (r.vatAmount() != null && r.vatAmount().compareTo(r.amount()) > 0) {
            throw new IllegalArgumentException("VAT cannot be more than the amount");
        }
        if (r.vendorPartnerId() != null) {
            partnerRepository.findByOrganizationIdAndId(e.getOrganizationId(), r.vendorPartnerId())
                    .orElseThrow(() -> new IllegalArgumentException("Unknown supplier"));
        }
        e.setExpenseDate(r.expenseDate());
        e.setCategory(r.category().trim().toUpperCase());
        e.setDescription(blankToNull(r.description()));
        e.setAmount(r.amount());
        e.setVatAmount(r.vatAmount());
        e.setPaymentMethod(r.paymentMethod());
        e.setVendorPartnerId(r.vendorPartnerId());
        e.setVendorName(blankToNull(r.vendorName()));
        e.setReference(blankToNull(r.reference()));
        if (r.costCenterId() != null) {
            Boolean known = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM ledger_cost_center WHERE organization_id = ?"
                    + " AND id = ? AND deleted_at IS NULL)", Boolean.class, e.getOrganizationId(), r.costCenterId());
            if (!Boolean.TRUE.equals(known)) {
                throw new IllegalArgumentException("Unknown cost center");
            }
        }
        e.setCostCenterId(r.costCenterId());
        // Null with a VAT amount posts as TVA20, as before phase 3.
        e.setVatCode(postingSetup.vatCode(e.getOrganizationId(), r.vatCode()));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
