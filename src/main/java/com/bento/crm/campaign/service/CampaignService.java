package com.bento.crm.campaign.service;

import com.bento.crm.common.context.TenantContext;
import com.bento.crm.common.exception.ResourceNotFoundException;
import com.bento.crm.campaign.dto.CreateCampaignRequest;
import com.bento.crm.campaign.model.Campaign;
import com.bento.crm.campaign.repository.CampaignRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CampaignService {

    private final CampaignRepository campaignRepository;

    @Transactional
    public Campaign createCampaign(CreateCampaignRequest request) {
        Campaign campaign = new Campaign();
        applyRequest(campaign, request);
        campaign.setOrganizationId(TenantContext.getCurrentOrganizationId());
        return campaignRepository.save(campaign);
    }

    public Campaign getCampaign(UUID id) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        return campaignRepository.findByOrganizationIdAndId(orgId, id)
                .orElseThrow(() -> new ResourceNotFoundException("Campaign not found"));
    }

    public Page<Campaign> listCampaigns(Pageable pageable) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        return campaignRepository.findByOrganizationId(orgId, pageable);
    }

    @Transactional
    public Campaign updateCampaign(UUID id, CreateCampaignRequest request) {
        Campaign campaign = getCampaign(id);
        applyRequest(campaign, request);
        return campaignRepository.save(campaign);
    }

    private void applyRequest(Campaign campaign, CreateCampaignRequest request) {
        campaign.setTitle(request.getTitle());
        campaign.setChannel(request.getChannel());
        campaign.setStatus(request.getStatus());
        campaign.setTemplateId(request.getTemplateId());
        campaign.setTargetTagId(request.getTargetTagId());
        campaign.setTargetFilter(request.getTargetFilter());
        campaign.setScheduledAt(request.getScheduledAt());
        campaign.setSentCount(request.getSentCount());
        campaign.setMetrics(request.getMetrics());
    }

    @Transactional
    public void deleteCampaign(UUID id) {
        Campaign campaign = getCampaign(id);
        campaign.setDeletedAt(java.time.Instant.now());
        campaignRepository.save(campaign);
    }

    @Transactional
    public Campaign restoreCampaign(UUID id) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        Campaign campaign = campaignRepository.findByOrganizationIdAndIdIncludingDeleted(orgId, id)
                .orElseThrow(() -> new ResourceNotFoundException("Campaign not found"));
        campaign.setDeletedAt(null);
        return campaignRepository.save(campaign);
    }

    public Page<Campaign> listDeleted(Pageable pageable) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        return campaignRepository.findDeletedByOrganizationId(orgId, pageable);
    }
}
