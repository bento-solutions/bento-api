package com.bento.crm.invoice.service;

import com.bento.crm.invoice.dto.InvoiceResponse;
import com.bento.crm.invoice.model.Invoice;
import com.bento.crm.ledger.service.VatDeterminationService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The VAT breakdown of a saved invoice: per code, net of the advances it deducts. */
public final class InvoiceVat {

    private InvoiceVat() {}

    public static List<InvoiceResponse.VatBreakdownRow> breakdown(Invoice invoice, Map<String, String> mentions) {
        Map<String, InvoiceResponse.VatBreakdownRow> rows = new LinkedHashMap<>();
        for (VatDeterminationService.BreakdownRow r : VatDeterminationService.breakdown(invoice.getLines(), mentions)) {
            String key = r.vatCode() != null ? r.vatCode() : "rate:" + r.rate().toPlainString();
            rows.put(key, new InvoiceResponse.VatBreakdownRow(r.vatCode(), r.rate(), r.base(), r.vat(), r.mention()));
        }
        if (rows.isEmpty()) {
            return null;
        }
        if (invoice.getAdvanceDeductions() != null) {
            for (Map<String, Object> d : invoice.getAdvanceDeductions()) {
                BigDecimal base = money(d.get("base"));
                BigDecimal vat = money(d.get("vat"));
                Object code = d.get("vatCode");
                String key = code != null ? code.toString() : null;
                InvoiceResponse.VatBreakdownRow row = key == null ? null : rows.get(key);
                if (row == null) {
                    // Deducted at a code no line uses: shown as its own (negative) row.
                    row = new InvoiceResponse.VatBreakdownRow(key, rate(base, vat), BigDecimal.ZERO.setScale(2),
                            BigDecimal.ZERO.setScale(2), mentions.get(key));
                    rows.put(key == null ? "deduction:" + rows.size() : key, row);
                }
                row.setBase(row.getBase().subtract(base));
                row.setVat(row.getVat().subtract(vat));
            }
        }
        return new ArrayList<>(rows.values());
    }

    private static BigDecimal rate(BigDecimal base, BigDecimal vat) {
        return base.signum() == 0 ? BigDecimal.ZERO
                : vat.multiply(new BigDecimal("100")).divide(base, 0, RoundingMode.HALF_UP);
    }

    static BigDecimal money(Object o) {
        if (o == null) return BigDecimal.ZERO.setScale(2);
        BigDecimal d = o instanceof BigDecimal b ? b : new BigDecimal(o.toString());
        return d.setScale(2, RoundingMode.HALF_UP);
    }
}
