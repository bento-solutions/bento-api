package com.bento.crm.identity.model;

import com.bento.crm.common.model.BaseTenantEntity;
import com.bento.crm.common.model.UserRole;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "app_user", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"organization_id", "email"})
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppUser extends BaseTenantEntity {

    /**
     * The person behind this Membership. Their password and email verification live on the
     * {@link Account}; this row only holds what is specific to one Organization.
     */
    @Column(nullable = false, columnDefinition = "uuid")
    private UUID accountId;

    /** A copy of the Account's address, kept because so much Organization-scoped code reads it. */
    @Column(nullable = false)
    private String email;

    @Column(nullable = false)
    private String displayName;

    @Column(length = 5)
    private String initials;

    @Column(length = 20)
    private String avatarColor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserRole role;

    @Column(columnDefinition = "uuid")
    private UUID teamId;

    @Column(nullable = false)
    private Boolean isActive;

    private String phone;

    private String jobTitle;

    @Column(length = 5)
    private String language;

    private Boolean notifyOnLeadAssign;

    private Boolean notifyOnDealUpdate;

    private Boolean notifyOnMention;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> dashboardKpis;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> roleOverridePermissions;

    private Instant lastActiveAt;
}
