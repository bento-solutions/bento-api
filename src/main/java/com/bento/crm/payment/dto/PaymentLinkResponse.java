package com.bento.crm.payment.dto;

import com.bento.crm.payment.model.PaymentLink;
import com.bento.crm.payment.model.PaymentSettings;

import java.time.Instant;
import java.util.UUID;

public record PaymentLinkResponse(UUID id, UUID invoiceId, String url, PaymentLink.Status status,
                                  PaymentSettings.Provider provider, Instant paidAt, Instant createdAt) {

    public static PaymentLinkResponse of(PaymentLink link, String url) {
        return new PaymentLinkResponse(link.getId(), link.getInvoiceId(), url, link.getStatus(),
                link.getProvider(), link.getPaidAt(), link.getCreatedAt());
    }
}
