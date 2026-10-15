package com.bento.crm.stock.controller;

import com.bento.crm.common.dto.PageResponse;
import com.bento.crm.stock.dto.StockDtos.*;
import com.bento.crm.stock.service.StockService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/stock")
@RequiredArgsConstructor
@Tag(name = "Stock", description = "Warehouses, stock levels and movements")
public class StockController {

    /**
     * Receiving, issuing, transferring and counting by hand is back-office work: admins and
     * managers (who manage purchase orders). Salespeople move stock only through their deals.
     */
    private static final String CAN_MOVE = "hasAuthority('PURCHASE_ORDERS_WRITE')";

    private final StockService service;

    @GetMapping("/settings")
    @PreAuthorize("isAuthenticated()")
    public SettingsResponse settings() {
        return service.settings();
    }

    @PutMapping("/settings")
    @PreAuthorize("hasAuthority('ADMIN_ACCESS')")
    @Operation(summary = "When stock goes out automatically")
    public SettingsResponse updateSettings(@Valid @RequestBody SettingsRequest request) {
        return service.updateSettings(request);
    }

    @GetMapping("/warehouses")
    @PreAuthorize("isAuthenticated()")
    public List<WarehouseResponse> warehouses() {
        return service.warehouses();
    }

    @PostMapping("/warehouses")
    @PreAuthorize("hasAuthority('ADMIN_ACCESS')")
    public ResponseEntity<WarehouseResponse> createWarehouse(@Valid @RequestBody WarehouseRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createWarehouse(request));
    }

    @PutMapping("/warehouses/{id}")
    @PreAuthorize("hasAuthority('ADMIN_ACCESS')")
    public WarehouseResponse updateWarehouse(@PathVariable UUID id, @Valid @RequestBody WarehouseRequest request) {
        return service.updateWarehouse(id, request);
    }

    @GetMapping("/levels")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "On-hand quantity per product and warehouse")
    public List<LevelRow> levels(@RequestParam(required = false) UUID warehouseId,
                                 @RequestParam(defaultValue = "false") boolean lowOnly) {
        return service.levels(warehouseId, lowOnly);
    }

    @GetMapping("/movements")
    @PreAuthorize("isAuthenticated()")
    public PageResponse<MovementResponse> movements(@RequestParam(required = false) UUID productId,
                                                    @RequestParam(required = false) UUID warehouseId,
                                                    Pageable pageable) {
        return PageResponse.fromPage(service.movements(productId, warehouseId, pageable));
    }

    @GetMapping("/valuation")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Stock value at cost, by warehouse and category")
    public ValuationReport valuation() {
        return service.valuation();
    }

    @PostMapping("/receipts")
    @PreAuthorize(CAN_MOVE)
    public ResponseEntity<MovementResponse> receive(@Valid @RequestBody MoveRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.receive(request));
    }

    @PostMapping("/issues")
    @PreAuthorize(CAN_MOVE)
    public ResponseEntity<MovementResponse> issue(@Valid @RequestBody MoveRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.issue(request));
    }

    @PostMapping("/transfers")
    @PreAuthorize(CAN_MOVE)
    public ResponseEntity<List<MovementResponse>> transfer(@Valid @RequestBody TransferRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.transfer(request));
    }

    @PostMapping("/counts")
    @PreAuthorize(CAN_MOVE)
    @Operation(summary = "Record an inventory count; differences become adjustments")
    public CountResult count(@Valid @RequestBody CountRequest request) {
        return service.count(request);
    }
}
