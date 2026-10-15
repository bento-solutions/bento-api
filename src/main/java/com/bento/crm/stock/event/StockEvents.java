package com.bento.crm.stock.event;

import java.util.UUID;

/**
 * Business events that can move stock. The services that own invoices, purchase orders and deals
 * publish them; {@code StockService} decides, from the organization's stock settings, whether
 * stock actually moves. Listeners run synchronously in the publisher's transaction.
 */
public final class StockEvents {

    private StockEvents() {}

    /** A customer invoice went out (created as, or moved to, SENT). */
    public record InvoiceIssued(UUID organizationId, UUID invoiceId) {}

    /** A purchase order was marked delivered: its goods arrived. */
    public record PurchaseOrderDelivered(UUID organizationId, UUID purchaseOrderId) {}

    /** A deal was confirmed (left OPEN for any stage other than lost). */
    public record DealWon(UUID organizationId, UUID dealId) {}
    /** A sent customer invoice was deleted: its goods go back into stock. */
    public record InvoiceCancelled(UUID organizationId, UUID invoiceId) {}
    /** A won deal was lost or deleted: its goods go back into stock. */
    public record DealLost(UUID organizationId, UUID dealId) {}
    /** A sent customer invoice was edited: stock follows its new quantities. */
    public record InvoiceChanged(UUID organizationId, UUID invoiceId) {}
    /** A won deal's order lines were edited: stock follows its new quantities. */
    public record DealChanged(UUID organizationId, UUID dealId) {}
}
