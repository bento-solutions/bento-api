package com.bento.crm.inbound.model;

import com.bento.crm.common.model.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** An email address of the organization that documents can be sent to. */
@Entity
@Table(name = "inbound_mailbox")
@Getter
@Setter
public class InboundMailbox extends BaseTenantEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Kind kind;

    @Column(nullable = false, unique = true, length = 60)
    private String token;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "allowed_senders", columnDefinition = "text[]", nullable = false)
    private String[] allowedSenders = new String[0];

    @Column(name = "auto_book", nullable = false)
    private boolean autoBook = true;

    @Column(nullable = false)
    private boolean active = true;

    public enum Kind { VENDOR_INVOICES, BANK_STATEMENTS }
}
