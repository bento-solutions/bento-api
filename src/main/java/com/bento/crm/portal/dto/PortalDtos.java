package com.bento.crm.portal.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class PortalDtos {

    private PortalDtos() {}

    // Staff side

    public record Account(UUID id, UUID partnerId, String email, String displayName, boolean active, boolean invitePending,
                          Instant inviteExpiresAt, Instant lastLoginAt, Instant createdAt) {}

    public record InviteRequest(UUID partnerId, String email, String displayName) {}

    /** The invitation link is returned once so it can also be sent by WhatsApp. */
    public record Invited(Account account, String inviteUrl, boolean emailed) {}

    public record AccountUpdate(Boolean active, String displayName) {}

    // Sign-in

    public record LoginRequest(String email, String password, UUID organizationId) {}

    public record AcceptRequest(String token, String password, String displayName) {}

    public record ForgotRequest(String email) {}

    public record ResetRequest(String token, String password) {}

    public record Session(String token, long expiresInSeconds, Me me) {}

    /** When one email is a client of several firms, the person picks one. */
    public record FirmChoice(UUID organizationId, String firmName) {}

    public record ChooseFirm(List<FirmChoice> firms) {}

    // The client's own data

    public record Me(UUID id, String email, String displayName, UUID partnerId, String companyName, UUID organizationId,
                     String firmName, String firmLogoUrl) {}

    public record Home(Me me, int documentsMissing, int openInvoices, BigDecimal amountDue, int overdueInvoices,
                       LocalDate nextTaxDeadline, String nextTaxDeadlineKind, int missionsInProgress) {}

    public record Invoice(UUID id, String invoiceNumber, LocalDate invoiceDate, LocalDate dueDate, String status,
                          BigDecimal total, BigDecimal owed, String currency, String payUrl) {}

    public record Mission(UUID id, String name, String status, LocalDate startDate, LocalDate dueDate,
                          List<Deliverable> deliverables) {}

    public record Deliverable(UUID id, String title, LocalDate dueDate, String status, Instant deliveredAt,
                              boolean hasFile, String fileName) {}

    public record TaxDeadline(UUID id, String kind, String periodLabel, LocalDate dueDate, String status, boolean overdue) {}
}
