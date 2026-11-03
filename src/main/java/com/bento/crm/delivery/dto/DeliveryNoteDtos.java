package com.bento.crm.delivery.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Delivery notes (bons de livraison, phase 3 part C). They never post. */
public final class DeliveryNoteDtos {

    private DeliveryNoteDtos() {}

    public record Line(String description, BigDecimal qty, String unit, UUID productId) {}

    /** status: DRAFT, DELIVERED, INVOICED or CANCELLED. The number (BL-YYYY-NNNN) is allocated on delivery. */
    public record DeliveryNote(UUID id, String number, UUID partnerId, String partnerName, UUID dealId, String dealTitle,
                               UUID missionId, LocalDate date, String status, List<Line> lines, String notes, UUID invoiceId,
                               String invoiceNumber, Instant deliveredAt, String signedBy, Instant createdAt) {}

    /** On create the partner comes from the deal when left out; the date defaults to today. */
    public record DeliveryNoteRequest(UUID partnerId, UUID dealId, UUID missionId, LocalDate date, List<Line> lines,
                                      String notes) {}

    public record DeliverRequest(LocalDate date, String signedBy) {}
}
