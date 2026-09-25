package com.bento.crm.whatsapp.dto;

/** Unread totals across the conversations the viewer can see, for the nav badge. */
public record UnreadSummary(long conversations, long messages) {
}
