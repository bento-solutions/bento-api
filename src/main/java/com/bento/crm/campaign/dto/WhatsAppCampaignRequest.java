package com.bento.crm.campaign.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/**
 * Creating a WhatsApp campaign from the /marketing composer.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WhatsAppCampaignRequest {

    @NotBlank
    private String title;

    /**
     * Name of the approved template, as it appears in Meta Business Manager. Required for Meta
     * (checked at launch); a linked personal number sends {@link #bodyPreview} as plain text.
     */
    private String templateName;

    private String templateLang;

    /** Positional values for the template's {{1}}, {{2}}, … placeholders. */
    private List<String> templateParams;

    /**
     * Meta: rendered text shown in the CRM timeline, not sent. Linked personal number: the
     * message itself.
     */
    private String bodyPreview;

    @NotEmpty
    private List<UUID> partnerIds;

    private boolean followupEnabled;

    @Positive
    private Integer followupDelayDays;

    private String followupTemplateName;

    /** Relance text for a linked personal number. */
    private String followupBody;

    /**
     * Test-only override that schedules the relance in minutes instead of days.
     * Exposing it on the request rather than hardcoding the delay is the difference
     * between a three-day test cycle and a three-minute one.
     */
    @Positive
    private Integer followupDelayMinutes;

    /** Send immediately on create instead of leaving the campaign in DRAFT. */
    private boolean launchNow;
}
