package com.bento.crm.partner.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Backs the dashboard's "New Leads" tile. Computed server-side because the partner list the
 * dashboard holds is paginated, so counting leads client-side would silently undercount.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LeadKpiResponse {

    /** Every live (non-deleted) lead, whatever its age. */
    private long total;

    /** Leads created since the first of the current month (UTC). */
    @JsonProperty("new_this_month")
    private long newThisMonth;

    /** Leads created during the previous calendar month, the baseline for the trend chip. */
    @JsonProperty("previous_month")
    private long previousMonth;

    /** Leads created per calendar month, oldest first, current month last (12 entries). */
    @JsonProperty("monthly_series")
    private List<Long> monthlySeries;
}
