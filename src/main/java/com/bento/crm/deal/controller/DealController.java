package com.bento.crm.deal.controller;

import com.bento.crm.common.dto.PageResponse;
import com.bento.crm.deal.dto.CreateDealRequest;
import com.bento.crm.deal.dto.DealResponse;
import com.bento.crm.deal.model.Deal;
import com.bento.crm.deal.service.DealService;
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
@RequestMapping("/deals")
@Tag(name = "Deals", description = "Deal management endpoints")
public class DealController {

    private final DealService dealService;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public DealController(DealService dealService, org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.dealService = dealService;
        this.jdbc = jdbc;
    }

    /** Fills advanceTotal and advancePaid from the deals' issued advance invoices. */
    private <C extends java.util.Collection<DealResponse>> C withAdvances(C deals) {
        if (deals.isEmpty()) return deals;
        java.util.Map<UUID, Object[]> byDeal = new java.util.HashMap<>();
        java.util.List<UUID> ids = deals.stream().map(DealResponse::getId).toList();
        UUID orgId = deals.iterator().next().getOrganizationId();
        jdbc.query("SELECT deal_id, COALESCE(SUM(total), 0), bool_and(status = 'PAID') FROM invoice"
                + " WHERE organization_id = ? AND deal_id = ANY(?) AND kind = 'ADVANCE' AND deleted_at IS NULL"
                + " AND status NOT IN ('DRAFT', 'CREDITED') GROUP BY deal_id", rs -> {
            byDeal.put(rs.getObject(1, UUID.class), new Object[]{rs.getBigDecimal(2), rs.getBoolean(3)});
        }, orgId, ids.toArray(new UUID[0]));
        java.util.Map<UUID, java.util.List<UUID>> missions = new java.util.HashMap<>();
        jdbc.query("SELECT deal_id, id FROM mission WHERE organization_id = ? AND deal_id = ANY(?) AND deleted_at IS NULL"
                + " ORDER BY created_at", rs -> {
            missions.computeIfAbsent(rs.getObject(1, UUID.class), k -> new java.util.ArrayList<>()).add(rs.getObject(2, UUID.class));
        }, orgId, ids.toArray(new UUID[0]));
        for (DealResponse d : deals) {
            d.setMissionIds(missions.getOrDefault(d.getId(), new java.util.ArrayList<>()));
            Object[] a = byDeal.get(d.getId());
            d.setAdvanceTotal(a == null ? java.math.BigDecimal.ZERO.setScale(2) : (java.math.BigDecimal) a[0]);
            d.setAdvancePaid(a != null && (Boolean) a[1]);
        }
        return deals;
    }

    private DealResponse withAdvances(DealResponse d) {
        withAdvances(java.util.List.of(d));
        return d;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('DEALS_CREATE')")
    @Operation(summary = "Create deal", description = "Create a new deal")
    public ResponseEntity<DealResponse> createDeal(@Valid @RequestBody CreateDealRequest request) {
        Deal created = dealService.createDeal(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(DealResponse.fromEntity(created));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('DEALS_READ')")
    @Operation(summary = "Get deal by ID", description = "Retrieve deal details")
    public ResponseEntity<DealResponse> getDeal(@PathVariable UUID id) {
        Deal deal = dealService.getDeal(id);
        return ResponseEntity.ok(withAdvances(DealResponse.fromEntity(deal)));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('DEALS_READ')")
    @Operation(summary = "List deals", description = "List all deals with optional search and multi-criteria filters")
    public ResponseEntity<PageResponse<DealResponse>> listDeals(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) com.bento.crm.deal.model.Deal.DealStage stage,
            @RequestParam(required = false) UUID partnerId,
            @RequestParam(required = false) UUID salesPersonUserId,
            Pageable pageable) {
        Page<Deal> page = dealService.listDeals(q, stage, partnerId, salesPersonUserId, pageable);
        Page<DealResponse> dtoPage = page.map(DealResponse::fromEntity);
        withAdvances(dtoPage.getContent());
        return ResponseEntity.ok(PageResponse.fromPage(dtoPage));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('DEALS_WRITE')")
    @Operation(summary = "Update deal", description = "Update deal information")
    public ResponseEntity<DealResponse> updateDeal(@PathVariable UUID id, @Valid @RequestBody CreateDealRequest request) {
        Deal deal = dealService.updateDeal(id, request);
        return ResponseEntity.ok(withAdvances(DealResponse.fromEntity(deal)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('DEALS_DELETE')")
    @Operation(summary = "Delete deal", description = "Soft delete deal record")
    public ResponseEntity<Void> deleteDeal(@PathVariable UUID id) {
        dealService.deleteDeal(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/restore")
    @PreAuthorize("hasAuthority('DEALS_DELETE')")
    @Operation(summary = "Restore deal", description = "Undo a soft delete on a deal")
    public ResponseEntity<DealResponse> restoreDeal(@PathVariable UUID id) {
        return ResponseEntity.ok(DealResponse.fromEntity(dealService.restoreDeal(id)));
    }

    @GetMapping("/deleted")
    @PreAuthorize("hasAuthority('DEALS_DELETE')")
    @Operation(summary = "List deleted deals", description = "Soft-deleted deals still inside the retention window")
    public ResponseEntity<PageResponse<DealResponse>> listDeleted(Pageable pageable) {
        Page<DealResponse> page = dealService.listDeleted(pageable).map(DealResponse::fromEntity);
        return ResponseEntity.ok(PageResponse.fromPage(page));
    }
}
