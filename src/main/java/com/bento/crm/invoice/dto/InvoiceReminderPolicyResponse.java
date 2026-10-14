package com.bento.crm.invoice.dto;

import com.bento.crm.invoice.model.InvoiceReminderPolicy;

import java.util.List;

public record InvoiceReminderPolicyResponse(
        boolean enabled,
        List<Integer> offsetsDays,
        InvoiceReminderPolicy.Channel channel,
        String message) {

    public static InvoiceReminderPolicyResponse fromEntity(InvoiceReminderPolicy policy) {
        return new InvoiceReminderPolicyResponse(
                Boolean.TRUE.equals(policy.getEnabled()),
                policy.getOffsetsDays(),
                policy.getChannel(),
                policy.getMessage());
    }
}
