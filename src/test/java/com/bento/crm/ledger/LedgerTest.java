package com.bento.crm.ledger;

import com.bento.crm.common.model.Permission;
import com.bento.crm.common.model.UserRole;
import com.bento.crm.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

class LedgerTest extends IntegrationTestBase {

    @Autowired
    private JdbcTemplate jdbc;

    private JsonNode json(MockHttpServletRequestBuilder req, String token, String body, int expected) throws Exception {
        req.header("Authorization", "Bearer " + token);
        if (body != null) req.contentType(MediaType.APPLICATION_JSON).content(body);
        var res = mockMvc.perform(req).andReturn().getResponse();
        String r = res.getContentAsString();
        assertThat(res.getStatus()).as(r).isEqualTo(expected);
        return r.isEmpty() ? null : objectMapper.readTree(r);
    }

    private String detail(MockHttpServletRequestBuilder req, String token, String body, int expected) throws Exception {
        return json(req, token, body, expected).get("detail").asText();
    }

    /** code -> account id, inactive included. */
    private Map<String, String> accounts(String token) throws Exception {
        Map<String, String> ids = new HashMap<>();
        for (JsonNode a : json(get("/ledger/accounts?includeInactive=true"), token, null, 200)) {
            ids.put(a.get("code").asText(), a.get("id").asText());
        }
        return ids;
    }

    private Map<String, String> journals(String token) throws Exception {
        Map<String, String> ids = new HashMap<>();
        for (JsonNode j : json(get("/ledger/journals"), token, null, 200)) {
            ids.put(j.get("code").asText(), j.get("id").asText());
        }
        return ids;
    }

    private static String line(String accountId, String debit, String credit) {
        return "{\"accountId\": \"" + accountId + "\", \"debit\": " + debit + ", \"credit\": " + credit + "}";
    }

    private static String entry(String date, String description, String... lines) {
        return "{\"entryDate\": \"" + date + "\", \"description\": \"" + description + "\", \"lines\": ["
                + String.join(",", lines) + "]}";
    }

    private JsonNode post2(String token, String date, String description, String debitAccount, String creditAccount,
                           String amount) throws Exception {
        return json(post("/ledger/entries"), token, entry(date, description, line(debitAccount, amount, "0"),
                line(creditAccount, "0", amount)), 201);
    }

