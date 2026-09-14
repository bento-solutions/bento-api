package com.bento.crm.purchaseorder.service;

import com.bento.crm.common.context.TenantContext;
import com.bento.crm.common.exception.ResourceNotFoundException;
import com.bento.crm.common.service.BusinessNumberService;
import com.bento.crm.purchaseorder.dto.CreatePurchaseOrderRequest;
import com.bento.crm.purchaseorder.model.PurchaseOrder;
import com.bento.crm.purchaseorder.repository.PurchaseOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PurchaseOrderService {

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final BusinessNumberService businessNumberService;

    static final BigDecimal DEFAULT_VAT_RATE = new BigDecimal("0.20");

    @Transactional
    public PurchaseOrder createPurchaseOrder(CreatePurchaseOrderRequest request) {
        PurchaseOrder po = new PurchaseOrder();
        po.setOrganizationId(TenantContext.getCurrentOrganizationId());
        applyRequest(po, request);
        return purchaseOrderRepository.save(po);
    }

    public PurchaseOrder getPurchaseOrder(UUID id) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        return purchaseOrderRepository.findByOrganizationIdAndId(orgId, id)
                .orElseThrow(() -> new ResourceNotFoundException("Purchase Order not found"));
    }

    public Page<PurchaseOrder> listPurchaseOrders(Pageable pageable) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        return purchaseOrderRepository.findByOrganizationId(orgId, pageable);
    }

    @Transactional
    public PurchaseOrder updatePurchaseOrder(UUID id, CreatePurchaseOrderRequest request) {
        PurchaseOrder po = getPurchaseOrder(id);
        applyRequest(po, request);
        return purchaseOrderRepository.save(po);
    }

    private void applyRequest(PurchaseOrder po, CreatePurchaseOrderRequest request) {
        po.setDealId(request.getDealId());
        po.setVendorPartnerId(request.getVendorPartnerId());
        po.setStatus(request.getStatus());
        po.setOrderNumber(request.getOrderNumber());
        po.setOrderDate(request.getOrderDate());
        po.setDeliveryDate(request.getDeliveryDate());
        po.setSentVia(request.getSentVia());
        po.setNotes(request.getNotes());
        po.setLines(request.getLines() != null ? request.getLines() : new ArrayList<>());
        po.setSubtotal(request.getSubtotal());
        po.setTax(request.getTax());
        po.setTotal(request.getTotal());

        fillDerivedFields(po);
    }

    private void fillDerivedFields(PurchaseOrder po) {
        if (po.getOrderNumber() == null || po.getOrderNumber().isBlank()) {
            po.setOrderNumber(businessNumberService.next(po.getOrganizationId(), "BC"));
        }
        if (po.getOrderDate() == null) {
            po.setOrderDate(LocalDate.now());
        }

        BigDecimal fromLines = sumOfLines(po.getLines());
        if (po.getSubtotal() == null) {
            po.setSubtotal(fromLines != null ? fromLines : po.getTotal());
        }
        if (po.getSubtotal() != null) {
            if (po.getTax() == null) {
                po.setTax(po.getSubtotal().multiply(DEFAULT_VAT_RATE).setScale(2, RoundingMode.HALF_UP));
            }
            if (po.getTotal() == null || fromLines != null || po.getTotal().compareTo(po.getSubtotal()) == 0) {
                po.setTotal(po.getSubtotal().add(po.getTax()));
            }
        }
    }

    private static BigDecimal sumOfLines(List<Map<String, Object>> lines) {
        if (lines == null || lines.isEmpty()) {
            return null;
        }
        BigDecimal sum = BigDecimal.ZERO;
        boolean any = false;
        for (Map<String, Object> line : lines) {
            BigDecimal qty = toDecimal(line.get("qty"));
            BigDecimal unitPrice = toDecimal(line.get("unitPrice"));
            if (unitPrice == null) {
                unitPrice = toDecimal(line.get("unit_price"));
            }
            if (qty == null || unitPrice == null) {
                continue;
            }
            sum = sum.add(qty.multiply(unitPrice));
            any = true;
        }
        return any ? sum.setScale(2, RoundingMode.HALF_UP) : null;
    }

    private static BigDecimal toDecimal(Object value) {
        if (value == null) return null;
        if (value instanceof BigDecimal d) return d;
        if (value instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Transactional
    public void deletePurchaseOrder(UUID id) {
        PurchaseOrder po = getPurchaseOrder(id);
        po.setDeletedAt(Instant.now());
        purchaseOrderRepository.save(po);
    }

    @Transactional
    public PurchaseOrder restorePurchaseOrder(UUID id) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        PurchaseOrder po = purchaseOrderRepository.findByOrganizationIdAndIdIncludingDeleted(orgId, id)
                .orElseThrow(() -> new ResourceNotFoundException("Purchase Order not found"));
        po.setDeletedAt(null);
        return purchaseOrderRepository.save(po);
    }

    public Page<PurchaseOrder> listDeleted(Pageable pageable) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        return purchaseOrderRepository.findDeletedByOrganizationId(orgId, pageable);
    }
}
