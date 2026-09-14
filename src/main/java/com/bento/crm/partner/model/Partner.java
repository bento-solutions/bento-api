package com.bento.crm.partner.model;

import com.bento.crm.common.model.BaseTenantEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "partner")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Partner extends BaseTenantEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PartnerType type;

    @Column(nullable = false)
    private String name;

    private String companyName;

    @Enumerated(EnumType.STRING)
    private RecordType recordType;

    private String email;

    private String phone;

    private String city;

    private String country;

    @Enumerated(EnumType.STRING)
    private PartnerSource source;

    private Integer score;

    @Enumerated(EnumType.STRING)
    private Temperature temperature;

    @Enumerated(EnumType.STRING)
    private Priority priority;

    @Enumerated(EnumType.STRING)
    private Qualification qualification;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PartnerStage stage;

    @Column(columnDefinition = "uuid")
    private UUID assignedToUserId;

    @Column(columnDefinition = "uuid")
    private UUID ownerId;

    @Column(columnDefinition = "uuid")
    private UUID convertedFromPartnerId;

    private BigDecimal estimatedDealValue;

    private Integer probability;

    private LocalDate expectedCloseDate;

    @Column(columnDefinition = "text")
    private String comments;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> company;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<Map<String, Object>> productInterests;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<Map<String, Object>> campaigns;

    @Column(columnDefinition = "text")
    private String notes;

    /**
     * Stable bot-provided identity (e.g. sha1 of normalized profile URL).
     * NULL for hand-entered rows; unique per organization when present.
     */
    private String externalId;

    /** Raw profile URL the lead was scraped from, for operator traceability. */
    @Column(columnDefinition = "text")
    private String sourceUrl;

    public enum PartnerType {
        LEAD, PROSPECT, CUSTOMER, VENDOR
    }

    public enum PartnerStage {
        NEW, CONTACTED, ATTEMPTED_CONTACT, MEETING_SCHEDULED, QUALIFIED,
        PROPOSAL_SENT, CONFIRMED, CUSTOMER, LOST, DISQUALIFIED
    }

    public enum RecordType {
        ORGANIZATION, INDIVIDUAL
    }

    public enum PartnerSource {
        WEBSITE, TRADE_SHOW, LINKEDIN, CAMPAIGN, REFERRAL, COLD_CALL, INBOUND, OTHER
    }

    public enum Temperature {
        COLD, WARM, HOT
    }

    public enum Priority {
        LOW, MEDIUM, HIGH
    }

    public enum Qualification {
        QUALIFIED, UNQUALIFIED, PENDING
    }
}
