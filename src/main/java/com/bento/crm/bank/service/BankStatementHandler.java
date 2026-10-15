package com.bento.crm.bank.service;

import com.bento.crm.ai.ClaudeClient;
import com.bento.crm.bank.model.BankAccount;
import com.bento.crm.bank.model.BankImport;
import com.bento.crm.bank.statement.ParsedStatement;
import com.bento.crm.bank.statement.StatementReader;
import com.bento.crm.inbound.model.InboundDocument;
import com.bento.crm.inbound.repository.InboundDocumentRepository;
import com.bento.crm.inbound.service.InboundDocumentHandler;
import com.bento.crm.notification.service.TeamNotifier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Statements that arrive in the statements inbox (or are dropped in by hand) are read and filed
 * into the bank account whose number they carry. A statement for an unknown account, or one that
 * cannot be read, waits in the review queue.
 */
@Component
@RequiredArgsConstructor
public class BankStatementHandler implements InboundDocumentHandler {

    private final StatementReader reader;
    private final BankImportService importService;
    private final InboundDocumentRepository documentRepository;
    private final TeamNotifier teamNotifier;

    @Override
    public boolean handles(InboundDocument.Kind kind) {
        return kind == InboundDocument.Kind.BANK_STATEMENT;
    }

    @Override
    public void handle(InboundDocument doc, byte[] content, String contentType, String fileName) {
        InboundDocument d = documentRepository.findById(doc.getId()).orElseThrow();
        ParsedStatement s;
        StatementReader.Format format;
        try {
            format = StatementReader.format(contentType, fileName);
            s = reader.read(content, contentType, fileName);
        } catch (ClaudeClient.AiException e) {
            throw e; // temporary: retried by the processor
        } catch (IllegalArgumentException | IllegalStateException e) {
            review(d, e.getMessage());
            return;
        }
        Optional<BankAccount> account = importService.matchAccount(d.getOrganizationId(), s.accountNumber());
        Map<String, Object> found = new LinkedHashMap<>();
        found.put("bank", s.bank() == null ? null : s.bank().name());
        found.put("accountNumber", s.accountNumber());
        found.put("periodStart", s.periodStart() == null ? null : s.periodStart().toString());
        found.put("periodEnd", s.periodEnd() == null ? null : s.periodEnd().toString());
        found.put("rows", s.rows().size());
        if (account.isEmpty()) {
            d.setExtraction(found);
            review(d, s.accountNumber() == null
                    ? "No account number was found on the statement: choose the account"
                    : "No bank account has the number " + s.accountNumber() + ": add it or choose the account");
            return;
        }
        BankImport imp = importService.importInto(d.getOrganizationId(), account.get(), s, d.getFileId(), d.getId(),
                format.name().toLowerCase());
        found.put("importId", imp.getId().toString());
        found.put("bankAccountId", account.get().getId().toString());
        found.put("rowsImported", imp.getRowsImported());
        found.put("rowsSkipped", imp.getRowsSkipped());
        found.put("balanceMismatch", imp.isBalanceMismatch());
        d.setExtraction(found);
        d.setStatus(InboundDocument.Status.FILED);
        d.setReviewReason(imp.isBalanceMismatch() ? "The statement's balances do not add up: check the lines" : null);
        d.setError(null);
        d.setNextAttemptAt(null);
        d.setProcessedAt(Instant.now());
        documentRepository.save(d);
    }

    private void review(InboundDocument d, String reason) {
        d.setStatus(InboundDocument.Status.NEEDS_REVIEW);
        d.setReviewReason(reason.length() > 255 ? reason.substring(0, 252) + "..." : reason);
        d.setNextAttemptAt(null);
        d.setProcessedAt(Instant.now());
        documentRepository.save(d);
        teamNotifier.notifyManagers(d.getOrganizationId(), "Bank statement to check", d.getReviewReason(),
                "INBOUND_DOCUMENT", d.getId());
    }
}
