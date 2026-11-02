package com.bento.crm.ledger.service;

import com.bento.crm.common.context.TenantContext;
import com.bento.crm.ledger.dto.LedgerDtos;
import com.bento.crm.ledger.service.LedgerPostingSetupService.GroupRow;
import com.bento.crm.ledger.service.LedgerPostingSetupService.Snapshot;
import com.bento.crm.ledger.service.LedgerPostingSetupService.VatRow;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Which VAT code a document line uses (phase 3). The order, shared with the web app:
 * <ol>
 *   <li>the line's own {@code vatCode}, when the user set one;</li>
 *   <li>else, when the partner's business group (null = the default group) is EXEMPT, its exempt code;</li>
 *   <li>else the product's {@code vatCode};</li>
 *   <li>else the product group's code: the line's override, then the product's group, then the default group;</li>
 *   <li>else TVA20.</li>
 * </ol>
 * Lines saved before phase 3 carry only a {@code vatRate}: their code is matched by rate (0 is TVA0).
 */
@Service
@RequiredArgsConstructor
public class VatDeterminationService {

    public static final String DEFAULT_CODE = "TVA20";
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal DEFAULT_FRACTION = new BigDecimal("0.20");

    private final LedgerPostingSetupService postingSetup;
    private final JdbcTemplate jdbc;

    /** A VAT code with its rate as a percentage (20) and, when it has one, its invoice mention. */
    public record Choice(String vatCode, BigDecimal rate, boolean exempt, String mention) {
        public BigDecimal fraction() {
            return rate.divide(HUNDRED, 6, RoundingMode.HALF_UP).stripTrailingZeros();
        }

        static Choice of(VatRow r) {
            return new Choice(r.code(), r.rate().stripTrailingZeros(), r.exempt(), r.mention());
        }
    }

    /** One VAT row of a document: per code (or per rate for a line with no known code). Rate is a percentage. */
    public record BreakdownRow(String vatCode, BigDecimal rate, BigDecimal base, BigDecimal vat, String mention) {}

    // Determination

