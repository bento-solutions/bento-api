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

    @Transactional(readOnly = true)
    public Page<Expense> list(LocalDate from, LocalDate to, String category, Pageable pageable) {
        return repository.search(TenantContext.getCurrentOrganizationId(), from != null ? from : EARLIEST,
                to != null ? to : LATEST, blankToNull(category), pageable);
    }

    @Transactional
    public Expense create(ExpenseRequest r) {
        Expense e = new Expense();
        e.setOrganizationId(TenantContext.getCurrentOrganizationId());
        apply(e, r);
        return repository.save(e);
    }

    @Transactional
    public Expense update(UUID id, ExpenseRequest r) {
        Expense e = get(id);
        apply(e, r);
        return repository.save(e);
    }

    @Transactional
    public void delete(UUID id) {
        Expense e = get(id);
        e.setDeletedAt(Instant.now());
        repository.save(e);
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

    private void apply(Expense e, ExpenseRequest r) {
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
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
