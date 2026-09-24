package com.bento.crm.campaign.service;

import com.bento.crm.campaign.dto.CampaignRecipientResponse;
import com.bento.crm.campaign.dto.CampaignStatsResponse;
import com.bento.crm.campaign.model.Campaign;
import com.bento.crm.campaign.model.CampaignRecipient;
import com.bento.crm.campaign.repository.CampaignRecipientRepository;
import com.bento.crm.campaign.repository.CampaignRepository;
import com.bento.crm.common.exception.ResourceNotFoundException;
import com.bento.crm.partner.model.Partner;
import com.bento.crm.partner.repository.PartnerRepository;
import com.bento.crm.whatsapp.model.WaFollowup;
import com.bento.crm.whatsapp.repository.WaFollowupRepository;
import com.bento.crm.whatsapp.util.PhoneNumbers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Owns a campaign's audience: who is enrolled, how each one is reachable on the campaign's
 * channel, and (for WhatsApp, the only channel with a real send path today) how each delivery
 * is progressing.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CampaignRecipientService {

    private final CampaignRepository campaignRepository;
    private final CampaignRecipientRepository recipientRepository;
    private final WaFollowupRepository followupRepository;
    private final PartnerRepository partnerRepository;

    /**
     * Enrols partners as recipients of a campaign, resolving each one's contact detail for the
     * campaign's own channel (phone for WhatsApp/SMS, email for Email). A partner missing that
     * detail is still recorded — as SKIPPED — rather than silently dropped: the agent selected
     * N contacts and should see all N accounted for, not wonder why the count is short.
     * Partners already enrolled are left as they are and returned unchanged (re-selecting them
     * is a no-op, not a duplicate row — the table has a unique constraint on campaign_id +
     * partner_id). They are checked up front rather than by catching the constraint violation:
     * a failed flush marks the surrounding transaction rollback-only, so the whole request
     * would fail on commit.
     */
    @Transactional
    public List<CampaignRecipient> enroll(UUID orgId, UUID campaignId, List<UUID> partnerIds) {
        Campaign campaign = requireCampaign(orgId, campaignId);
        Map<UUID, CampaignRecipient> existing = recipientRepository.findAllByCampaign(orgId, campaignId).stream()
                .collect(Collectors.toMap(CampaignRecipient::getPartnerId, r -> r, (a, b) -> a));
        List<CampaignRecipient> enrolled = new ArrayList<>();

        for (UUID partnerId : new LinkedHashSet<>(partnerIds)) {
            CampaignRecipient already = existing.get(partnerId);
            if (already != null) {
                enrolled.add(already);
                continue;
            }
            Partner partner = partnerRepository.findByOrganizationIdAndId(orgId, partnerId).orElse(null);
            if (partner == null) {
                log.warn("[campaign] partner {} not found in org {}, skipping", partnerId, orgId);
                continue;
            }

            CampaignRecipient recipient = new CampaignRecipient();
            recipient.setOrganizationId(orgId);
            recipient.setCampaignId(campaign.getId());
            recipient.setPartnerId(partnerId);
            recipient.setFollowupCount(0);
            resolveContact(campaign.getChannel(), partner, recipient);
            enrolled.add(recipientRepository.save(recipient));
        }
        return enrolled;
    }

    /** Fills in the recipient's reachable contact detail, or marks it SKIPPED when there is none. */
    private void resolveContact(Campaign.Channel channel, Partner partner, CampaignRecipient recipient) {
        if (channel == Campaign.Channel.EMAIL) {
            String email = partner.getEmail() != null ? partner.getEmail().trim() : null;
            if (email == null || email.isBlank()) {
                recipient.setStatus(CampaignRecipient.Status.SKIPPED);
                recipient.setErrorCode("NO_EMAIL");
                recipient.setErrorTitle("No email address on this contact");
            } else {
                recipient.setEmail(email);
                recipient.setStatus(CampaignRecipient.Status.PENDING);
            }
            return;
        }

        // WhatsApp and SMS both reach a contact by phone.
        String phone = PhoneNumbers.toE164(partner.getPhone()).orElse(null);
        if (phone == null) {
            recipient.setStatus(CampaignRecipient.Status.SKIPPED);
            recipient.setErrorCode("NO_PHONE");
            recipient.setErrorTitle("No usable phone number on this contact");
        } else {
            recipient.setPhoneE164(phone);
            recipient.setStatus(CampaignRecipient.Status.PENDING);
        }
    }

    /** Drops one recipient — e.g. a contact added by mistake, before the campaign has sent. */
    @Transactional
    public void remove(UUID orgId, UUID campaignId, UUID recipientId) {
        CampaignRecipient recipient = recipientRepository.findByOrganizationIdAndId(orgId, recipientId)
                .filter(r -> r.getCampaignId().equals(campaignId))
                .orElseThrow(() -> new ResourceNotFoundException("Recipient not found"));
        recipientRepository.delete(recipient);
    }

    private Campaign requireCampaign(UUID orgId, UUID campaignId) {
        return campaignRepository.findByOrganizationIdAndId(orgId, campaignId)
                .orElseThrow(() -> new ResourceNotFoundException("Campaign not found"));
    }

    @Transactional(readOnly = true)
    public List<CampaignRecipientResponse> listRecipients(UUID orgId, UUID campaignId) {
        requireCampaign(orgId, campaignId);
        List<CampaignRecipient> recipients = recipientRepository.findAllByCampaign(orgId, campaignId);

        // Two bulk lookups rather than a query per row: a 500-contact campaign would
        // otherwise issue 1000 queries to render one table.
        Set<UUID> partnerIds = recipients.stream()
                .map(CampaignRecipient::getPartnerId)
                .collect(Collectors.toSet());

        Map<UUID, String> names = partnerIds.isEmpty()
                ? Map.of()
                : partnerRepository.findAllById(partnerIds).stream()
                        .collect(Collectors.toMap(Partner::getId, Partner::getName, (a, b) -> a));

        Map<UUID, Instant> dueDates = new HashMap<>();
        for (Object[] row : followupRepository.findDueDatesForCampaign(orgId, campaignId, WaFollowup.State.PENDING)) {
            dueDates.put((UUID) row[0], (Instant) row[1]);
        }

        return recipients.stream()
                .map(r -> CampaignRecipientResponse.fromEntity(
                        r,
                        names.getOrDefault(r.getPartnerId(), "Unknown contact"),
                        dueDates.get(r.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public CampaignStatsResponse stats(UUID orgId, UUID campaignId) {
        requireCampaign(orgId, campaignId);
        Map<CampaignRecipient.Status, Long> counts = new EnumMap<>(CampaignRecipient.Status.class);
        for (Object[] row : recipientRepository.countByStatusForCampaign(orgId, campaignId)) {
            counts.put((CampaignRecipient.Status) row[0], (Long) row[1]);
        }

        return CampaignStatsResponse.builder()
                .total(counts.values().stream().mapToLong(Long::longValue).sum())
                .pending(counts.getOrDefault(CampaignRecipient.Status.PENDING, 0L))
                .sent(counts.getOrDefault(CampaignRecipient.Status.SENT, 0L))
                .delivered(counts.getOrDefault(CampaignRecipient.Status.DELIVERED, 0L))
                .read(counts.getOrDefault(CampaignRecipient.Status.READ, 0L))
                .replied(counts.getOrDefault(CampaignRecipient.Status.REPLIED, 0L))
                .failed(counts.getOrDefault(CampaignRecipient.Status.FAILED, 0L))
                .skipped(counts.getOrDefault(CampaignRecipient.Status.SKIPPED, 0L))
                .optedOut(counts.getOrDefault(CampaignRecipient.Status.OPTED_OUT, 0L))
                .followupsPending(followupRepository.countForCampaignInState(
                        orgId, campaignId, WaFollowup.State.PENDING))
                .followupsSent(followupRepository.countForCampaignInState(
                        orgId, campaignId, WaFollowup.State.SENT))
                .build();
    }
}
