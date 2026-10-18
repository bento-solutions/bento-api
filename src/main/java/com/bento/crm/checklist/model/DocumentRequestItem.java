package com.bento.crm.checklist.model;

import com.bento.crm.common.model.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "document_request_item")
@Getter
@Setter
public class DocumentRequestItem extends BaseTenantEntity {

    @Column(name = "request_id", nullable = false, columnDefinition = "uuid")
    private UUID requestId;

    @Column(nullable = false, length = 160)
    private String label;

    @Column(nullable = false)
    private boolean required = true;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.MISSING;

    @Column(name = "file_id", columnDefinition = "uuid")
    private UUID fileId;

    @Column(name = "received_at")
    private Instant receivedAt;

    @Column(name = "review_note", length = 500)
    private String reviewNote;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    /** MISSING: not sent yet. RECEIVED: sent, to check. ACCEPTED: fine. REJECTED: send again. */
    public enum Status { MISSING, RECEIVED, ACCEPTED, REJECTED }
}
