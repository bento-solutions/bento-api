package com.bento.crm.ledger.service;

import com.bento.crm.ledger.model.LedgerAccount;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Seeds an organization's PCGE chart of accounts and its journals on its first ledger call.
 *
 * <p>Idempotent and safe under concurrent first calls: every row is inserted with
 * {@code ON CONFLICT DO NOTHING} against the partial unique index on (organization_id, code), so
 * a second concurrent caller waits for the first to commit and then inserts nothing. Organizations
 * already seeded are remembered (after commit) so later calls cost nothing.
 */
@Service
@RequiredArgsConstructor
public class LedgerSetupService {

    record Seed(String code, String name) {}

    record JournalSeed(String code, String name, String kind) {}

    static final List<Seed> ACCOUNTS = List.of(
            new Seed("1111", "Capital social"),
            new Seed("1161", "Report à nouveau (solde créditeur)"),
            new Seed("1169", "Report à nouveau (solde débiteur)"),
            new Seed("1191", "Résultat net de l'exercice (bénéficiaire)"),
            new Seed("1199", "Résultat net de l'exercice (déficitaire)"),
            new Seed("1481", "Emprunts auprès des établissements de crédit"),
            new Seed("3111", "Marchandises"),
            new Seed("3421", "Clients"),
            new Seed("3453", "Acomptes sur impôts sur les résultats"),
            new Seed("34552", "État, TVA récupérable sur charges"),
            new Seed("4411", "Fournisseurs"),
            new Seed("4417", "Fournisseurs, factures non parvenues"),
            new Seed("4455", "État, TVA facturée"),
            new Seed("4456", "État, TVA due"),
            new Seed("4497", "Comptes d'attente"),
            new Seed("5115", "Virements de fonds"),
            new Seed("5141", "Banques"),
            new Seed("5161", "Caisse"),
            new Seed("6111", "Achats de marchandises"),
            new Seed("6114", "Variation de stocks de marchandises"),
            new Seed("6125", "Achats non stockés de matières et fournitures"),
            new Seed("6131", "Locations et charges locatives"),
            new Seed("6136", "Rémunérations d'intermédiaires et honoraires"),
            new Seed("6142", "Transports"),
            new Seed("6143", "Déplacements, missions et réceptions"),
            new Seed("6144", "Publicité, publications et relations publiques"),
            new Seed("6145", "Frais postaux et frais de télécommunications"),
            new Seed("6147", "Services bancaires"),
            new Seed("6161", "Impôts et taxes directs"),
            new Seed("6171", "Rémunérations du personnel"),
            new Seed("6174", "Charges sociales"),
            new Seed("6311", "Intérêts des emprunts et dettes"),
            new Seed("7111", "Ventes de marchandises au Maroc"),
            new Seed("7124", "Ventes de services produits au Maroc"),
            new Seed("7381", "Intérêts et produits assimilés")
    );

    static final List<JournalSeed> JOURNALS = List.of(
            new JournalSeed("VT", "Ventes", "SALES"),
            new JournalSeed("AC", "Achats", "PURCHASES"),
            new JournalSeed("BQ", "Banque", "BANK"),
            new JournalSeed("CA", "Caisse", "CASH"),
            new JournalSeed("OD", "Opérations diverses", "MISC"),
            new JournalSeed("AN", "À-nouveaux", "OPENING")
    );

    private final JdbcTemplate jdbc;
    private final Set<UUID> seeded = ConcurrentHashMap.newKeySet();

    /** Makes sure the organization has its accounts and journals. Cheap once done. */
    @Transactional
    public void ensure(UUID orgId) {
        if (seeded.contains(orgId)) {
            return;
        }
        jdbc.batchUpdate("""
                INSERT INTO ledger_account (organization_id, code, name, account_class, kind, is_system)
                VALUES (?, ?, ?, ?, ?, TRUE)
                ON CONFLICT (organization_id, code) WHERE deleted_at IS NULL DO NOTHING
                """, ACCOUNTS, ACCOUNTS.size(), (ps, s) -> {
            ps.setObject(1, orgId);
            ps.setString(2, s.code());
            ps.setString(3, s.name());
            ps.setShort(4, (short) (s.code().charAt(0) - '0'));
            ps.setString(5, LedgerAccount.kindOf(s.code()).name());
        });
        jdbc.batchUpdate("""
                INSERT INTO ledger_journal (organization_id, code, name, kind)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (organization_id, code) WHERE deleted_at IS NULL DO NOTHING
                """, JOURNALS, JOURNALS.size(), (ps, j) -> {
            ps.setObject(1, orgId);
            ps.setString(2, j.code());
            ps.setString(3, j.name());
            ps.setString(4, j.kind());
        });
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    seeded.add(orgId);
                }
            });
        } else {
            seeded.add(orgId);
        }
    }
}
