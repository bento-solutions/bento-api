package com.bento.crm.tax.dto;

import com.bento.crm.tax.model.TaxDeadline;
import com.bento.crm.tax.model.TaxProfile;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public final class TaxDtos {

    private TaxDtos() {}

    public record Profile(UUID id, UUID partnerId, String partnerName, TaxProfile.VatRegime vatRegime, boolean corporateTax,
                          int fiscalYearEndMonth, boolean hasEmployees, boolean active) {}

    public record ProfileRequest(UUID partnerId, TaxProfile.VatRegime vatRegime, Boolean corporateTax,
                                 Integer fiscalYearEndMonth, Boolean hasEmployees, Boolean active) {}

    public record Deadline(UUID id, UUID profileId, UUID partnerId, String partnerName, TaxDeadline.Kind kind,
                           String periodLabel, LocalDate periodStart, LocalDate periodEnd, LocalDate dueDate,
                           TaxDeadline.Status status, Instant doneAt, BigDecimal amount, String note, boolean overdue) {}

    public record DeadlineUpdate(TaxDeadline.Status status, BigDecimal amount, String note) {}
}
