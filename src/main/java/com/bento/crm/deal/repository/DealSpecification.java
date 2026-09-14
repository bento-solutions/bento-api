package com.bento.crm.deal.repository;

import com.bento.crm.deal.model.Deal;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class DealSpecification {

    public static Specification<Deal> filter(
            UUID organizationId,
            String q,
            Deal.DealStage stage,
            UUID partnerId,
            UUID salesPersonUserId) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            // 1. Mandatory Tenant Isolation & Soft-delete filter
            predicates.add(cb.equal(root.get("organizationId"), organizationId));
            predicates.add(cb.isNull(root.get("deletedAt")));

            // 2. Free-text search query (q)
            if (q != null && !q.isBlank()) {
                String pattern = "%" + q.trim().toLowerCase() + "%";
                Predicate titleMatch = cb.like(cb.lower(root.get("title")), pattern);
                Predicate orderNumMatch = cb.like(cb.lower(cb.coalesce(root.get("orderNumber"), "")), pattern);
                Predicate customerAccMatch = cb.like(cb.lower(cb.coalesce(root.get("customerAccount"), "")), pattern);
                Predicate vendorAccMatch = cb.like(cb.lower(cb.coalesce(root.get("vendorAccount"), "")), pattern);
                Predicate poRefMatch = cb.like(cb.lower(cb.coalesce(root.get("purchaseOrderRef"), "")), pattern);
                Predicate contactMatch = cb.like(cb.lower(cb.coalesce(root.get("contactPerson"), "")), pattern);

                predicates.add(cb.or(titleMatch, orderNumMatch, customerAccMatch, vendorAccMatch, poRefMatch, contactMatch));
            }

            // 3. Stage filter
            if (stage != null) {
                predicates.add(cb.equal(root.get("stage"), stage));
            }

            // 4. Partner filter
            if (partnerId != null) {
                predicates.add(cb.equal(root.get("partnerId"), partnerId));
            }

            // 5. Sales person filter
            if (salesPersonUserId != null) {
                predicates.add(cb.equal(root.get("salesPersonUserId"), salesPersonUserId));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
