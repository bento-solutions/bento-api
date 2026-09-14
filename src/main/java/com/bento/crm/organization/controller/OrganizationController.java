package com.bento.crm.organization.controller;

import com.bento.crm.organization.dto.CreateOrganizationRequest;
import com.bento.crm.organization.dto.OrganizationResponse;
import com.bento.crm.organization.dto.UpdateOrganizationRequest;
import com.bento.crm.organization.model.Organization;
import com.bento.crm.organization.service.OrganizationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/organizations")
@RequiredArgsConstructor
@Tag(name = "Organizations", description = "Organization management endpoints")
public class OrganizationController {

    private final OrganizationService organizationService;

    @PostMapping
    @Operation(summary = "Create organization", description = "Create new organization (signup)")
    public ResponseEntity<OrganizationResponse> createOrganization(@Valid @RequestBody CreateOrganizationRequest request) {
        Organization organization = organizationService.createOrganization(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(OrganizationResponse.fromEntity(organization));
    }

    @GetMapping("/me")
    @Operation(summary = "Get current organization", description = "Retrieve the organization for the authenticated caller's tenant")
    public ResponseEntity<OrganizationResponse> getCurrentOrganization() {
        Organization organization = organizationService.getCurrentOrganization();
        return ResponseEntity.ok(OrganizationResponse.fromEntity(organization));
    }

    @PatchMapping("/me")
    @PreAuthorize("hasAuthority('ADMIN_ACCESS')")
    @Operation(summary = "Update current organization", description = "Update organization profile fields for the authenticated caller's tenant")
    public ResponseEntity<OrganizationResponse> updateCurrentOrganization(@Valid @RequestBody UpdateOrganizationRequest request) {
        Organization organization = organizationService.updateCurrentOrganization(request);
        return ResponseEntity.ok(OrganizationResponse.fromEntity(organization));
    }

    @PostMapping(value = "/me/logo", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('ADMIN_ACCESS')")
    @Operation(summary = "Upload organization logo", description = "Upload a logo image for the current organization")
    public ResponseEntity<OrganizationResponse> uploadLogo(@RequestParam("file") org.springframework.web.multipart.MultipartFile file) {
        Organization organization = organizationService.uploadLogo(file);
        return ResponseEntity.ok(OrganizationResponse.fromEntity(organization));
    }
}