    @Test
    void chartOfAccountsAndJournalsAreSeededOnceEvenUnderConcurrentFirstCalls() throws Exception {
        String token = signUpAndLogin();
        UUID orgId = orgIdOf(token);

        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<Integer>> calls = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                String path = i % 2 == 0 ? "/ledger/accounts" : "/ledger/journals";
                Callable<Integer> call = () -> mockMvc.perform(get(path).header("Authorization", "Bearer " + token))
                        .andReturn().getResponse().getStatus();
                calls.add(pool.submit(call));
            }
            for (Future<Integer> f : calls) {
                assertThat(f.get()).isEqualTo(200);
            }
        } finally {
            pool.shutdown();
        }

        JsonNode accounts = json(get("/ledger/accounts"), token, null, 200);
        assertThat(accounts.size()).isEqualTo(70);
        assertThat(accounts.get(0).get("code").asText()).isEqualTo("1111");
        JsonNode bank = null;
        JsonNode loan = null;
        JsonNode vat = null;
        for (JsonNode a : accounts) {
            if (a.get("code").asText().equals("5141")) bank = a;
            if (a.get("code").asText().equals("1481")) loan = a;
            if (a.get("code").asText().equals("34552")) vat = a;
        }
        assertThat(bank.get("name").asText()).isEqualTo("Banques");
        assertThat(bank.get("accountClass").asInt()).isEqualTo(5);
        assertThat(bank.get("kind").asText()).isEqualTo("ASSET");
        assertThat(bank.get("system").asBoolean()).isTrue();
        assertThat(bank.get("used").asBoolean()).isFalse();
        assertThat(bank.get("balance").decimalValue()).isEqualByComparingTo("0");
        assertThat(loan.get("kind").asText()).isEqualTo("LIABILITY");
        assertThat(vat.get("name").asText()).isEqualTo("État, TVA récupérable sur charges");

        JsonNode journals = json(get("/ledger/journals"), token, null, 200);
        assertThat(journals.size()).isEqualTo(6);
        Map<String, String> kinds = new HashMap<>();
        journals.forEach(j -> kinds.put(j.get("code").asText(), j.get("kind").asText()));
        assertThat(kinds).containsEntry("VT", "SALES").containsEntry("AC", "PURCHASES").containsEntry("BQ", "BANK")
                .containsEntry("CA", "CASH").containsEntry("OD", "MISC").containsEntry("AN", "OPENING");

        // Idempotent: more calls add nothing.
        json(get("/ledger/accounts"), token, null, 200);
        json(get("/ledger/cost-centers"), token, null, 200);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_account WHERE organization_id = ?", Integer.class, orgId))
                .isEqualTo(70);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_journal WHERE organization_id = ?", Integer.class, orgId))
                .isEqualTo(6);
    }

    @Test
    void accountsAreCreatedRenamedDeactivatedAndDeletedOnlyWhenUnused() throws Exception {
        String token = signUpAndLogin();

        JsonNode created = json(post("/ledger/accounts"), token, "{\"code\": \"6250\", \"name\": \"Divers\"}", 201);
        assertThat(created.get("accountClass").asInt()).isEqualTo(6);
        assertThat(created.get("kind").asText()).isEqualTo("EXPENSE");
        assertThat(created.get("system").asBoolean()).isFalse();
        assertThat(created.get("used").asBoolean()).isFalse();
        String id = created.get("id").asText();

        assertThat(detail(post("/ledger/accounts"), token, "{\"code\": \"6250\", \"name\": \"Encore\"}", 400))
                .isEqualTo("An account with code 6250 already exists");
        assertThat(detail(post("/ledger/accounts"), token, "{\"code\": \"5141\", \"name\": \"Banque bis\"}", 400))
                .isEqualTo("An account with code 5141 already exists");
        json(post("/ledger/accounts"), token, "{\"code\": \"9123\", \"name\": \"Classe 9\"}", 400);
        json(post("/ledger/accounts"), token, "{\"code\": \"612\", \"name\": \"Trop court\"}", 400);
        json(post("/ledger/accounts"), token, "{\"code\": \"61234567890\", \"name\": \"Trop long\"}", 400);
        json(post("/ledger/accounts"), token, "{\"code\": \"61A4\", \"name\": \"Lettre\"}", 400);
        json(post("/ledger/accounts"), token, "{\"code\": \"6251\"}", 400);
        assertThat(json(post("/ledger/accounts"), token, "{\"code\": \"1611\", \"name\": \"Dette\"}", 201)
                .get("kind").asText()).isEqualTo("LIABILITY");
        assertThat(json(post("/ledger/accounts"), token, "{\"code\": \"7711\", \"name\": \"Produits\"}", 201)
                .get("kind").asText()).isEqualTo("INCOME");
        assertThat(json(post("/ledger/accounts"), token, "{\"code\": \"8811\", \"name\": \"Impôts\"}", 201)
                .get("kind").asText()).isEqualTo("EXPENSE");

        // Rename: the code never changes.
        JsonNode renamed = json(put("/ledger/accounts/" + id), token, "{\"name\": \"Frais divers\", \"code\": \"6299\"}", 200);
        assertThat(renamed.get("name").asText()).isEqualTo("Frais divers");
        assertThat(renamed.get("code").asText()).isEqualTo("6250");
        json(put("/ledger/accounts/" + id), token, "{\"name\": \"\"}", 400);

        // Deactivate: hidden by default, listed with includeInactive.
        assertThat(json(put("/ledger/accounts/" + id), token, "{\"active\": false}", 200).get("active").asBoolean()).isFalse();
        JsonNode active = json(get("/ledger/accounts"), token, null, 200);
        active.forEach(a -> assertThat(a.get("id").asText()).isNotEqualTo(id));
        assertThat(accounts(token)).containsEntry("6250", id);

        // Unused: deleted.
        json(delete("/ledger/accounts/" + id), token, null, 204);
        json(put("/ledger/accounts/" + id), token, "{\"name\": \"X\"}", 404);
        assertThat(accounts(token)).doesNotContainKey("6250");
        // The code can be reused once the account is gone.
        json(post("/ledger/accounts"), token, "{\"code\": \"6250\", \"name\": \"Divers\"}", 201);

        // System accounts are never deleted.
        Map<String, String> ids = accounts(token);
        assertThat(detail(delete("/ledger/accounts/" + ids.get("5141")), token, null, 400)).contains("deactivate it instead");

        // Used: cannot be deleted, can be deactivated, then refuses new entries.
        String used = json(post("/ledger/accounts"), token, "{\"code\": \"6252\", \"name\": \"Utilisé\"}", 201).get("id").asText();
        post2(token, "2026-02-01", "Frais", used, ids.get("5141"), "40");
        JsonNode listed = null;
        for (JsonNode a : json(get("/ledger/accounts"), token, null, 200)) {
            if (a.get("id").asText().equals(used)) listed = a;
        }
        assertThat(listed.get("used").asBoolean()).isTrue();
        assertThat(listed.get("balance").decimalValue()).isEqualByComparingTo("40");
        assertThat(detail(delete("/ledger/accounts/" + used), token, null, 400))
                .isEqualTo("This account has entries: deactivate it instead");
        json(put("/ledger/accounts/" + used), token, "{\"active\": false}", 200);
        assertThat(detail(post("/ledger/entries"), token, entry("2026-02-02", "Frais",
                line(used, "10", "0"), line(ids.get("5141"), "0", "10")), 400)).contains("6252 is inactive");
    }

    @Test
    void costCentersAreManagedAndProtectedOnceUsed() throws Exception {
        String token = signUpAndLogin();
        String client = json(post("/partners"), token, "{\"type\": \"CUSTOMER\", \"name\": \"Atlas SARL\"}", 201).get("id").asText();
        String mission = json(post("/missions"), token, """
                {"partnerId": "%s", "name": "Bilan 2026", "status": "IN_PROGRESS"}""".formatted(client), 201)
                .at("/mission/id").asText();

        JsonNode cc = json(post("/ledger/cost-centers"), token,
                "{\"code\": \"ATL\", \"name\": \"Mission Atlas\", \"missionId\": \"" + mission + "\"}", 201);
        assertThat(cc.get("active").asBoolean()).isTrue();
        assertThat(cc.get("missionId").asText()).isEqualTo(mission);
        assertThat(cc.get("missionName").asText()).isEqualTo("Bilan 2026");
        String ccId = cc.get("id").asText();

        assertThat(detail(post("/ledger/cost-centers"), token, "{\"code\": \"ATL\", \"name\": \"Doublon\"}", 400))
                .isEqualTo("A cost center with code ATL already exists");
        json(post("/ledger/cost-centers"), token, "{\"code\": \"\", \"name\": \"Vide\"}", 400);
        json(post("/ledger/cost-centers"), token, "{\"code\": \"123456789012345678901\", \"name\": \"Long\"}", 400);
        json(post("/ledger/cost-centers"), token,
                "{\"code\": \"BAD\", \"name\": \"X\", \"missionId\": \"" + UUID.randomUUID() + "\"}", 404);

        JsonNode updated = json(put("/ledger/cost-centers/" + ccId), token, "{\"name\": \"Atlas\"}", 200);
        assertThat(updated.get("name").asText()).isEqualTo("Atlas");
        assertThat(updated.get("missionId").asText()).isEqualTo(mission);
        updated = json(put("/ledger/cost-centers/" + ccId), token, "{\"missionId\": null}", 200);
        assertThat(updated.hasNonNull("missionId")).isFalse();

        String spare = json(post("/ledger/cost-centers"), token, "{\"code\": \"TMP\", \"name\": \"Temporaire\"}", 201)
                .get("id").asText();
        assertThat(json(get("/ledger/cost-centers"), token, null, 200).size()).isEqualTo(2);
        json(delete("/ledger/cost-centers/" + spare), token, null, 204);
        assertThat(json(get("/ledger/cost-centers"), token, null, 200).size()).isEqualTo(1);

        Map<String, String> ids = accounts(token);
        JsonNode e = json(post("/ledger/entries"), token, entry("2026-04-01", "Déplacement Atlas",
                "{\"accountId\": \"" + ids.get("6143") + "\", \"costCenterId\": \"" + ccId + "\", \"partnerId\": \""
                        + client + "\", \"label\": \"Taxi\", \"debit\": 150, \"credit\": 0}",
                line(ids.get("5161"), "0", "150")), 201);
        assertThat(e.at("/lines/0/costCenterCode").asText()).isEqualTo("ATL");
        assertThat(e.at("/lines/0/partnerName").asText()).isEqualTo("Atlas SARL");
        assertThat(e.at("/lines/0/label").asText()).isEqualTo("Taxi");
        assertThat(e.at("/lines/1/label").asText()).isEqualTo("Déplacement Atlas");

        assertThat(detail(delete("/ledger/cost-centers/" + ccId), token, null, 400)).contains("deactivate it instead");
        json(put("/ledger/cost-centers/" + ccId), token, "{\"active\": false}", 200);
        assertThat(json(get("/ledger/cost-centers"), token, null, 200).size()).isEqualTo(0);
        assertThat(json(get("/ledger/cost-centers?includeInactive=true"), token, null, 200).size()).isEqualTo(1);

        JsonNode filtered = json(get("/ledger/entries?costCenterId=" + ccId), token, null, 200);
        assertThat(filtered.get("total_elements").asInt()).isEqualTo(1);
    }

    @Test
    void manualEntriesArePostedNumberedAndValidated() throws Exception {
        String token = signUpAndLogin();
        Map<String, String> a = accounts(token);
        Map<String, String> j = journals(token);
        String bank = a.get("5141");
        String capital = a.get("1111");

        JsonNode e = post2(token, "2026-01-15", "Apport en capital", bank, capital, "10000.50");
        assertThat(e.get("number").asText()).isEqualTo("OD-2026-0001");
        assertThat(e.get("journalCode").asText()).isEqualTo("OD");
        assertThat(e.get("journalId").asText()).isEqualTo(j.get("OD"));
        assertThat(e.get("entryDate").asText()).isEqualTo("2026-01-15");
        assertThat(e.get("sourceType").asText()).isEqualTo("MANUAL");
        assertThat(e.get("totalDebit").decimalValue()).isEqualByComparingTo("10000.50");
        assertThat(e.get("totalCredit").decimalValue()).isEqualByComparingTo("10000.50");
        assertThat(e.get("postedBy").asText()).isEqualTo("Test Admin");
        assertThat(e.hasNonNull("createdAt")).isTrue();
        assertThat(e.get("lines").size()).isEqualTo(2);
        assertThat(e.at("/lines/0/lineNo").asInt()).isEqualTo(1);
        assertThat(e.at("/lines/0/accountCode").asText()).isEqualTo("5141");
        assertThat(e.at("/lines/0/accountName").asText()).isEqualTo("Banques");
        assertThat(e.at("/lines/1/credit").decimalValue()).isEqualByComparingTo("10000.50");

        // Numbering per journal and per year of the entry date.
        assertThat(post2(token, "2026-02-01", "Deux", bank, capital, "1").get("number").asText()).isEqualTo("OD-2026-0002");
        assertThat(post2(token, "2025-12-31", "Exercice précédent", bank, capital, "1").get("number").asText())
                .isEqualTo("OD-2025-0001");
        JsonNode opening = json(post("/ledger/entries"), token, "{\"journalId\": \"" + j.get("AN") + "\", "
                + entry("2026-01-01", "À-nouveaux", line(bank, "5", "0"), line(capital, "0", "5")).substring(1), 201);
        assertThat(opening.get("number").asText()).isEqualTo("AN-2026-0001");
        assertThat(post2(token, "2026-03-01", "Trois", bank, capital, "1").get("number").asText()).isEqualTo("OD-2026-0003");

        // Read back.
        JsonNode one = json(get("/ledger/entries/" + e.get("id").asText()), token, null, 200);
        assertThat(one.get("number").asText()).isEqualTo("OD-2026-0001");
        JsonNode page = json(get("/ledger/entries?size=2"), token, null, 200);
        assertThat(page.get("total_elements").asInt()).isEqualTo(5);
        assertThat(page.get("total_pages").asInt()).isEqualTo(3);
        assertThat(page.at("/content/0/number").asText()).isEqualTo("OD-2026-0003");
        assertThat(page.at("/content/1/number").asText()).isEqualTo("OD-2026-0002");
        assertThat(json(get("/ledger/entries?journalId=" + j.get("AN")), token, null, 200).get("total_elements").asInt())
                .isEqualTo(1);
        assertThat(json(get("/ledger/entries?from=2026-01-01&to=2026-01-31"), token, null, 200).get("total_elements").asInt())
                .isEqualTo(2);
        assertThat(json(get("/ledger/entries?q=capital"), token, null, 200).get("total_elements").asInt()).isEqualTo(1);
        assertThat(json(get("/ledger/entries?q=OD-2025"), token, null, 200).get("total_elements").asInt()).isEqualTo(1);
        assertThat(json(get("/ledger/entries?accountId=" + a.get("6131")), token, null, 200).get("total_elements").asInt())
                .isEqualTo(0);

        // Validation errors.
        assertThat(detail(post("/ledger/entries"), token, entry("2026-04-01", "Une ligne", line(bank, "10", "0")), 400))
                .isEqualTo("An entry needs at least 2 lines");
        assertThat(detail(post("/ledger/entries"), token, entry("2026-04-01", "Les deux",
                line(bank, "10", "10"), line(capital, "0", "0")), 400)).isEqualTo("Line 1: enter either a debit or a credit, not both");
        assertThat(detail(post("/ledger/entries"), token, entry("2026-04-01", "Négatif",
                line(bank, "-10", "0"), line(capital, "0", "-10")), 400)).isEqualTo("Line 1: amounts cannot be negative");
        assertThat(detail(post("/ledger/entries"), token, entry("2026-04-01", "Zéro",
                line(bank, "10", "0"), line(capital, "0", "10"), line(capital, "0", "0")), 400))
                .isEqualTo("Line 3: enter a debit or a credit");
        assertThat(detail(post("/ledger/entries"), token, entry("2026-04-01", "Déséquilibre",
                line(bank, "100", "0"), line(capital, "0", "90")), 400))
                .isEqualTo("The entry does not balance: debit 100.00, credit 90.00");
        assertThat(detail(post("/ledger/entries"), token, entry("2026-04-01", "Décimales",
                line(bank, "10.005", "0"), line(capital, "0", "10.005")), 400)).isEqualTo("Line 1: amounts have at most 2 decimals");
        json(post("/ledger/entries"), token, entry("2026-04-01", "Compte inconnu",
                line(UUID.randomUUID().toString(), "10", "0"), line(capital, "0", "10")), 404);
        json(post("/ledger/entries"), token, entry("2026-04-01", "Partenaire inconnu",
                "{\"accountId\": \"" + bank + "\", \"partnerId\": \"" + UUID.randomUUID() + "\", \"debit\": 10}",
                line(capital, "0", "10")), 404);
        json(post("/ledger/entries"), token, entry("2026-04-01", "Centre inconnu",
                "{\"accountId\": \"" + bank + "\", \"costCenterId\": \"" + UUID.randomUUID() + "\", \"debit\": 10}",
                line(capital, "0", "10")), 404);
        assertThat(detail(post("/ledger/entries"), token, "{\"journalId\": \"" + j.get("VT") + "\", "
                + entry("2026-04-01", "Ventes", line(bank, "10", "0"), line(capital, "0", "10")).substring(1), 400))
                .contains("OD or AN");
        json(post("/ledger/entries"), token, "{\"journalId\": \"" + UUID.randomUUID() + "\", "
                + entry("2026-04-01", "Journal inconnu", line(bank, "10", "0"), line(capital, "0", "10")).substring(1), 404);
        json(post("/ledger/entries"), token, "{\"description\": \"Sans date\", \"lines\": ["
                + line(bank, "10", "0") + "," + line(capital, "0", "10") + "]}", 400);
        json(post("/ledger/entries"), token, "{\"entryDate\": \"2026-04-01\", \"lines\": ["
                + line(bank, "10", "0") + "," + line(capital, "0", "10") + "]}", 400);
        json(post("/ledger/entries"), token, "{\"entryDate\": \"2026-04-01\", \"description\": \"Sans lignes\"}", 400);

        // Nothing was written by the refused entries, and no number was consumed.
        assertThat(json(get("/ledger/entries"), token, null, 200).get("total_elements").asInt()).isEqualTo(5);
        assertThat(post2(token, "2026-04-02", "Quatre", bank, capital, "1").get("number").asText()).isEqualTo("OD-2026-0004");

        // Entries are never edited or deleted: there is no such endpoint.
        assertThat(mockMvc.perform(put("/ledger/entries/" + e.get("id").asText()).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().getResponse().getStatus()).isGreaterThanOrEqualTo(400);
        assertThat(mockMvc.perform(delete("/ledger/entries/" + e.get("id").asText()).header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getStatus()).isGreaterThanOrEqualTo(400);
    }

    @Test
    void anEntryIsReversedOnceAndAReversalIsNeverReversed() throws Exception {
        String token = signUpAndLogin();
        Map<String, String> a = accounts(token);
        JsonNode original = json(post("/ledger/entries"), token, entry("2026-05-10", "Loyer mai",
                line(a.get("6131"), "4000", "0"), line(a.get("34552"), "800", "0"), line(a.get("4411"), "0", "4800")), 201);
        String id = original.get("id").asText();

        JsonNode reversal = json(post("/ledger/entries/" + id + "/reverse"), token,
                "{\"entryDate\": \"2026-05-31\"}", 201);
        assertThat(reversal.get("number").asText()).isEqualTo("OD-2026-0002");
        assertThat(reversal.get("journalCode").asText()).isEqualTo("OD");
        assertThat(reversal.get("entryDate").asText()).isEqualTo("2026-05-31");
        assertThat(reversal.get("reversalOfId").asText()).isEqualTo(id);
        assertThat(reversal.get("description").asText()).contains("OD-2026-0001");
        assertThat(reversal.at("/lines/0/accountCode").asText()).isEqualTo("6131");
        assertThat(reversal.at("/lines/0/debit").decimalValue()).isEqualByComparingTo("0");
        assertThat(reversal.at("/lines/0/credit").decimalValue()).isEqualByComparingTo("4000");
        assertThat(reversal.at("/lines/2/debit").decimalValue()).isEqualByComparingTo("4800");
        assertThat(reversal.get("totalDebit").decimalValue()).isEqualByComparingTo("4800");

        JsonNode reread = json(get("/ledger/entries/" + id), token, null, 200);
        assertThat(reread.get("reversedById").asText()).isEqualTo(reversal.get("id").asText());

        assertThat(detail(post("/ledger/entries/" + id + "/reverse"), token, "{}", 400))
                .isEqualTo("This entry has already been reversed");
        assertThat(detail(post("/ledger/entries/" + reversal.get("id").asText() + "/reverse"), token, null, 400))
                .isEqualTo("A reversal cannot itself be reversed");
        json(post("/ledger/entries/" + UUID.randomUUID() + "/reverse"), token, "{}", 404);

        // Original + reversal leave every account at zero.
        for (JsonNode acc : json(get("/ledger/accounts"), token, null, 200)) {
            assertThat(acc.get("balance").decimalValue()).isEqualByComparingTo("0");
        }

        // A reversal with a custom description and the default date (today).
        JsonNode other = post2(token, "2026-06-01", "Erreur", a.get("6142"), a.get("5161"), "75");
        JsonNode r2 = json(post("/ledger/entries/" + other.get("id").asText() + "/reverse"), token,
                "{\"description\": \"Annulation\"}", 201);
        assertThat(r2.get("description").asText()).isEqualTo("Annulation");
        assertThat(r2.get("entryDate").asText()).isEqualTo(java.time.LocalDate.now().toString());
        assertThat(r2.get("sourceLabel").asText()).isEqualTo(other.get("number").asText());
    }

    @Test
    void generalLedgerRunsTheBalanceFromTheOpeningAndTrialBalanceBalances() throws Exception {
        String token = signUpAndLogin();
        Map<String, String> a = accounts(token);
        String bank = a.get("5141");
        post2(token, "2026-01-15", "Capital", bank, a.get("1111"), "1000");
        post2(token, "2026-03-10", "Loyer", a.get("6131"), bank, "300");
        post2(token, "2026-03-20", "Honoraires encaissés", bank, a.get("7124"), "500");
        post2(token, "2026-04-05", "Hors période", a.get("6142"), bank, "20");

        JsonNode gl = json(get("/ledger/general-ledger?from=2026-03-01&to=2026-03-31&accountId=" + bank), token, null, 200);
        assertThat(gl.get("from").asText()).isEqualTo("2026-03-01");
        assertThat(gl.get("to").asText()).isEqualTo("2026-03-31");
        assertThat(gl.get("accounts").size()).isEqualTo(1);
        JsonNode acc = gl.at("/accounts/0");
        assertThat(acc.get("code").asText()).isEqualTo("5141");
        assertThat(acc.get("name").asText()).isEqualTo("Banques");
        assertThat(acc.get("openingBalance").decimalValue()).isEqualByComparingTo("1000");
        assertThat(acc.get("lines").size()).isEqualTo(2);
        assertThat(acc.at("/lines/0/entryNumber").asText()).isEqualTo("OD-2026-0002");
        assertThat(acc.at("/lines/0/journalCode").asText()).isEqualTo("OD");
        assertThat(acc.at("/lines/0/label").asText()).isEqualTo("Loyer");
        assertThat(acc.at("/lines/0/credit").decimalValue()).isEqualByComparingTo("300");
        assertThat(acc.at("/lines/0/balance").decimalValue()).isEqualByComparingTo("700");
        assertThat(acc.at("/lines/1/balance").decimalValue()).isEqualByComparingTo("1200");
        assertThat(acc.get("totalDebit").decimalValue()).isEqualByComparingTo("500");
        assertThat(acc.get("totalCredit").decimalValue()).isEqualByComparingTo("300");
        assertThat(acc.get("closingBalance").decimalValue()).isEqualByComparingTo("1200");

        // Every account with an opening or movements, sorted by code; code-prefix range.
        JsonNode all = json(get("/ledger/general-ledger?from=2026-03-01&to=2026-03-31"), token, null, 200);
        List<String> codes = new ArrayList<>();
        all.get("accounts").forEach(x -> codes.add(x.get("code").asText()));
        assertThat(codes).containsExactly("1111", "5141", "6131", "7124");
        assertThat(all.at("/accounts/0/openingBalance").decimalValue()).isEqualByComparingTo("-1000");
        assertThat(all.at("/accounts/0/lines").size()).isEqualTo(0);
        assertThat(all.at("/accounts/0/closingBalance").decimalValue()).isEqualByComparingTo("-1000");
        JsonNode range = json(get("/ledger/general-ledger?from=2026-01-01&to=2026-12-31&accountFrom=6&accountTo=6"),
                token, null, 200);
        codes.clear();
        range.get("accounts").forEach(x -> codes.add(x.get("code").asText()));
        assertThat(codes).containsExactly("6131", "6142");

        JsonNode tb = json(get("/ledger/trial-balance?from=2026-03-01&to=2026-03-31"), token, null, 200);
        assertThat(tb.get("rows").size()).isEqualTo(4);
        JsonNode capital = tb.at("/rows/0");
        assertThat(capital.get("code").asText()).isEqualTo("1111");
        assertThat(capital.get("accountClass").asInt()).isEqualTo(1);
        assertThat(capital.get("openingDebit").decimalValue()).isEqualByComparingTo("0");
        assertThat(capital.get("openingCredit").decimalValue()).isEqualByComparingTo("1000");
        assertThat(capital.get("closingCredit").decimalValue()).isEqualByComparingTo("1000");
        JsonNode bankRow = tb.at("/rows/1");
        assertThat(bankRow.get("openingDebit").decimalValue()).isEqualByComparingTo("1000");
        assertThat(bankRow.get("periodDebit").decimalValue()).isEqualByComparingTo("500");
        assertThat(bankRow.get("periodCredit").decimalValue()).isEqualByComparingTo("300");
        assertThat(bankRow.get("closingDebit").decimalValue()).isEqualByComparingTo("1200");
        assertThat(bankRow.get("closingCredit").decimalValue()).isEqualByComparingTo("0");
        JsonNode t = tb.get("totals");
        assertThat(t.get("openingDebit").decimalValue()).isEqualByComparingTo("1000")
                .isEqualByComparingTo(t.get("openingCredit").decimalValue());
        assertThat(t.get("periodDebit").decimalValue()).isEqualByComparingTo("800")
                .isEqualByComparingTo(t.get("periodCredit").decimalValue());
        assertThat(t.get("closingDebit").decimalValue()).isEqualByComparingTo("1500")
                .isEqualByComparingTo(t.get("closingCredit").decimalValue());

        JsonNode class6 = json(get("/ledger/trial-balance?from=2026-01-01&to=2026-12-31&accountClass=6"), token, null, 200);
        assertThat(class6.get("rows").size()).isEqualTo(2);
        json(get("/ledger/trial-balance?accountClass=9"), token, null, 400);
        json(get("/ledger/trial-balance?from=2026-05-01&to=2026-04-01"), token, null, 400);

        // Defaults: the current fiscal year to date.
        JsonNode defaults = json(get("/ledger/trial-balance"), token, null, 200);
        assertThat(defaults.get("to").asText()).isEqualTo(java.time.LocalDate.now().toString());
        assertThat(defaults.get("from").asText()).endsWith("-01");
    }

    @Test
    void anotherOrganizationCannotSeeOrPostToTheseAccounts() throws Exception {
        String tokenA = signUpAndLogin();
        String tokenB = signUpAndLogin();
        Map<String, String> a = accounts(tokenA);
        String own = json(post("/ledger/accounts"), tokenA, "{\"code\": \"6250\", \"name\": \"Divers\"}", 201).get("id").asText();
        String cc = json(post("/ledger/cost-centers"), tokenA, "{\"code\": \"A1\", \"name\": \"A\"}", 201).get("id").asText();
        JsonNode e = post2(tokenA, "2026-02-01", "A", a.get("5141"), a.get("1111"), "100");
        String entryId = e.get("id").asText();
        Map<String, String> b = accounts(tokenB);

        assertThat(b.values()).doesNotContain(own, a.get("5141"));
        assertThat(json(get("/ledger/entries"), tokenB, null, 200).get("total_elements").asInt()).isEqualTo(0);
        json(get("/ledger/entries/" + entryId), tokenB, null, 404);
        json(post("/ledger/entries/" + entryId + "/reverse"), tokenB, "{}", 404);
        json(put("/ledger/accounts/" + own), tokenB, "{\"name\": \"Pris\"}", 404);
        json(delete("/ledger/accounts/" + own), tokenB, null, 404);
        json(put("/ledger/cost-centers/" + cc), tokenB, "{\"name\": \"Pris\"}", 404);
        json(delete("/ledger/cost-centers/" + cc), tokenB, null, 404);
        json(post("/ledger/entries"), tokenB, entry("2026-02-01", "Vol",
                line(a.get("5141"), "100", "0"), line(b.get("1111"), "0", "100")), 404);
        json(post("/ledger/entries"), tokenB, entry("2026-02-01", "Vol",
                "{\"accountId\": \"" + b.get("5141") + "\", \"costCenterId\": \"" + cc + "\", \"debit\": 100}",
                line(b.get("1111"), "0", "100")), 404);
        String journalA = journals(tokenA).get("OD");
        json(post("/ledger/entries"), tokenB, "{\"journalId\": \"" + journalA + "\", "
                + entry("2026-02-01", "Vol", line(b.get("5141"), "100", "0"), line(b.get("1111"), "0", "100")).substring(1), 404);
        assertThat(json(get("/ledger/trial-balance?from=2026-01-01&to=2026-12-31"), tokenB, null, 200).get("rows").size())
                .isEqualTo(0);
        assertThat(json(get("/ledger/general-ledger?from=2026-01-01&to=2026-12-31&accountId=" + a.get("5141")), tokenB,
                null, 200).get("accounts").size()).isEqualTo(0);
        // Organization B's own numbering starts at 1.
        assertThat(post2(tokenB, "2026-02-01", "B", b.get("5141"), b.get("1111"), "1").get("number").asText())
                .isEqualTo("OD-2026-0001");
    }

    @Test
    void onlyAdminsAndManagersReachTheLedger() throws Exception {
        String admin = signUpAndLogin();
        UUID orgId = orgIdOf(admin);
        String viewer = tokenForNewUser(orgId, UserRole.VIEWER);
        String sales = tokenForNewUser(orgId, UserRole.SALESPERSON);
        String manager = tokenForNewUser(orgId, UserRole.MANAGER);
        Map<String, String> a = accounts(admin);

        for (String token : List.of(viewer, sales)) {
            json(get("/ledger/accounts"), token, null, 403);
            json(get("/ledger/journals"), token, null, 403);
            json(get("/ledger/entries"), token, null, 403);
            json(get("/ledger/trial-balance"), token, null, 403);
            json(get("/ledger/general-ledger"), token, null, 403);
            json(get("/ledger/cost-centers"), token, null, 403);
            json(post("/ledger/accounts"), token, "{\"code\": \"6250\", \"name\": \"X\"}", 403);
            json(post("/ledger/entries"), token, entry("2026-02-01", "X",
                    line(a.get("5141"), "1", "0"), line(a.get("1111"), "0", "1")), 403);
        }

        json(get("/ledger/accounts"), manager, null, 200);
        JsonNode e = post2(manager, "2026-02-01", "Par le manager", a.get("5141"), a.get("1111"), "1");
        assertThat(e.get("postedBy").asText()).isEqualTo("Test MANAGER");
        json(post("/ledger/entries/" + e.get("id").asText() + "/reverse"), manager, "{}", 201);

        assertThat(Permission.forRole(UserRole.ADMIN))
                .contains(Permission.LEDGER_READ, Permission.LEDGER_WRITE, Permission.LEDGER_CLOSE);
        assertThat(Permission.forRole(UserRole.MANAGER))
                .contains(Permission.LEDGER_READ, Permission.LEDGER_WRITE).doesNotContain(Permission.LEDGER_CLOSE);
        for (UserRole role : List.of(UserRole.SALESPERSON, UserRole.SUPPORT, UserRole.VIEWER)) {
            assertThat(Permission.forRole(role))
                    .doesNotContain(Permission.LEDGER_READ, Permission.LEDGER_WRITE, Permission.LEDGER_CLOSE);
        }
    }
}
