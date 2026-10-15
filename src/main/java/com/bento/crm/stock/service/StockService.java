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

    /**
     * On-hand quantity per product and warehouse, for every warehouse that has held the product.
     * A tracked product that was never stocked shows once, at zero, in the default warehouse.
     */
    @Transactional
    public List<LevelRow> levels(UUID warehouseId, boolean lowOnly) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        defaultWarehouse(orgId);
        List<LevelRow> rows = jdbc.query("""
                SELECT * FROM (
                    SELECT p.id AS product_id, p.sku, p.name, p.category, w.id AS warehouse_id, w.name AS warehouse_name,
                           l.quantity, p.reorder_level, p.cost_price, p.unit_price, w.is_default
                    FROM stock_level l
                    JOIN product p ON p.id = l.product_id AND p.organization_id = l.organization_id
                    JOIN warehouse w ON w.id = l.warehouse_id AND w.organization_id = l.organization_id
                    WHERE l.organization_id = ? AND p.deleted_at IS NULL AND p.track_stock = TRUE
                      AND w.deleted_at IS NULL AND (w.is_active = TRUE OR l.quantity <> 0)
                    UNION ALL
                    SELECT p.id, p.sku, p.name, p.category, w.id, w.name, 0, p.reorder_level, p.cost_price, p.unit_price, w.is_default
                    FROM product p
                    JOIN warehouse w ON w.organization_id = p.organization_id AND w.is_default = TRUE AND w.deleted_at IS NULL
                    WHERE p.organization_id = ? AND p.deleted_at IS NULL AND p.track_stock = TRUE
                      AND NOT EXISTS (SELECT 1 FROM stock_level l WHERE l.organization_id = p.organization_id AND l.product_id = p.id)
                ) r
                WHERE (CAST(? AS uuid) IS NULL OR r.warehouse_id = CAST(? AS uuid))
                ORDER BY lower(r.name), r.is_default DESC, lower(r.warehouse_name)
                """, (rs, i) -> {
            BigDecimal qty = rs.getBigDecimal("quantity");
            BigDecimal reorder = rs.getBigDecimal("reorder_level");
            BigDecimal cost = rs.getBigDecimal("cost_price");
            BigDecimal valuedAt = valuationPrice(cost, rs.getBigDecimal("unit_price"));
            BigDecimal value = valuedAt != null ? qty.max(BigDecimal.ZERO).multiply(valuedAt).setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
            return new LevelRow(rs.getObject("product_id", UUID.class), rs.getString("sku"), rs.getString("name"),
                    rs.getString("category"), rs.getObject("warehouse_id", UUID.class), rs.getString("warehouse_name"),
                    qty, reorder, cost, value, isLow(qty, reorder));
        }, orgId, orgId, warehouseId, warehouseId);
        return lowOnly ? rows.stream().filter(LevelRow::low).toList() : rows;
    }

    /** Stock is valued at purchase cost, or at the sale price when no cost is set (empty or zero). */
    static BigDecimal valuationPrice(BigDecimal cost, BigDecimal salePrice) {
        return cost != null && cost.signum() > 0 ? cost : salePrice;
    }

    /** Low only when a reorder level is set and the shelf is at or below it. */
    static boolean isLow(BigDecimal qty, BigDecimal reorder) {
        return reorder != null && reorder.signum() > 0 && qty.compareTo(reorder) <= 0;
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
        if (signed.signum() < 0) {
            requireOnHand(orgId, product, w, signed.negate());
        }
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
        requireOnHand(orgId, product, from, r.quantity());
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
                || stillOut(e.organizationId(), REF_INVOICE, e.invoiceId())) {
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
                || stillOut(e.organizationId(), REF_DEAL, e.dealId())) {
            return;
        }
        dealRepository.findByOrganizationIdAndId(e.organizationId(), e.dealId()).ifPresent(deal ->
                issueLines(e.organizationId(), dealLines(deal), REF_DEAL, deal.getId(), dealLabel(deal)));
    }

    private static List<Map<String, Object>> dealLines(com.bento.crm.deal.model.Deal deal) {
        List<Map<String, Object>> lines = new ArrayList<>();
        deal.getOrderLines().forEach(l -> {
            Map<String, Object> m = new HashMap<>();
            m.put("product", l.getProduct());
            m.put("qty", l.getQty());
            lines.add(m);
        });
        return lines;
    }

    private static String dealLabel(com.bento.crm.deal.model.Deal deal) {
        return deal.getOrderNumber() != null ? deal.getOrderNumber() : deal.getTitle();
    }

    @EventListener
    @Transactional
    public void onInvoiceChanged(StockEvents.InvoiceChanged e) {
        if (!movementRepository.existsForReference(e.organizationId(), REF_INVOICE, e.invoiceId())) return;
        invoiceRepository.findByOrganizationIdAndId(e.organizationId(), e.invoiceId()).ifPresent(inv ->
                follow(e.organizationId(), nonNull(inv.getLines()), REF_INVOICE, inv.getId(), inv.getInvoiceNumber()));
    }

    @EventListener
    @Transactional
    public void onDealChanged(StockEvents.DealChanged e) {
        if (!movementRepository.existsForReference(e.organizationId(), REF_DEAL, e.dealId())) return;
        dealRepository.findByOrganizationIdAndId(e.organizationId(), e.dealId()).ifPresent(deal ->
                follow(e.organizationId(), dealLines(deal), REF_DEAL, deal.getId(), dealLabel(deal)));
    }

    /**
     * Brings what a document has taken out in line with its current lines: more goods are issued
     * from the default warehouse, fewer are put back where they came from.
     */
    private void follow(UUID orgId, List<Map<String, Object>> lines, String refType, UUID refId, String label) {
        Map<UUID, BigDecimal> wanted = new LinkedHashMap<>();
        Map<UUID, Product> products = new HashMap<>();
        for (Map<String, Object> line : lines) {
            BigDecimal qty = decimal(line.getOrDefault("qty", line.get("quantity")));
            if (qty == null || qty.signum() <= 0) continue;
            resolve(orgId, line).ifPresent(p -> {
                products.put(p.getId(), p);
                wanted.merge(p.getId(), qty, BigDecimal::add);
            });
        }
        record Out(UUID productId, UUID warehouseId, BigDecimal quantity) {}
        List<Out> outs = jdbc.query("""
                SELECT product_id, warehouse_id, -sum(quantity) AS quantity
                FROM stock_movement WHERE organization_id = ? AND reference_type = ? AND reference_id = ?
                GROUP BY product_id, warehouse_id HAVING sum(quantity) < 0
                """, (rs, i) -> new Out(rs.getObject("product_id", UUID.class), rs.getObject("warehouse_id", UUID.class),
                rs.getBigDecimal("quantity")), orgId, refType, refId);
        Set<UUID> all = new LinkedHashSet<>(wanted.keySet());
        outs.forEach(o -> all.add(o.productId()));
        UUID warehouseId = defaultWarehouse(orgId).getId();
        for (UUID productId : all) {
            BigDecimal out = outs.stream().filter(o -> o.productId().equals(productId))
                    .map(Out::quantity).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal delta = wanted.getOrDefault(productId, BigDecimal.ZERO).subtract(out);
            Product p = products.computeIfAbsent(productId, id ->
                    productRepository.findByOrganizationIdAndIdIncludingDeleted(orgId, id).orElse(null));
            if (p == null || delta.signum() == 0) continue;
            if (delta.signum() > 0) {
                move(orgId, p, warehouseId, StockMovement.Type.ISSUE, delta.negate(), p.getCostPrice(), refType, refId, label, "Modification");
                continue;
            }
            BigDecimal back = delta.negate();
            for (Out o : outs) {
                if (back.signum() <= 0) break;
                if (!o.productId().equals(productId)) continue;
                BigDecimal q = back.min(o.quantity());
                move(orgId, p, o.warehouseId(), StockMovement.Type.RECEIPT, q, p.getCostPrice(), refType, refId, label, "Modification");
                back = back.subtract(q);
            }
        }
    }

    @EventListener
    @Transactional
    public void onInvoiceCancelled(StockEvents.InvoiceCancelled e) {
        giveBack(e.organizationId(), REF_INVOICE, e.invoiceId());
    }

    @EventListener
    @Transactional
    public void onDealLost(StockEvents.DealLost e) {
        giveBack(e.organizationId(), REF_DEAL, e.dealId());
    }

    /** Whether a document's goods are currently out of stock (issued and not given back). */
    private boolean stillOut(UUID orgId, String refType, UUID refId) {
        Integer open = jdbc.queryForObject("""
                SELECT count(*) FROM (
                    SELECT 1 FROM stock_movement WHERE organization_id = ? AND reference_type = ? AND reference_id = ?
                    GROUP BY product_id, warehouse_id HAVING sum(quantity) <> 0) t
                """, Integer.class, orgId, refType, refId);
        return open != null && open > 0;
    }

    /** Puts back whatever a document took out, under the same reference so the history reads as one. */
    private void giveBack(UUID orgId, String refType, UUID refId) {
        record Net(UUID productId, UUID warehouseId, BigDecimal quantity, String label) {}
        List<Net> nets = jdbc.query("""
                SELECT product_id, warehouse_id, sum(quantity) AS quantity, max(reference_label) AS label
                FROM stock_movement WHERE organization_id = ? AND reference_type = ? AND reference_id = ?
                GROUP BY product_id, warehouse_id HAVING sum(quantity) < 0
                """, (rs, i) -> new Net(rs.getObject("product_id", UUID.class), rs.getObject("warehouse_id", UUID.class),
                rs.getBigDecimal("quantity"), rs.getString("label")), orgId, refType, refId);
        for (Net n : nets) {
            productRepository.findByOrganizationIdAndIdIncludingDeleted(orgId, n.productId()).ifPresent(p ->
                    move(orgId, p, n.warehouseId(), StockMovement.Type.RECEIPT, n.quantity().negate(), p.getCostPrice(),
                            refType, refId, n.label(), "Annulation"));
        }
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
        for (String key : List.of("sku", "product", "item", "name", "description")) {
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
        if (signed.signum() < 0 && isLow(after, reorder) && !isLow(before, reorder)) {
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

    /**
     * People cannot take out more than the books show (a count corrects the books first). Sales
     * still go through when the shelf is short: they are never blocked by stock.
     */
    private void requireOnHand(UUID orgId, Product product, Warehouse w, BigDecimal wanted) {
        // Lock the level row so two people cannot both take the last units.
        List<BigDecimal> q = jdbc.queryForList(
                "SELECT quantity FROM stock_level WHERE organization_id = ? AND product_id = ? AND warehouse_id = ? FOR UPDATE",
                BigDecimal.class, orgId, product.getId(), w.getId());
        BigDecimal onHand = q.isEmpty() ? BigDecimal.ZERO : q.get(0);
        if (wanted.compareTo(onHand) > 0) {
            throw new IllegalArgumentException("Only " + onHand.stripTrailingZeros().toPlainString() + " of "
                    + product.getName() + " in " + w.getName());
        }
    }

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
