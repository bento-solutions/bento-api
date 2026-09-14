package com.bento.crm.deal.service;

import com.bento.crm.common.context.TenantContext;
import com.bento.crm.common.exception.ResourceNotFoundException;
import com.bento.crm.deal.dto.CreateDealRequest;
import com.bento.crm.deal.model.Deal;
import com.bento.crm.deal.model.DealOrderLine;
import com.bento.crm.deal.repository.DealRepository;
import com.bento.crm.deal.repository.DealSpecification;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DealService {

    private final DealRepository dealRepository;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;

    @Transactional
    public Deal createDeal(CreateDealRequest request) {
        Deal deal = new Deal();
        deal.setOrganizationId(TenantContext.getCurrentOrganizationId());
        applyRequest(deal, request);
        Deal saved = dealRepository.save(deal);
        eventPublisher.publishEvent(com.bento.crm.automation.event.EntityChangedEvent.builder()
                .organizationId(saved.getOrganizationId())
                .trigger(com.bento.crm.automation.model.AutomationRule.Trigger.DEAL_CREATED)
                .entityType("DEAL")
                .entityId(saved.getId())
                .payload(dealToPayload(saved))
                .build());
        return saved;
    }

    public Deal getDeal(UUID id) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        return dealRepository.findByOrganizationIdAndId(orgId, id)
                .orElseThrow(() -> new ResourceNotFoundException("Deal not found"));
    }

    public Page<Deal> listDeals(Pageable pageable) {
        return listDeals(null, null, null, null, pageable);
    }

    public Page<Deal> listDeals(String q, Deal.DealStage stage, UUID partnerId, UUID salesPersonUserId, Pageable pageable) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        if ((q == null || q.isBlank()) && stage == null && partnerId == null && salesPersonUserId == null) {
            return dealRepository.findByOrganizationId(orgId, pageable);
        }
        return dealRepository.findAll(DealSpecification.filter(orgId, q, stage, partnerId, salesPersonUserId), pageable);
    }

    @Transactional
    public Deal updateDeal(UUID id, CreateDealRequest request) {
        Deal deal = getDeal(id);
        applyRequest(deal, request);
        Deal saved = dealRepository.save(deal);
        eventPublisher.publishEvent(com.bento.crm.automation.event.EntityChangedEvent.builder()
                .organizationId(saved.getOrganizationId())
                .trigger(com.bento.crm.automation.model.AutomationRule.Trigger.DEAL_UPDATED)
                .entityType("DEAL")
                .entityId(saved.getId())
                .payload(dealToPayload(saved))
                .build());
        return saved;
    }

    private java.util.Map<String, Object> dealToPayload(Deal d) {
        java.util.Map<String, Object> map = new java.util.HashMap<>();
        map.put("id", d.getId());
        map.put("title", d.getTitle());
        map.put("stage", d.getStage() != null ? d.getStage().name() : null);
        map.put("amount", d.getAmount());
        map.put("partnerId", d.getPartnerId());
        map.put("salesPersonUserId", d.getSalesPersonUserId());
        return map;
    }

    private void applyRequest(Deal deal, CreateDealRequest request) {
        if (request.getOrderLines() != null) {
            deal.replaceOrderLines(request.getOrderLines().stream()
                    .map(l -> DealOrderLine.builder()
                            .product(l.getProduct())
                            .description(l.getDescription())
                            .qty(l.getQty())
                            .unitPrice(l.getUnitPrice())
                            .discount(l.getDiscount())
                            .total(l.effectiveTotal())
                            .vendor(l.getVendor())
                            .build())
                    .toList());
        }
        deal.setPartnerId(request.getPartnerId());
        deal.setProposalId(request.getProposalId());
        deal.setTitle(request.getTitle());
        deal.setStage(request.getStage());
        deal.setAmount(request.getAmount());
        deal.setDiscount(request.getDiscount());
        deal.setComments(request.getComments());
        deal.setOrderNumber(request.getOrderNumber());
        deal.setOrderDate(request.getOrderDate());
        deal.setRequestedDeliveryDate(request.getRequestedDeliveryDate());
        deal.setEstimatedDeliveryDate(request.getEstimatedDeliveryDate());
        deal.setExpectedDeliveryDateVendor(request.getExpectedDeliveryDateVendor());
        deal.setDeliveryDate(request.getDeliveryDate());
        deal.setCustomerAccount(request.getCustomerAccount());
        deal.setBillingAddress(request.getBillingAddress());
        deal.setDeliveryAddress(request.getDeliveryAddress());
        deal.setContactPerson(request.getContactPerson());
        deal.setContactEmail(request.getContactEmail());
        deal.setContactPhone(request.getContactPhone());
        deal.setSalesPersonUserId(request.getSalesPersonUserId());
        deal.setSalesRegion(request.getSalesRegion());
        deal.setCurrency(request.getCurrency());
        deal.setPaymentTerms(request.getPaymentTerms());
        deal.setOrderTotalAmount(request.getOrderTotalAmount());
        deal.setVendorAccount(request.getVendorAccount());
        deal.setPurchaseOrderRef(request.getPurchaseOrderRef());
        deal.setWarehouseAddress(request.getWarehouseAddress());
        deal.setTransportationService(request.getTransportationService());
    }

    @Transactional
    public void deleteDeal(UUID id) {
        Deal deal = getDeal(id);
        deal.setDeletedAt(java.time.Instant.now());
        dealRepository.save(deal);
    }

    @Transactional
    public Deal restoreDeal(UUID id) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        Deal deal = dealRepository.findByOrganizationIdAndIdIncludingDeleted(orgId, id)
                .orElseThrow(() -> new ResourceNotFoundException("Deal not found"));
        deal.setDeletedAt(null);
        return dealRepository.save(deal);
    }

    public Page<Deal> listDeleted(Pageable pageable) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        return dealRepository.findDeletedByOrganizationId(orgId, pageable);
    }
}
