package com.bento.crm.product.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductTaxRateTest {

    @Test
    void percentagesAndFractionsBothStoreAsAFraction() {
        assertThat(ProductService.normalizeTaxRate(new BigDecimal("20"))).isEqualByComparingTo("0.2");
        assertThat(ProductService.normalizeTaxRate(new BigDecimal("0.2"))).isEqualByComparingTo("0.2");
        assertThat(ProductService.normalizeTaxRate(new BigDecimal("5.5"))).isEqualByComparingTo("0.055");
        assertThat(ProductService.normalizeTaxRate(BigDecimal.ZERO)).isEqualByComparingTo("0");
    }

    @Test
    void ratesOutsideZeroToHundredPercentAreRejected() {
        assertThatThrownBy(() -> ProductService.normalizeTaxRate(new BigDecimal("120")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("VAT rate");
        assertThatThrownBy(() -> ProductService.normalizeTaxRate(new BigDecimal("-0.1")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
