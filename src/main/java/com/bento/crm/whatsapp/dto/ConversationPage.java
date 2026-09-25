package com.bento.crm.whatsapp.dto;

import java.util.List;

/** A page of the inbox, newest activity first; pass {@code nextCursor} back to continue. */
public record ConversationPage(List<ConversationView> items, String nextCursor) {
}
