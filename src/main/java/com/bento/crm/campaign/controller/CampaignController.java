package com.bento.crm.campaign.controller;

import com.bento.crm.common.dto.PageResponse;
import com.bento.crm.campaign.dto.CreateCampaignRequest;
import com.bento.crm.campaign.dto.CampaignResponse;
import com.bento.crm.campaign.model.Campaign;
import com.bento.crm.campaign.service.CampaignService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/campaigns")
@Tag(name = "Campaigns", description = "Campaign management endpoints")
public class CampaignController {

    private final CampaignService campaignService;

    public CampaignController(CampaignService campaignService) {
        this.campaignService = campaignService;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('CAMPAIGNS_CREATE')")
    @Operation(summary = "Create campaign", description = "Create a new campaign")
    public ResponseEntity<CampaignResponse> createCampaign(@Valid @RequestBody CreateCampaignRequest request) {
        Campaign created = campaignService.createCampaign(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(CampaignResponse.fromEntity(created));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('CAMPAIGNS_READ')")
    @Operation(summary = "Get campaign by ID", description = "Retrieve campaign details")
    public ResponseEntity<CampaignResponse> getCampaign(@PathVariable UUID id) {
        Campaign campaign = campaignService.getCampaign(id);
        return ResponseEntity.ok(CampaignResponse.fromEntity(campaign));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('CAMPAIGNS_READ')")
    @Operation(summary = "List campaigns", description = "List all campaigns in the organization")
    public ResponseEntity<PageResponse<CampaignResponse>> listCampaigns(Pageable pageable) {
        Page<Campaign> page = campaignService.listCampaigns(pageable);
        Page<CampaignResponse> dtoPage = page.map(CampaignResponse::fromEntity);
        return ResponseEntity.ok(PageResponse.fromPage(dtoPage));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('CAMPAIGNS_WRITE')")
    @Operation(summary = "Update campaign", description = "Update campaign information")
    public ResponseEntity<CampaignResponse> updateCampaign(@PathVariable UUID id, @Valid @RequestBody CreateCampaignRequest request) {
        Campaign campaign = campaignService.updateCampaign(id, request);
        return ResponseEntity.ok(CampaignResponse.fromEntity(campaign));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('CAMPAIGNS_DELETE')")
    @Operation(summary = "Delete campaign", description = "Soft delete campaign record")
    public ResponseEntity<Void> deleteCampaign(@PathVariable UUID id) {
        campaignService.deleteCampaign(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/restore")
    @PreAuthorize("hasAuthority('CAMPAIGNS_DELETE')")
    @Operation(summary = "Restore campaign", description = "Undo a soft delete on a campaign")
    public ResponseEntity<CampaignResponse> restoreCampaign(@PathVariable UUID id) {
        return ResponseEntity.ok(CampaignResponse.fromEntity(campaignService.restoreCampaign(id)));
    }

    @GetMapping("/deleted")
    @PreAuthorize("hasAuthority('CAMPAIGNS_DELETE')")
    @Operation(summary = "List deleted campaigns", description = "Soft-deleted campaigns still inside the retention window")
    public ResponseEntity<PageResponse<CampaignResponse>> listDeleted(Pageable pageable) {
        Page<CampaignResponse> page = campaignService.listDeleted(pageable).map(CampaignResponse::fromEntity);
        return ResponseEntity.ok(PageResponse.fromPage(page));
    }
}
