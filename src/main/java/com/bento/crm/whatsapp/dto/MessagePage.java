package com.bento.crm.whatsapp.dto;

import java.util.List;

/** Thread messages newest first; pass {@code nextCursor} as {@code before} to load older ones. */
public record MessagePage(List<MessageView> items, String nextCursor) {
}
