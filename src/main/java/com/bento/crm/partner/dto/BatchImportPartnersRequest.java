package com.bento.crm.partner.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * A scraped/imported lot of leads sharing one brand and business type, so an operator does not
 * have to set them per-row: any entry that already carries its own {@code brand_id}/
 * {@code business_type_id} keeps it, everything else falls back to these lot-level defaults.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BatchImportPartnersRequest {

    @JsonProperty("brand_id")
    private String brandId;

    @JsonProperty("business_type_id")
    private String businessTypeId;

    @NotEmpty
    @Valid
    private List<CreatePartnerRequest> partners;
}
