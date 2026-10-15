package com.bento.crm.bank.statement;

import com.bento.crm.bank.model.BankAccount;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * A statement as read from a file, before it is matched to an account.
 *
 * @param bank          the bank the file seems to come from, or null
 * @param accountNumber digits of the RIB or account number found in the file, or null
 */
public record ParsedStatement(BankAccount.Bank bank, String accountNumber, LocalDate periodStart, LocalDate periodEnd,
                              BigDecimal openingBalance, BigDecimal closingBalance, List<Row> rows, String parser) {

    /** One movement. {@code amount} is signed: positive in, negative out. */
    public record Row(LocalDate bookingDate, LocalDate valueDate, String description, String reference, BigDecimal amount,
                      BigDecimal balance) {}
}
