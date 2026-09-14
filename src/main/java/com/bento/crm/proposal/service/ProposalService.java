package com.bento.crm.proposal.service;

import com.bento.crm.common.context.TenantContext;
import com.bento.crm.common.exception.ResourceNotFoundException;
import com.bento.crm.proposal.dto.CreateProposalRequest;
import com.bento.crm.proposal.model.Proposal;
import com.bento.crm.proposal.repository.ProposalRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProposalService {

    private final ProposalRepository proposalRepository;

    @Transactional
    public Proposal createProposal(CreateProposalRequest request) {
        Proposal proposal = new Proposal();
        applyRequest(proposal, request);
        proposal.setOrganizationId(TenantContext.getCurrentOrganizationId());
        return proposalRepository.save(proposal);
    }

    public Proposal getProposal(UUID id) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        return proposalRepository.findByOrganizationIdAndId(orgId, id)
                .orElseThrow(() -> new ResourceNotFoundException("Proposal not found"));
    }

    public Page<Proposal> listProposals(Pageable pageable) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        return proposalRepository.findByOrganizationId(orgId, pageable);
    }

    @Transactional
    public Proposal updateProposal(UUID id, CreateProposalRequest request) {
        Proposal proposal = getProposal(id);
        applyRequest(proposal, request);
        return proposalRepository.save(proposal);
    }

    private void applyRequest(Proposal proposal, CreateProposalRequest request) {
        proposal.setPartnerId(request.getPartnerId());
        proposal.setTemplateId(request.getTemplateId());
        proposal.setTitle(request.getTitle());
        proposal.setStatus(request.getStatus());
        proposal.setDeliveryMethod(request.getDeliveryMethod());
        proposal.setOpportunityValue(request.getOpportunityValue());
        proposal.setClosingProbability(request.getClosingProbability());
        proposal.setExpectedClosingDate(request.getExpectedClosingDate());
        proposal.setCompetitors(request.getCompetitors());
        proposal.setConfirmationMethod(request.getConfirmationMethod());
        proposal.setConfirmationAttachmentFileId(request.getConfirmationAttachmentFileId());
        proposal.setConfirmationNote(request.getConfirmationNote());
        proposal.setConfirmedAt(request.getConfirmedAt());
        proposal.setSentAt(request.getSentAt());
    }

    @Transactional
    public void deleteProposal(UUID id) {
        Proposal proposal = getProposal(id);
        proposal.setDeletedAt(java.time.Instant.now());
        proposalRepository.save(proposal);
    }

    @Transactional
    public Proposal restoreProposal(UUID id) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        Proposal proposal = proposalRepository.findByOrganizationIdAndIdIncludingDeleted(orgId, id)
                .orElseThrow(() -> new ResourceNotFoundException("Proposal not found"));
        proposal.setDeletedAt(null);
        return proposalRepository.save(proposal);
    }

    public Page<Proposal> listDeleted(Pageable pageable) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        return proposalRepository.findDeletedByOrganizationId(orgId, pageable);
    }
}
