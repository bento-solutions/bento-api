package com.bento.crm.partner.dto;

import com.bento.crm.partner.model.Partner;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The three views of a partner's account ("grand livre"), composed in one round trip so the
 * open/closed split and the transaction list can never disagree with each other.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PartnerLedgerResponse {

    private UUID partnerId;
    private Partner.PartnerType partnerType;

    private List<OpenInvoice> openInvoices;
    private List<ClosedInvoice> closedInvoices;
    private List<Transaction> transactions;
    private Totals totals;
    /** Open advances (unapplied advance payments, paid advance invoices not yet deducted). */
    private List<com.bento.crm.invoice.dto.AgingReport.Advance> advances;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class OpenInvoice {
        private UUID id;
        private String invoiceNumber;
        private LocalDate invoiceDate;
        private LocalDate dueDate;
        private BigDecimal totalAmount;
        private BigDecimal paidAmount;
        private BigDecimal remainingAmount;
        private BigDecimal vatAmount;
        private String status;
        private boolean overdue;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ClosedInvoice {
        private UUID id;
        private String invoiceNumber;
        private LocalDate paymentDate;
        private LocalDate invoiceDate;
        private LocalDate dueDate;
        private BigDecimal totalAmount;
        private BigDecimal vatAmount;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Transaction {
        private LocalDate date;
        private TransactionType type;
        private String documentNumber;
        /** Signed: see {@code PartnerLedgerService#signedAmount}. */
        private BigDecimal amount;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Totals {
        private BigDecimal totalInvoiced;
        private BigDecimal totalPaid;
        private BigDecimal totalOpen;
        private BigDecimal totalOverdue;
        /** Net of the signed transaction list, i.e. what the ledger closes at. */
        private BigDecimal balance;
        private BigDecimal totalCredited;
        private BigDecimal totalAdvances;
        /** totalOpen - totalAdvances. */
        private BigDecimal netBalance;
    }

    public enum TransactionType {
        INVOICE, PAYMENT, CREDIT_NOTE
    }
}
