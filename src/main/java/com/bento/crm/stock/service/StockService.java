package com.bento.crm.stock.service;

import com.bento.crm.common.context.TenantContext;
import com.bento.crm.common.exception.ResourceNotFoundException;
import com.bento.crm.deal.model.Deal;
import com.bento.crm.deal.repository.DealRepository;
import com.bento.crm.invoice.model.Invoice;
import com.bento.crm.invoice.repository.InvoiceRepository;
import com.bento.crm.notification.model.Notification;
import com.bento.crm.notification.service.NotificationService;
import com.bento.crm.product.model.Product;
import com.bento.crm.product.repository.ProductRepository;
import com.bento.crm.purchaseorder.model.PurchaseOrder;
import com.bento.crm.purchaseorder.repository.PurchaseOrderRepository;
import com.bento.crm.stock.dto.StockDtos.*;
import com.bento.crm.stock.event.StockEvents;
import com.bento.crm.stock.model.StockMovement;
import com.bento.crm.stock.model.StockSettings;
import com.bento.crm.stock.model.Warehouse;
import com.bento.crm.stock.repository.StockMovementRepository;
import com.bento.crm.stock.repository.StockSettingsRepository;
import com.bento.crm.stock.repository.WarehouseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Stock levels per product and warehouse, and the movements that change them. Levels are written
 * only through {@link #move}, which updates the level atomically and records the movement, so the
 * ledger and the levels cannot drift apart. Quantities may go negative: a sale is never blocked
 * because the books say the shelf is empty; the count that follows corrects it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockService {

    static final String REF_PURCHASE_ORDER = "PURCHASE_ORDER";
    static final String REF_INVOICE = "INVOICE";
    static final String REF_DEAL = "DEAL";
    static final String REF_TRANSFER = "TRANSFER";
    static final String REF_COUNT = "COUNT";

    private final WarehouseRepository warehouseRepository;
    private final StockMovementRepository movementRepository;
    private final StockSettingsRepository settingsRepository;
    private final ProductRepository productRepository;
    private final InvoiceRepository invoiceRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final DealRepository dealRepository;
    private final NotificationService notificationService;
    private final JdbcTemplate jdbc;

    // ── Settings ────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public SettingsResponse settings() {
        StockSettings s = settingsFor(TenantContext.getCurrentOrganizationId());
        return new SettingsResponse(s.getIssueTrigger(), Boolean.TRUE.equals(s.getReceiveOnDelivery()));
    }

    @Transactional
    public SettingsResponse updateSettings(SettingsRequest request) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        StockSettings s = settingsFor(orgId);
        s.setIssueTrigger(request.issueTrigger());
        if (request.receiveOnDelivery() != null) {
            s.setReceiveOnDelivery(request.receiveOnDelivery());
        }
        s = settingsRepository.save(s);
        return new SettingsResponse(s.getIssueTrigger(), Boolean.TRUE.equals(s.getReceiveOnDelivery()));
    }

    /** Called at signup with the trigger the new organization chose (or its industry's default). */
    @Transactional
    public void initialize(UUID orgId, StockSettings.IssueTrigger trigger, String industry) {
        StockSettings s = settingsFor(orgId);
        s.setIssueTrigger(trigger != null ? trigger : StockSettings.defaultFor(industry));
        settingsRepository.save(s);
    }

    private StockSettings settingsFor(UUID orgId) {
        return settingsRepository.findByOrganizationIdAndDeletedAtIsNull(orgId).orElseGet(() -> {
            StockSettings s = new StockSettings();
            s.setOrganizationId(orgId);
            return s;
        });
    }

    // ── Warehouses ──────────────────────────────────────────────────────────────

    @Transactional
    public List<WarehouseResponse> warehouses() {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        defaultWarehouse(orgId);
        return warehouseRepository.findAllForOrganization(orgId).stream().map(WarehouseResponse::of).toList();
    }

    @Transactional
    public WarehouseResponse createWarehouse(WarehouseRequest r) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        defaultWarehouse(orgId);
        Warehouse w = new Warehouse();
        w.setOrganizationId(orgId);
        apply(w, r);
        return WarehouseResponse.of(save(orgId, w, Boolean.TRUE.equals(r.isDefault())));
    }

    @Transactional
    public WarehouseResponse updateWarehouse(UUID id, WarehouseRequest r) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        Warehouse w = warehouse(orgId, id);
        if (Boolean.TRUE.equals(w.getIsDefault()) && (Boolean.FALSE.equals(r.isDefault()) || Boolean.FALSE.equals(r.isActive()))) {
            throw new IllegalArgumentException("Make another warehouse the default first");
        }
        apply(w, r);
        return WarehouseResponse.of(save(orgId, w, Boolean.TRUE.equals(r.isDefault())));
    }

    private void apply(Warehouse w, WarehouseRequest r) {
        w.setName(r.name().trim());
        w.setCode(blankToNull(r.code()));
        w.setAddress(blankToNull(r.address()));
        if (r.isActive() != null) {
            w.setIsActive(r.isActive());
        }
    }

    private Warehouse save(UUID orgId, Warehouse w, boolean makeDefault) {
        if (makeDefault && !Boolean.TRUE.equals(w.getIsDefault())) {
            warehouseRepository.findDefault(orgId).ifPresent(old -> {
                old.setIsDefault(false);
                warehouseRepository.saveAndFlush(old);
            });
            w.setIsDefault(true);
            w.setIsActive(true);
        }
        try {
            return warehouseRepository.saveAndFlush(w);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new IllegalArgumentException("A warehouse with this name already exists");
        }
    }

    /** The organization's default warehouse, created on first use as "Entrepôt principal". */
    Warehouse defaultWarehouse(UUID orgId) {
        return warehouseRepository.findDefault(orgId).orElseGet(() -> {
            Warehouse w = new Warehouse();
            w.setOrganizationId(orgId);
            w.setName("Entrepôt principal");
            w.setIsDefault(true);
            return warehouseRepository.saveAndFlush(w);
        });
    }

    private Warehouse warehouse(UUID orgId, UUID id) {
        return warehouseRepository.findForOrganization(orgId, id)
                .orElseThrow(() -> new ResourceNotFoundException("Warehouse not found"));
    }

    // ── Levels, movements, valuation ───────────────────────────────────────────

    /** On-hand quantity of every stock-tracked product, per warehouse (zero rows included). */
    @Transactional
    public List<LevelRow> levels(UUID warehouseId, boolean lowOnly) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        defaultWarehouse(orgId);
        List<LevelRow> rows = jdbc.query("""
                SELECT p.id AS product_id, p.sku, p.name, p.category, w.id AS warehouse_id, w.name AS warehouse_name,
                       COALESCE(l.quantity, 0) AS quantity, p.reorder_level, p.cost_price
                FROM product p
                CROSS JOIN warehouse w
                LEFT JOIN stock_level l ON l.organization_id = p.organization_id AND l.product_id = p.id AND l.warehouse_id = w.id
                WHERE p.organization_id = ? AND w.organization_id = p.organization_id
                  AND p.deleted_at IS NULL AND p.track_stock = TRUE
                  AND w.deleted_at IS NULL AND (w.is_active = TRUE OR COALESCE(l.quantity, 0) <> 0)
                  AND (CAST(? AS uuid) IS NULL OR w.id = CAST(? AS uuid))
                ORDER BY lower(p.name), w.is_default DESC, lower(w.name)
                """, (rs, i) -> {
            BigDecimal qty = rs.getBigDecimal("quantity");
            BigDecimal reorder = rs.getBigDecimal("reorder_level");
            BigDecimal cost = rs.getBigDecimal("cost_price");
            BigDecimal value = cost != null ? qty.max(BigDecimal.ZERO).multiply(cost).setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
            return new LevelRow(rs.getObject("product_id", UUID.class), rs.getString("sku"), rs.getString("name"),
                    rs.getString("category"), rs.getObject("warehouse_id", UUID.class), rs.getString("warehouse_name"),
                    qty, reorder, cost, value, reorder != null && qty.compareTo(reorder) <= 0);
        }, orgId, warehouseId, warehouseId);
        return lowOnly ? rows.stream().filter(LevelRow::low).toList() : rows;
    }

    @Transactional(readOnly = true)
    public Page<MovementResponse> movements(UUID productId, UUID warehouseId, Pageable pageable) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        Map<UUID, String> products = new HashMap<>();
        Map<UUID, String> warehouses = new HashMap<>();
        warehouseRepository.findAllForOrganization(orgId).forEach(w -> warehouses.put(w.getId(), w.getName()));
        return movementRepository.search(orgId, productId, warehouseId, pageable).map(m -> new MovementResponse(
                m.getId(), m.getProductId(),
                products.computeIfAbsent(m.getProductId(), id -> productRepository.findByOrganizationIdAndIdIncludingDeleted(orgId, id)
                        .map(Product::getName).orElse("")),
                m.getWarehouseId(), warehouses.getOrDefault(m.getWarehouseId(), ""), m.getType(), m.getQuantity(),
                m.getUnitCost(), m.getBalanceAfter(), m.getReferenceType(), m.getReferenceId(), m.getReferenceLabel(),
                m.getNote(), m.getCreatedAt()));
    }

    @Transactional
    public ValuationReport valuation() {
        List<LevelRow> rows = levels(null, false);
        Map<String, ValuationReport.Group> byWarehouse = new LinkedHashMap<>();
        Map<String, ValuationReport.Group> byCategory = new TreeMap<>();
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal units = BigDecimal.ZERO;
        Set<UUID> products = new HashSet<>();
        Set<UUID> low = new HashSet<>();
        for (LevelRow r : rows) {
            BigDecimal u = r.quantity().max(BigDecimal.ZERO);
            total = total.add(r.value());
            units = units.add(u);
            products.add(r.productId());
            if (r.low()) low.add(r.productId());
            add(byWarehouse, r.warehouseId().toString(), r.warehouseName(), r.value(), u);
            String category = r.category() != null && !r.category().isBlank() ? r.category() : "";
            add(byCategory, category, category, r.value(), u);
        }
        return new ValuationReport(total, units, products.size(), low.size(),
                new ArrayList<>(byWarehouse.values()), new ArrayList<>(byCategory.values()));
    }

    private static void add(Map<String, ValuationReport.Group> groups, String key, String label, BigDecimal value, BigDecimal units) {
        groups.merge(key, new ValuationReport.Group(key, label, value, units),
                (a, b) -> new ValuationReport.Group(key, label, a.value().add(b.value()), a.units().add(b.units())));
    }

    // ── Manual operations ───────────────────────────────────────────────────────

    @Transactional
    public MovementResponse receive(MoveRequest r) {
        return manual(r, StockMovement.Type.RECEIPT, r.quantity());
    }

    @Transactional
    public MovementResponse issue(MoveRequest r) {
        return manual(r, StockMovement.Type.ISSUE, r.quantity().negate());
    }

    private MovementResponse manual(MoveRequest r, StockMovement.Type type, BigDecimal signed) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        Product product = trackedProduct(orgId, r.productId());
        Warehouse w = r.warehouseId() != null ? activeWarehouse(orgId, r.warehouseId()) : defaultWarehouse(orgId);
        BigDecimal cost = r.unitCost() != null ? r.unitCost() : product.getCostPrice();
        StockMovement m = move(orgId, product, w.getId(), type, signed, cost, null, null, null, r.note());
        return toResponse(m, product.getName(), w.getName());
    }

    @Transactional
    public List<MovementResponse> transfer(TransferRequest r) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        if (r.fromWarehouseId().equals(r.toWarehouseId())) {
            throw new IllegalArgumentException("Choose two different warehouses");
        }
        Product product = trackedProduct(orgId, r.productId());
        Warehouse from = warehouse(orgId, r.fromWarehouseId());
        Warehouse to = activeWarehouse(orgId, r.toWarehouseId());
        UUID transferId = UUID.randomUUID();
        String label = from.getName() + " → " + to.getName();
        StockMovement out = move(orgId, product, from.getId(), StockMovement.Type.TRANSFER_OUT, r.quantity().negate(),
                product.getCostPrice(), REF_TRANSFER, transferId, label, r.note());
        StockMovement in = move(orgId, product, to.getId(), StockMovement.Type.TRANSFER_IN, r.quantity(),
                product.getCostPrice(), REF_TRANSFER, transferId, label, r.note());
        return List.of(toResponse(out, product.getName(), from.getName()), toResponse(in, product.getName(), to.getName()));
    }

    /** An inventory count: each counted product is adjusted to what was found on the shelf. */
    @Transactional
    public CountResult count(CountRequest r) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        Warehouse w = warehouse(orgId, r.warehouseId());
        UUID countId = UUID.randomUUID();
        String label = "Inventaire " + java.time.LocalDate.now(java.time.ZoneId.of("Africa/Casablanca"));
        int adjusted = 0;
        BigDecimal net = BigDecimal.ZERO;
        for (CountLine line : r.lines()) {
            Product product = trackedProduct(orgId, line.productId());
            BigDecimal current = level(orgId, product.getId(), w.getId());
            BigDecimal diff = line.countedQuantity().subtract(current);
            if (diff.signum() == 0) continue;
            move(orgId, product, w.getId(), StockMovement.Type.ADJUSTMENT, diff, product.getCostPrice(),
                    REF_COUNT, countId, label, r.note());
            adjusted++;
            net = net.add(diff);
        }
        return new CountResult(adjusted, net);
    }

    // ── Automatic movements ─────────────────────────────────────────────────────

    @EventListener
    @Transactional
    public void onPurchaseOrderDelivered(StockEvents.PurchaseOrderDelivered e) {
        StockSettings s = settingsFor(e.organizationId());
        if (!Boolean.TRUE.equals(s.getReceiveOnDelivery())
                || movementRepository.existsForReference(e.organizationId(), REF_PURCHASE_ORDER, e.purchaseOrderId())) {
            return;
        }
        purchaseOrderRepository.findByOrganizationIdAndId(e.organizationId(), e.purchaseOrderId()).ifPresent(po -> {
            UUID warehouseId = defaultWarehouse(e.organizationId()).getId();
            for (Map<String, Object> line : nonNull(po.getLines())) {
                BigDecimal qty = decimal(line.getOrDefault("qty", line.get("quantity")));
                resolve(e.organizationId(), line).ifPresent(p -> {
                    if (qty == null || qty.signum() <= 0) return;
                    BigDecimal cost = decimal(line.getOrDefault("cost", line.getOrDefault("unitPrice", line.get("unit_price"))));
                    move(e.organizationId(), p, warehouseId, StockMovement.Type.RECEIPT, qty, cost != null ? cost : p.getCostPrice(),
                            REF_PURCHASE_ORDER, po.getId(), po.getOrderNumber(), null);
                });
            }
        });
    }

    @EventListener
    @Transactional
    public void onInvoiceIssued(StockEvents.InvoiceIssued e) {
        if (settingsFor(e.organizationId()).getIssueTrigger() != StockSettings.IssueTrigger.INVOICE_SENT
                || movementRepository.existsForReference(e.organizationId(), REF_INVOICE, e.invoiceId())) {
            return;
        }
        invoiceRepository.findByOrganizationIdAndId(e.organizationId(), e.invoiceId())
                .filter(inv -> inv.getType() == Invoice.InvoiceType.CUSTOMER)
                .ifPresent(inv -> issueLines(e.organizationId(), nonNull(inv.getLines()), REF_INVOICE, inv.getId(), inv.getInvoiceNumber()));
    }

    @EventListener
    @Transactional
    public void onDealWon(StockEvents.DealWon e) {
        if (settingsFor(e.organizationId()).getIssueTrigger() != StockSettings.IssueTrigger.DEAL_WON
                || movementRepository.existsForReference(e.organizationId(), REF_DEAL, e.dealId())) {
            return;
        }
        dealRepository.findByOrganizationIdAndId(e.organizationId(), e.dealId()).ifPresent(deal -> {
            List<Map<String, Object>> lines = new ArrayList<>();
            deal.getOrderLines().forEach(l -> {
                Map<String, Object> m = new HashMap<>();
                m.put("product", l.getProduct());
                m.put("qty", l.getQty());
                lines.add(m);
            });
            issueLines(e.organizationId(), lines, REF_DEAL, deal.getId(),
                    deal.getOrderNumber() != null ? deal.getOrderNumber() : deal.getTitle());
        });
    }

    private void issueLines(UUID orgId, List<Map<String, Object>> lines, String refType, UUID refId, String label) {
        UUID warehouseId = defaultWarehouse(orgId).getId();
        for (Map<String, Object> line : lines) {
            BigDecimal qty = decimal(line.getOrDefault("qty", line.get("quantity")));
            if (qty == null || qty.signum() <= 0) continue;
            resolve(orgId, line).ifPresent(p -> move(orgId, p, warehouseId, StockMovement.Type.ISSUE, qty.negate(),
                    p.getCostPrice(), refType, refId, label, null));
        }
    }

    /**
     * The stock-tracked product a document line refers to. Lines name products in different ways
     * (an id, the SKU, or the product name typed or picked in the form), so each is tried in turn.
     */
    Optional<Product> resolve(UUID orgId, Map<String, Object> line) {
        for (String key : List.of("productId", "product_id")) {
            Object v = line.get(key);
            if (v != null) {
                try {
                    Optional<Product> p = productRepository.findByOrganizationIdAndId(orgId, UUID.fromString(v.toString()));
                    if (p.isPresent()) return p.filter(x -> Boolean.TRUE.equals(x.getTrackStock()));
                } catch (IllegalArgumentException ignored) {
                    // not an id
                }
            }
        }
        for (String key : List.of("sku", "product", "item", "name")) {
            Object v = line.get(key);
            if (v == null || v.toString().isBlank()) continue;
            List<UUID> ids = jdbc.queryForList("""
                    SELECT id FROM product
                    WHERE organization_id = ? AND deleted_at IS NULL AND (lower(sku) = lower(?) OR lower(name) = lower(?))
                    ORDER BY (lower(sku) = lower(?)) DESC LIMIT 1
                    """, UUID.class, orgId, v.toString().trim(), v.toString().trim(), v.toString().trim());
            if (!ids.isEmpty()) {
                return productRepository.findByOrganizationIdAndId(orgId, ids.get(0)).filter(x -> Boolean.TRUE.equals(x.getTrackStock()));
            }
        }
        return Optional.empty();
    }

    // ── The one write path ──────────────────────────────────────────────────────

    /**
     * Changes a product's level in a warehouse by {@code signed} and records the movement. The
     * level is updated with a single upsert, so two movements at once both count.
     */
    StockMovement move(UUID orgId, Product product, UUID warehouseId, StockMovement.Type type, BigDecimal signed,
                       BigDecimal unitCost, String refType, UUID refId, String refLabel, String note) {
        BigDecimal after = jdbc.queryForObject("""
                INSERT INTO stock_level (organization_id, product_id, warehouse_id, quantity, updated_at)
                VALUES (?, ?, ?, ?, now())
                ON CONFLICT (organization_id, product_id, warehouse_id)
                DO UPDATE SET quantity = stock_level.quantity + EXCLUDED.quantity, updated_at = now()
                RETURNING quantity
                """, BigDecimal.class, orgId, product.getId(), warehouseId, signed);
        StockMovement m = new StockMovement();
        m.setOrganizationId(orgId);
        m.setProductId(product.getId());
        m.setWarehouseId(warehouseId);
        m.setType(type);
        m.setQuantity(signed);
        m.setUnitCost(unitCost);
        m.setBalanceAfter(after);
        m.setReferenceType(refType);
        m.setReferenceId(refId);
        m.setReferenceLabel(refLabel != null && refLabel.length() > 100 ? refLabel.substring(0, 100) : refLabel);
        m.setNote(blankToNull(note));
        m = movementRepository.save(m);

        BigDecimal reorder = product.getReorderLevel();
        BigDecimal before = after.subtract(signed);
        if (signed.signum() < 0 && reorder != null && before.compareTo(reorder) > 0 && after.compareTo(reorder) <= 0) {
            notifyLowStock(orgId, product, warehouseId, after);
        }
        return m;
    }

    private void notifyLowStock(UUID orgId, Product product, UUID warehouseId, BigDecimal after) {
        String warehouse = warehouseRepository.findForOrganization(orgId, warehouseId).map(Warehouse::getName).orElse("");
        List<UUID> recipients = jdbc.queryForList(
                "SELECT id FROM app_user WHERE organization_id = ? AND role IN ('ADMIN', 'MANAGER') AND is_active = true",
                UUID.class, orgId);
        for (UUID userId : recipients) {
            Notification n = new Notification();
            n.setOrganizationId(orgId);
            n.setRecipientUserId(userId);
            n.setType(Notification.NotificationType.SYSTEM);
            n.setTitle("Low stock: " + product.getName());
            n.setMessage(after.stripTrailingZeros().toPlainString() + " left in " + warehouse
                    + " (reorder at " + product.getReorderLevel().stripTrailingZeros().toPlainString() + ").");
            n.setRelatedEntityType("PRODUCT");
            n.setRelatedEntityId(product.getId());
            n.setIsRead(false);
            notificationService.createForOrganization(orgId, n);
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private BigDecimal level(UUID orgId, UUID productId, UUID warehouseId) {
        List<BigDecimal> q = jdbc.queryForList(
                "SELECT quantity FROM stock_level WHERE organization_id = ? AND product_id = ? AND warehouse_id = ?",
                BigDecimal.class, orgId, productId, warehouseId);
        return q.isEmpty() ? BigDecimal.ZERO : q.get(0);
    }

    private Product trackedProduct(UUID orgId, UUID productId) {
        Product p = productRepository.findByOrganizationIdAndId(orgId, productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found"));
        if (!Boolean.TRUE.equals(p.getTrackStock())) {
            throw new IllegalArgumentException("Turn on stock tracking for " + p.getName() + " first");
        }
        return p;
    }

    private Warehouse activeWarehouse(UUID orgId, UUID id) {
        Warehouse w = warehouse(orgId, id);
        if (!Boolean.TRUE.equals(w.getIsActive())) {
            throw new IllegalArgumentException(w.getName() + " is archived");
        }
        return w;
    }

    private static MovementResponse toResponse(StockMovement m, String productName, String warehouseName) {
        return new MovementResponse(m.getId(), m.getProductId(), productName, m.getWarehouseId(), warehouseName,
                m.getType(), m.getQuantity(), m.getUnitCost(), m.getBalanceAfter(), m.getReferenceType(),
                m.getReferenceId(), m.getReferenceLabel(), m.getNote(), m.getCreatedAt());
    }

    private static List<Map<String, Object>> nonNull(List<Map<String, Object>> lines) {
        return lines != null ? lines : List.of();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static BigDecimal decimal(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal d) return d;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try {
            return new BigDecimal(v.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
