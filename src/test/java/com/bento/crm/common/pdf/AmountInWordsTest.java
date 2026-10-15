package com.bento.crm.common.pdf;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class AmountInWordsTest {

    @Test
    void writesFrenchNumbers() {
        assertThat(AmountInWords.words(0)).isEqualTo("zéro");
        assertThat(AmountInWords.words(21)).isEqualTo("vingt et un");
        assertThat(AmountInWords.words(71)).isEqualTo("soixante et onze");
        assertThat(AmountInWords.words(80)).isEqualTo("quatre-vingts");
        assertThat(AmountInWords.words(81)).isEqualTo("quatre-vingt-un");
        assertThat(AmountInWords.words(99)).isEqualTo("quatre-vingt-dix-neuf");
        assertThat(AmountInWords.words(200)).isEqualTo("deux cents");
        assertThat(AmountInWords.words(1000)).isEqualTo("mille");
        assertThat(AmountInWords.words(280_000)).isEqualTo("deux cent quatre-vingt mille");
        assertThat(AmountInWords.words(2_000_000)).isEqualTo("deux millions");
    }

    @Test
    void writesDirhamsAndCentimes() {
        assertThat(AmountInWords.dirhams(new BigDecimal("1250.50")))
                .isEqualTo("mille deux cent cinquante dirhams et cinquante centimes");
        assertThat(AmountInWords.dirhams(new BigDecimal("1"))).isEqualTo("un dirham");
        assertThat(AmountInWords.dirhams(new BigDecimal("12000.00"))).isEqualTo("douze mille dirhams");
    }
}
