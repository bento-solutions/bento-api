package com.bento.crm.stock.dto;

import com.bento.crm.stock.model.StockMovement;
import com.bento.crm.stock.model.StockSettings;
import com.bento.crm.stock.model.Warehouse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Requests and responses of the stock API, kept together because each is small. */
public final class StockDtos {

    private StockDtos() {}

    public record SettingsRequest(@NotNull StockSettings.IssueTrigger issueTrigger, Boolean receiveOnDelivery) {}

    public record SettingsResponse(StockSettings.IssueTrigger issueTrigger, boolean receiveOnDelivery) {}

    public record WarehouseRequest(@NotBlank @Size(max = 120) String name, @Size(max = 30) String code,
                                   String address, Boolean isDefault, Boolean isActive) {}

    public record WarehouseResponse(UUID id, String name, String code, String address, boolean isDefault,
                                    boolean isActive) {
        public static WarehouseResponse of(Warehouse w) {
            return new WarehouseResponse(w.getId(), w.getName(), w.getCode(), w.getAddress(),
                    Boolean.TRUE.equals(w.getIsDefault()), Boolean.TRUE.equals(w.getIsActive()));
        }
    }

    /** A manual receipt (positive) or issue. */
    public record MoveRequest(@NotNull UUID productId, UUID warehouseId, @NotNull @Positive BigDecimal quantity,
                              @PositiveOrZero BigDecimal unitCost, @Size(max = 2000) String note) {}

    public record TransferRequest(@NotNull UUID productId, @NotNull UUID fromWarehouseId, @NotNull UUID toWarehouseId,
                                  @NotNull @Positive BigDecimal quantity, @Size(max = 2000) String note) {}

    public record CountLine(@NotNull UUID productId, @NotNull @PositiveOrZero BigDecimal countedQuantity) {}

    public record CountRequest(@NotNull UUID warehouseId, @NotEmpty @Valid List<CountLine> lines, @Size(max = 2000) String note) {}

    /** How many products a count changed, and by how much in total (signed). */
    public record CountResult(int adjusted, BigDecimal netChange) {}

    public record LevelRow(UUID productId, String sku, String name, String category, UUID warehouseId,
                           String warehouseName, BigDecimal quantity, BigDecimal reorderLevel, BigDecimal costPrice,
                           BigDecimal value, boolean low) {}

    public record MovementResponse(UUID id, UUID productId, String productName, UUID warehouseId, String warehouseName,
                                   StockMovement.Type type, BigDecimal quantity, BigDecimal unitCost,
                                   BigDecimal balanceAfter, String referenceType, UUID referenceId,
                                   String referenceLabel, String note, Instant createdAt) {}

    /** Stock value at cost: per warehouse and per category, plus the total. */
    public record ValuationReport(BigDecimal totalValue, BigDecimal totalUnits, int products, int lowStock,
                                  List<Group> byWarehouse, List<Group> byCategory) {
        public record Group(String key, String label, BigDecimal value, BigDecimal units) {}
    }
}
