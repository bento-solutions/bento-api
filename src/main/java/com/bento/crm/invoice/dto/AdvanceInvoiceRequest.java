package com.bento.crm.invoice.dto;

import com.bento.crm.invoice.model.Invoice;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * POST /invoices/advance. Exactly one of {@code amount} and {@code percent}; the amount is before
 * VAT unless {@code amountIncludesVat}. {@code type} is CUSTOMER (default) or VENDOR.
 */
public record AdvanceInvoiceRequest(UUID partnerId, UUID dealId, UUID proposalId, BigDecimal amount, BigDecimal percent,
                                    Boolean amountIncludesVat, String vatCode, LocalDate date, LocalDate dueDate,
                                    String label, Invoice.InvoiceType type) {}