    @Transactional
    public LedgerDtos.VatDetermination determine(UUID partnerId, UUID productId, UUID productGroupId) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        Snapshot s = postingSetup.snapshot(orgId);
        Choice c = determine(orgId, s, partnerId, productId, productGroupId, new HashMap<>());
        return new LedgerDtos.VatDetermination(c.vatCode(), c.rate(), c.exempt(), c.mention());
    }

    /** Steps 2 to 5 for one partner and product, reading the organization's setup. */
    @Transactional
    public Choice determine(UUID orgId, UUID partnerId, UUID productId, UUID productGroupId) {
        return determine(orgId, postingSetup.snapshot(orgId), partnerId, productId, productGroupId, new HashMap<>());
    }

    /** Steps 2 to 5 of the order: what a line with no code of its own uses. */
    public Choice determine(UUID orgId, Snapshot s, UUID partnerId, UUID productId, UUID productGroupId,
                            Map<UUID, Map<String, Object>> productCache) {
        UUID partnerBusinessGroup = null;
        if (partnerId != null) {
            List<UUID> rows = jdbc.queryForList("SELECT business_group_id FROM partner WHERE organization_id = ? AND id = ?",
                    UUID.class, orgId, partnerId);
            partnerBusinessGroup = rows.isEmpty() ? null : rows.get(0);
        }
        GroupRow business = s.businessGroupRow(s.businessGroup(partnerBusinessGroup));
        if (business != null && "EXEMPT".equals(business.vatRule()) && business.exemptVatCode() != null) {
            VatRow r = s.vatByCode(business.exemptVatCode());
            if (r != null) return Choice.of(r);
        }
        Map<String, Object> product = productId == null ? Map.of() : productCache.computeIfAbsent(productId, id -> {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT vat_code, product_group_id FROM product WHERE organization_id = ? AND id = ?", orgId, id);
            return rows.isEmpty() ? Map.of() : rows.get(0);
        });
        String productCode = (String) product.get("vat_code");
        if (productCode != null && s.vatByCode(productCode) != null) {
            return Choice.of(s.vatByCode(productCode));
        }
        UUID group = productGroupId != null && s.hasProductGroup(productGroupId) ? productGroupId
                : product.get("product_group_id") != null && s.hasProductGroup((UUID) product.get("product_group_id"))
                ? (UUID) product.get("product_group_id") : s.productGroup(null);
        GroupRow g = s.productGroupRow(group);
        if (g != null && g.vatCode() != null && s.vatByCode(g.vatCode()) != null) {
            return Choice.of(s.vatByCode(g.vatCode()));
        }
        VatRow standard = s.vatByCode(DEFAULT_CODE);
        return standard != null ? Choice.of(standard) : new Choice(DEFAULT_CODE, new BigDecimal("20"), false, null);
    }

    // Lines

    /**
     * Gives every usable line its {@code vatCode} and {@code vatRate} (a fraction) by the order
     * above, and returns the VAT breakdown of the lines.
     *
     * @throws IllegalArgumentException (400) for a VAT code the organization does not have
     */
    @Transactional
    public List<BreakdownRow> fillLines(UUID orgId, UUID partnerId, List<Map<String, Object>> lines) {
        if (lines == null || lines.isEmpty()) return List.of();
        Snapshot s = postingSetup.snapshot(orgId);
        Map<UUID, Map<String, Object>> products = new HashMap<>();
        for (Map<String, Object> line : lines) {
            if (line == null) continue;
            Object rawCode = line.get("vatCode");
            if (rawCode != null && !rawCode.toString().isBlank()) {
                String code = rawCode.toString().trim().toUpperCase(Locale.ROOT);
                VatRow r = s.vatByCode(code);
                if (r == null) {
                    throw new IllegalArgumentException("Unknown VAT code on a line: " + rawCode);
                }
                set(line, Choice.of(r));
                continue;
            }
            Object rawRate = first(line, "vatRate", "vat_rate", "taxRate");
            if (rawRate != null) {
                BigDecimal fraction = fraction(rawRate);
                VatRow r = fraction == null ? null : s.vatFor(fraction);
                if (r != null) {
                    set(line, Choice.of(r));
                } else if (fraction != null) {
                    line.put("vatRate", fraction);
                }
                continue;
            }
            Choice c = determine(orgId, s, partnerId, uuid(line.get("productId")), uuid(line.get("productGroupId")), products);
            set(line, c);
        }
        Map<String, String> mentions = new HashMap<>();
        for (VatRow r : s.vat()) {
            if (r.mention() != null) mentions.put(r.code(), r.mention());
        }
        return breakdown(lines, mentions);
    }

    private static void set(Map<String, Object> line, Choice c) {
        line.put("vatCode", c.vatCode());
        line.put("vatRate", c.fraction());
    }

    /** code -> mention of the organization's VAT setup. */
    @Transactional
    public Map<String, String> mentions(UUID orgId) {
        Map<String, String> m = new HashMap<>();
        for (VatRow r : postingSetup.snapshot(orgId).vat()) {
            if (r.mention() != null) m.put(r.code(), r.mention());
        }
        return m;
    }

    /**
     * The VAT breakdown of saved lines (which carry their code and rate), highest rate first. A
     * line with no qty or unit price is left out, as in the totals.
     */
    public static List<BreakdownRow> breakdown(List<Map<String, Object>> lines, Map<String, String> mentions) {
        record Acc(String code, BigDecimal rate) {}
        Map<String, Acc> keys = new LinkedHashMap<>();
        Map<String, BigDecimal> bases = new LinkedHashMap<>();
        if (lines != null) {
            for (Map<String, Object> line : lines) {
                if (line == null) continue;
                BigDecimal base = net(line);
                if (base == null) continue;
                Object code = line.get("vatCode");
                BigDecimal fraction = fraction(first(line, "vatRate", "vat_rate", "taxRate"));
                if (fraction == null) fraction = DEFAULT_FRACTION;
                String key = code != null ? code.toString() : "rate:" + fraction.stripTrailingZeros().toPlainString();
                keys.putIfAbsent(key, new Acc(code == null ? null : code.toString(), fraction));
                bases.merge(key, base, BigDecimal::add);
            }
        }
        List<BreakdownRow> out = new ArrayList<>();
        keys.forEach((key, acc) -> {
            BigDecimal base = bases.get(key).setScale(2, RoundingMode.HALF_UP);
            BigDecimal vat = base.multiply(acc.rate()).setScale(2, RoundingMode.HALF_UP);
            out.add(new BreakdownRow(acc.code(), acc.rate().multiply(HUNDRED).stripTrailingZeros(), base, vat,
                    acc.code() == null ? null : mentions.get(acc.code())));
        });
        out.sort(Comparator.comparing(BreakdownRow::rate).reversed());
        return out;
    }

    /** qty × unit price of a line, or null when either is missing. */
    public static BigDecimal net(Map<String, Object> line) {
        BigDecimal qty = decimal(line.get("qty"));
        BigDecimal price = decimal(first(line, "unitPrice", "unit_price"));
        if (qty == null || price == null) return null;
        return qty.multiply(price);
    }

    /** A rate as a fraction: 0.2 stays, 20 becomes 0.2; null when unreadable or negative. */
    public static BigDecimal fraction(Object raw) {
        BigDecimal r = decimal(raw);
        if (r == null || r.signum() < 0) return null;
        if (r.compareTo(BigDecimal.ONE) > 0) r = r.divide(HUNDRED, 6, RoundingMode.HALF_UP);
        return r.stripTrailingZeros();
    }

    private static Object first(Map<String, Object> map, String... keys) {
        for (String k : keys) {
            Object v = map.get(k);
            if (v != null && !(v instanceof String str && str.isBlank())) return v;
        }
        return null;
    }

    static BigDecimal decimal(Object value) {
        if (value == null) return null;
        if (value instanceof BigDecimal d) return d;
        if (value instanceof Integer || value instanceof Long) return BigDecimal.valueOf(((Number) value).longValue());
        if (value instanceof Number n) return new BigDecimal(n.toString());
        try {
            return new BigDecimal(value.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static UUID uuid(Object value) {
        if (value == null || value.toString().isBlank()) return null;
        try {
            return UUID.fromString(value.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
