package com.bento.crm.bank.statement;

import com.bento.crm.bank.model.BankAccount;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PdfStatementReaderTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void readsTheModelsAnswerIntoSignedRows() throws Exception {
        ParsedStatement s = PdfStatementReader.parse(mapper.readTree("""
                {"bank_name": "CIH Bank", "account_number": "230 780 0005555555555555 66",
                 "period_start": "2026-09-01", "period_end": null, "opening_balance": -150.5, "closing_balance": 849.5,
                 "rows": [
                   {"booking_date": "2026-09-02", "value_date": null, "description": "VIR RECU", "reference": null,
                    "debit": null, "credit": 1200, "balance": null},
                   {"booking_date": "2026-09-05", "value_date": "2026-09-05", "description": "AGIOS", "reference": "X1",
                    "debit": "200,00", "credit": null, "balance": null},
                   {"booking_date": null, "value_date": null, "description": "REPORT", "reference": null,
                    "debit": null, "credit": 5, "balance": null}
                 ]}"""));
        assertThat(s.bank()).isEqualTo(BankAccount.Bank.CIH);
        assertThat(s.accountNumber()).isEqualTo("230780000555555555555566");
        assertThat(s.rows()).hasSize(2);
        assertThat(s.rows().get(0).amount()).isEqualByComparingTo("1200");
        assertThat(s.rows().get(1).amount()).isEqualByComparingTo("-200");
        assertThat(s.periodEnd()).isEqualTo(LocalDate.of(2026, 9, 5));
        assertThat(s.parser()).isEqualTo("ai:cih");
    }

    @Test
    void anAnswerWithoutMovementsIsRefused() {
        assertThatThrownBy(() -> PdfStatementReader.parse(mapper.readTree("{\"rows\": []}")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
