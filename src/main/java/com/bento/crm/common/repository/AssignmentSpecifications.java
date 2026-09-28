package com.bento.crm.common.repository;

import org.springframework.data.jpa.domain.Specification;

import java.util.UUID;

/**
 * Reusable predicate for entities that own an {@code assignedToUserId} column, so Tasks and
 * Tickets filter "assigned to me / assigned to X / unassigned" identically.
 */
public final class AssignmentSpecifications {

    /** Sentinel query value meaning "no assignee set", since {@code assignedToUserId} is a UUID column. */
    public static final String UNASSIGNED = "none";

    private AssignmentSpecifications() {
    }

    /**
     * Filters on {@code assignedToUserId}. A {@code null} value leaves the predicate unconstrained;
     * {@link #UNASSIGNED} (case-insensitive) matches records with no assignee; anything else is
     * parsed as the assignee's user id.
     */
    public static <T> Specification<T> assignedTo(String assignedToUserId) {
        return (root, query, cb) -> {
            if (assignedToUserId == null) {
                return cb.conjunction();
            }
            if (UNASSIGNED.equalsIgnoreCase(assignedToUserId)) {
                return cb.isNull(root.get("assignedToUserId"));
            }
            return cb.equal(root.get("assignedToUserId"), UUID.fromString(assignedToUserId));
        };
    }
}
