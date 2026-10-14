package com.bento.crm.invoice.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class InvoiceReminderPolicyRequest {

    @NotNull
    private Boolean enabled;

    /** Days relative to the due date, from 30 days before to a year after. */
    @NotNull
    @Size(min = 1, max = 10)
    private List<@NotNull @Min(-30) @Max(365) Integer> offsetsDays;

    @Size(max = 2000)
    private String message;
}
