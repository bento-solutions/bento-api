package com.bento.crm.invoice.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** POST /invoices/{id}/credit-note. No lines: every line of the original, in full. */
public record CreditNoteRequest(List<Map<String, Object>> lines, String reason, LocalDate date) {}
