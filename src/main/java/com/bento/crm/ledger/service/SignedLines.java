package com.bento.crm.ledger.service;

import com.bento.crm.ledger.service.LedgerPostingService.PostingLine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds the lines of a closing entry from signed amounts (debit positive, credit negative),
 * merged per account, partner and cost center: debits first, then credits, zero lines dropped.
 */
final class SignedLines {

    private record Key(UUID account, UUID partner, UUID costCenter) {}

    private final Map<Key, BigDecimal> amounts = new LinkedHashMap<>();
    private final Map<Key, String> labels = new HashMap<>();

    SignedLines add(UUID account, UUID partner, UUID costCenter, BigDecimal signed, String label) {
        if (signed == null || signed.signum() == 0) return this;
        Key k = new Key(account, partner, costCenter);
        amounts.merge(k, signed, BigDecimal::add);
        if (label != null) labels.putIfAbsent(k, label);
        return this;
    }

    SignedLines debit(UUID account, BigDecimal amount, String label) {
        return add(account, null, null, amount, label);
    }

    SignedLines credit(UUID account, BigDecimal amount, String label) {
        return add(account, null, null, amount == null ? null : amount.negate(), label);
    }

    List<PostingLine> lines() {
        List<PostingLine> out = new ArrayList<>();
        for (boolean debits : new boolean[]{true, false}) {
            amounts.forEach((k, v) -> {
                BigDecimal a = v.setScale(2, RoundingMode.HALF_UP);
                if (a.signum() == 0 || (a.signum() > 0) != debits) return;
                out.add(new PostingLine(k.account(), k.partner(), k.costCenter(), labels.get(k),
                        a.signum() > 0 ? a : null, a.signum() < 0 ? a.negate() : null));
            });
        }
        return out;
    }

    /** Whether there is something to post (at least two non-zero lines). */
    boolean postable() {
        return lines().size() >= 2;
    }

    static BigDecimal money(BigDecimal v) {
        return v == null ? BigDecimal.ZERO.setScale(2) : v.setScale(2, RoundingMode.HALF_UP);
    }

    /** A stable source id per organization and key (a period, a date...), so a re-run finds its own entry. */
    static UUID sourceId(UUID orgId, String key) {
        return UUID.nameUUIDFromBytes((orgId + "|" + key).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
