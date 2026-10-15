package com.bento.crm.invoice.dto;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/** A brand's own invoice logo and footer; null leaves the organization's in place. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BrandInvoiceStyleRequest {

    private UUID logoFileId;

    @Size(max = 2000)
    private String footer;
}
