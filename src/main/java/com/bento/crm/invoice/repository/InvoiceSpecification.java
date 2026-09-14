package com.bento.crm.invoice.repository;

import com.bento.crm.invoice.model.Invoice;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class InvoiceSpecification {

    public static Specification<Invoice> filter(
            UUID organizationId,
            String q,
            Invoice.InvoiceType type,
            Invoice.Status status,
            UUID partnerId,
            LocalDate fromDate,
            LocalDate toDate) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            // 1. Mandatory Tenant Isolation & Soft-delete filter
            predicates.add(cb.equal(root.get("organizationId"), organizationId));
            predicates.add(cb.isNull(root.get("deletedAt")));

            // 2. Free-text search query (q)
            if (q != null && !q.isBlank()) {
                String pattern = "%" + q.trim().toLowerCase() + "%";
                Predicate invNumMatch = cb.like(cb.lower(cb.coalesce(root.get("invoiceNumber"), "")), pattern);
                Predicate custNameMatch = cb.like(cb.lower(cb.coalesce(root.get("customerName"), "")), pattern);
                Predicate custAccMatch = cb.like(cb.lower(cb.coalesce(root.get("customerAccount"), "")), pattern);
                Predicate vatMatch = cb.like(cb.lower(cb.coalesce(root.get("vatNumber"), "")), pattern);

                predicates.add(cb.or(invNumMatch, custNameMatch, custAccMatch, vatMatch));
            }

            // 3. Invoice Type (CUSTOMER / VENDOR)
            if (type != null) {
                predicates.add(cb.equal(root.get("type"), type));
            }

            // 4. Status (DRAFT / SENT / PAID etc.)
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }

            // 5. Partner ID
            if (partnerId != null) {
                predicates.add(cb.equal(root.get("partnerId"), partnerId));
            }

            // 6. Date ranges
            if (fromDate != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("invoiceDate"), fromDate));
            }
            if (toDate != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("invoiceDate"), toDate));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
