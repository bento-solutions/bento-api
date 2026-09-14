package com.bento.crm.proposal.controller;

import com.bento.crm.common.dto.PageResponse;
import com.bento.crm.proposal.dto.CreateProposalRequest;
import com.bento.crm.proposal.dto.ProposalResponse;
import com.bento.crm.proposal.model.Proposal;
import com.bento.crm.proposal.service.ProposalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import com.bento.crm.common.pdf.PdfDocumentService;
import com.bento.crm.organization.model.Organization;
import com.bento.crm.organization.repository.OrganizationRepository;
import com.bento.crm.partner.model.Partner;
import com.bento.crm.partner.repository.PartnerRepository;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/proposals")
@Tag(name = "Proposals", description = "Proposal management endpoints")
public class ProposalController {

    private final ProposalService proposalService;
    private final PdfDocumentService pdfDocumentService;
    private final PartnerRepository partnerRepository;
    private final OrganizationRepository organizationRepository;

    public ProposalController(ProposalService proposalService,
                              PdfDocumentService pdfDocumentService,
                              PartnerRepository partnerRepository,
                              OrganizationRepository organizationRepository) {
        this.proposalService = proposalService;
        this.pdfDocumentService = pdfDocumentService;
        this.partnerRepository = partnerRepository;
        this.organizationRepository = organizationRepository;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PROPOSALS_CREATE')")
    @Operation(summary = "Create proposal", description = "Create a new proposal")
    public ResponseEntity<ProposalResponse> createProposal(@Valid @RequestBody CreateProposalRequest request) {
        Proposal created = proposalService.createProposal(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ProposalResponse.fromEntity(created));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PROPOSALS_READ')")
    @Operation(summary = "Get proposal by ID", description = "Retrieve proposal details")
    public ResponseEntity<ProposalResponse> getProposal(@PathVariable UUID id) {
        Proposal proposal = proposalService.getProposal(id);
        return ResponseEntity.ok(ProposalResponse.fromEntity(proposal));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PROPOSALS_READ')")
    @Operation(summary = "List proposals", description = "List all proposals in the organization")
    public ResponseEntity<PageResponse<ProposalResponse>> listProposals(Pageable pageable) {
        Page<Proposal> page = proposalService.listProposals(pageable);
        Page<ProposalResponse> dtoPage = page.map(ProposalResponse::fromEntity);
        return ResponseEntity.ok(PageResponse.fromPage(dtoPage));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('PROPOSALS_WRITE')")
    @Operation(summary = "Update proposal", description = "Update proposal information")
    public ResponseEntity<ProposalResponse> updateProposal(@PathVariable UUID id, @Valid @RequestBody CreateProposalRequest request) {
        Proposal proposal = proposalService.updateProposal(id, request);
        return ResponseEntity.ok(ProposalResponse.fromEntity(proposal));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('PROPOSALS_DELETE')")
    @Operation(summary = "Delete proposal", description = "Soft delete proposal record")
    public ResponseEntity<Void> deleteProposal(@PathVariable UUID id) {
        proposalService.deleteProposal(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/restore")
    @PreAuthorize("hasAuthority('PROPOSALS_DELETE')")
    @Operation(summary = "Restore proposal", description = "Undo a soft delete on a proposal")
    public ResponseEntity<ProposalResponse> restoreProposal(@PathVariable UUID id) {
        return ResponseEntity.ok(ProposalResponse.fromEntity(proposalService.restoreProposal(id)));
    }

    @GetMapping("/deleted")
    @PreAuthorize("hasAuthority('PROPOSALS_DELETE')")
    @Operation(summary = "List deleted proposals", description = "Soft-deleted proposals still inside the retention window")
    public ResponseEntity<PageResponse<ProposalResponse>> listDeleted(Pageable pageable) {
        Page<ProposalResponse> page = proposalService.listDeleted(pageable).map(ProposalResponse::fromEntity);
        return ResponseEntity.ok(PageResponse.fromPage(page));
    }

    @GetMapping(value = "/{id}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize("hasAuthority('PROPOSALS_READ')")
    @Operation(summary = "Download proposal PDF", description = "Generates a PDF document for a proposal")
    public ResponseEntity<byte[]> getProposalPdf(@PathVariable UUID id) {
        Proposal proposal = proposalService.getProposal(id);
        Partner partner = null;
        if (proposal.getPartnerId() != null) {
            partner = partnerRepository.findByOrganizationIdAndId(proposal.getOrganizationId(), proposal.getPartnerId()).orElse(null);
        }
        Organization org = organizationRepository.findById(proposal.getOrganizationId()).orElse(null);
        byte[] pdf = pdfDocumentService.generateProposalPdf(proposal, partner, org);

        String filename = "proposition-" + id.toString().substring(0, 8) + ".pdf";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }
}
