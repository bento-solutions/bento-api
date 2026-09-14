package com.bento.crm.partner.repository;

import com.bento.crm.partner.model.Partner;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class PartnerSpecification {

    public static Specification<Partner> filter(
            UUID organizationId,
            String q,
            Partner.PartnerType type,
            Partner.PartnerStage stage,
            UUID assignedToUserId) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            // 1. Mandatory Tenant Isolation & Soft-delete filter
            predicates.add(cb.equal(root.get("organizationId"), organizationId));
            predicates.add(cb.isNull(root.get("deletedAt")));

            // 2. Free-text search query (q)
            if (q != null && !q.isBlank()) {
                String pattern = "%" + q.trim().toLowerCase() + "%";
                Predicate nameMatch = cb.like(cb.lower(root.get("name")), pattern);
                Predicate companyMatch = cb.like(cb.lower(cb.coalesce(root.get("companyName"), "")), pattern);
                Predicate emailMatch = cb.like(cb.lower(cb.coalesce(root.get("email"), "")), pattern);
                Predicate phoneMatch = cb.like(cb.coalesce(root.get("phone"), ""), pattern);
                Predicate cityMatch = cb.like(cb.lower(cb.coalesce(root.get("city"), "")), pattern);

                predicates.add(cb.or(nameMatch, companyMatch, emailMatch, phoneMatch, cityMatch));
            }

            // 3. Partner Type filter
            if (type != null) {
                predicates.add(cb.equal(root.get("type"), type));
            }

            // 4. Partner Stage filter
            if (stage != null) {
                predicates.add(cb.equal(root.get("stage"), stage));
            }

            // 5. Assigned user
            if (assignedToUserId != null) {
                predicates.add(cb.equal(root.get("assignedToUserId"), assignedToUserId));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
