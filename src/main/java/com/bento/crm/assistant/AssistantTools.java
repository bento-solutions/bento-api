package com.bento.crm.assistant;

import com.bento.crm.common.security.CurrentUser;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * What the assistant can look up. Every tool only reads, runs inside the asking user's
 * organization, and is offered only when the user holds the matching permission, so the
 * assistant never sees more than the person could open in the app. Changes are never made here:
 * the propose_* tools describe an action that the person confirms in the app.
 */
@Component
@RequiredArgsConstructor
public class AssistantTools {

    static final int MAX_ROWS = 25;

    public record Def(String name, String description, Map<String, Object> properties, List<String> required,
                      String authority, BiFunction<UUID, JsonNode, Object> run, boolean proposal) {}

    private final JdbcTemplate jdbc;

    /** The tools this user may use. */
    public List<Def> forCurrentUser() {
        return all().stream().filter(d -> d.authority() == null || CurrentUser.hasAuthority(d.authority())).toList();
    }

    private List<Def> all() {
        List<Def> d = new ArrayList<>();
        d.add(new Def("search_contacts", "Find customers, leads, prospects or suppliers by name, company, email or phone.",
                props("query", str("Words to search for"), "type", enumOf("CUSTOMER", "LEAD", "PROSPECT", "VENDOR")),
                List.of("query"), "PARTNERS_READ", this::searchContacts, false));
        d.add(new Def("contact_overview", "One contact's details with their open deals, unpaid invoices and open tasks.",
                props("contact_id", str("Contact id from search_contacts")), List.of("contact_id"), "PARTNERS_READ",
                this::contactOverview, false));
        d.add(new Def("list_invoices", "Customer invoices or supplier bills, newest first, with what is still owed.",
                props("type", enumOf("CUSTOMER", "VENDOR"), "status", enumOf("DRAFT", "SENT", "PARTIALLY_PAID", "PAID", "OVERDUE"),
                        "overdue_only", Map.of("type", "boolean"), "contact_id", str("Only this contact")),
                List.of(), "INVOICES_READ", this::listInvoices, false));
        d.add(new Def("finance_summary", "Money owed to and by the company today: receivables, payables, overdue amounts, and this month's invoicing and cash received.",
                props(), List.of(), "INVOICES_READ", (org, in) -> financeSummary(org), false));
        d.add(new Def("list_deals", "Deals with their stage and amount.",
                props("stage", str("A deal stage, e.g. OPEN, CLOSED_WON, CLOSED_LOST"), "contact_id", str("Only this contact")),
                List.of(), "DEALS_READ", this::listDeals, false));
        d.add(new Def("list_tasks", "Open tasks, soonest due first.",
                props("mine_only", Map.of("type", "boolean"), "overdue_only", Map.of("type", "boolean")),
                List.of(), "TASKS_READ", this::listTasks, false));
        d.add(new Def("stock_levels", "Quantities on hand per product and warehouse.",
                props("query", str("Product name or SKU"), "low_only", Map.of("type", "boolean")),
                List.of(), "PURCHASE_ORDERS_READ", this::stockLevels, false));
        d.add(new Def("documents_to_review", "Supplier invoices received by email or WhatsApp that wait for a person to check them.",
                props(), List.of(), "INVOICES_READ", (org, in) -> documentsToReview(org), false));
        d.add(new Def("propose_task", "Suggest creating a task. Nothing is created until the user confirms it in the app.",
                props("title", str("What to do"), "due_date", str("YYYY-MM-DD"), "contact_id", str("Related contact"),
                        "priority", enumOf("URGENT", "MEDIUM", "LOW")),
                List.of("title"), "TASKS_CREATE", (org, in) -> "Shown to the user to confirm. It is not created yet.", true));
        d.add(new Def("propose_invoice_reminders", "Suggest sending payment reminders for customer invoices. Nothing is sent until the user confirms it in the app.",
                props("invoice_ids", Map.of("type", "array", "items", Map.of("type", "string")),
                        "channel", enumOf("email", "whatsapp")),
                List.of("invoice_ids"), "INVOICES_WRITE", (org, in) -> "Shown to the user to confirm. Nothing is sent yet.", true));
        return d;
    }

    // Read-only lookups

    private Object searchContacts(UUID org, JsonNode in) {
        String q = "%" + text(in, "query").toLowerCase() + "%";
        String digits = text(in, "query").replaceAll("\\D", "");
        String phone = digits.length() >= 4 ? "%" + digits + "%" : "-";
        String type = text(in, "type");
        return jdbc.queryForList("""
                SELECT id, type, name, company_name, email, phone, city, stage
                FROM partner WHERE organization_id = ? AND deleted_at IS NULL
                  AND (lower(name) LIKE ? OR lower(coalesce(company_name, '')) LIKE ? OR lower(coalesce(email, '')) LIKE ?
                       OR regexp_replace(coalesce(phone, ''), '\\D', '', 'g') LIKE ?)
                  AND (? = '' OR type = ?)
                ORDER BY updated_at DESC LIMIT ?
                """, org, q, q, q, phone, type, type, MAX_ROWS);
    }

    private Object contactOverview(UUID org, JsonNode in) {
        UUID id = uuid(in, "contact_id");
        List<Map<String, Object>> c = jdbc.queryForList("""
                SELECT id, type, name, company_name, email, phone, city, country, stage, comments
                FROM partner WHERE organization_id = ? AND id = ? AND deleted_at IS NULL
                """, org, id);
        if (c.isEmpty()) {
            return Map.of("error", "No such contact");
        }
        Map<String, Object> out = new LinkedHashMap<>(c.get(0));
        if (CurrentUser.hasAuthority("DEALS_READ")) {
            out.put("open_deals", jdbc.queryForList("SELECT id, title, stage, amount FROM deal WHERE organization_id = ?"
                    + " AND partner_id = ? AND deleted_at IS NULL AND stage NOT IN ('CLOSED_WON', 'CLOSED_LOST', 'PAID')"
                    + " ORDER BY updated_at DESC LIMIT 10", org, id));
        }
        if (CurrentUser.hasAuthority("INVOICES_READ")) {
            out.put("unpaid_invoices", jdbc.queryForList(INVOICES + " AND i.partner_id = ? AND i.status <> 'PAID'"
                    + " AND i.status <> 'DRAFT' ORDER BY i.due_date LIMIT 10", org, id));
        }
        if (CurrentUser.hasAuthority("TASKS_READ")) {
            out.put("open_tasks", jdbc.queryForList("SELECT id, title, status, priority, due_date FROM task"
                    + " WHERE organization_id = ? AND related_entity_type = 'PARTNER' AND related_entity_id = ?"
                    + " AND deleted_at IS NULL AND status <> 'DONE' ORDER BY due_date NULLS LAST LIMIT 10", org, id));
        }
        return out;
    }

    private static final String INVOICES = """
            SELECT i.id, i.type, i.invoice_number, i.customer_name, i.status, i.invoice_date, i.due_date, i.total,
                   i.total - coalesce((SELECT sum(p.amount) FROM payment p WHERE p.invoice_id = i.id AND p.deleted_at IS NULL), 0) AS owed
            FROM invoice i WHERE i.organization_id = ? AND i.deleted_at IS NULL
            """;

    private Object listInvoices(UUID org, JsonNode in) {
        StringBuilder sql = new StringBuilder(INVOICES);
        List<Object> args = new ArrayList<>(List.of(org));
        if (!text(in, "type").isEmpty()) { sql.append(" AND i.type = ?"); args.add(text(in, "type")); }
        if (!text(in, "status").isEmpty()) { sql.append(" AND i.status = ?"); args.add(text(in, "status")); }
        if (in.path("overdue_only").asBoolean(false)) {
            sql.append(" AND i.status NOT IN ('PAID', 'DRAFT') AND i.due_date < CURRENT_DATE");
        }
        if (!text(in, "contact_id").isEmpty()) { sql.append(" AND i.partner_id = ?"); args.add(uuid(in, "contact_id")); }
        sql.append(" ORDER BY i.invoice_date DESC NULLS LAST, i.created_at DESC LIMIT ").append(MAX_ROWS);
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    private Object financeSummary(UUID org) {
        String owed = "SELECT coalesce(sum(i.total - coalesce((SELECT sum(p.amount) FROM payment p WHERE p.invoice_id = i.id"
                + " AND p.deleted_at IS NULL), 0)), 0) FROM invoice i WHERE i.organization_id = ? AND i.deleted_at IS NULL"
                + " AND i.type = ? AND i.status NOT IN ('PAID', 'DRAFT')";
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("today", LocalDate.now().toString());
        out.put("receivables", jdbc.queryForObject(owed, Object.class, org, "CUSTOMER"));
        out.put("receivables_overdue", jdbc.queryForObject(owed + " AND i.due_date < CURRENT_DATE", Object.class, org, "CUSTOMER"));
        out.put("payables", jdbc.queryForObject(owed, Object.class, org, "VENDOR"));
        out.put("payables_overdue", jdbc.queryForObject(owed + " AND i.due_date < CURRENT_DATE", Object.class, org, "VENDOR"));
        out.put("invoiced_this_month", jdbc.queryForObject("SELECT coalesce(sum(total), 0) FROM invoice WHERE organization_id = ?"
                + " AND deleted_at IS NULL AND type = 'CUSTOMER' AND status <> 'DRAFT'"
                + " AND invoice_date >= date_trunc('month', CURRENT_DATE)", Object.class, org));
        if (CurrentUser.hasAuthority("PAYMENTS_READ")) {
            out.put("received_this_month", jdbc.queryForObject("SELECT coalesce(sum(p.amount), 0) FROM payment p"
                    + " JOIN invoice i ON i.id = p.invoice_id WHERE p.organization_id = ? AND p.deleted_at IS NULL"
                    + " AND i.type = 'CUSTOMER' AND p.payment_date >= date_trunc('month', CURRENT_DATE)", Object.class, org));
        }
        out.put("currency", "MAD");
        return out;
    }

    private Object listDeals(UUID org, JsonNode in) {
        StringBuilder sql = new StringBuilder("SELECT d.id, d.title, d.stage, d.amount, d.order_number, p.name AS contact"
                + " FROM deal d LEFT JOIN partner p ON p.id = d.partner_id WHERE d.organization_id = ? AND d.deleted_at IS NULL");
        List<Object> args = new ArrayList<>(List.of(org));
        if (!text(in, "stage").isEmpty()) { sql.append(" AND d.stage = ?"); args.add(text(in, "stage").toUpperCase()); }
        if (!text(in, "contact_id").isEmpty()) { sql.append(" AND d.partner_id = ?"); args.add(uuid(in, "contact_id")); }
        sql.append(" ORDER BY d.updated_at DESC LIMIT ").append(MAX_ROWS);
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    private Object listTasks(UUID org, JsonNode in) {
        StringBuilder sql = new StringBuilder("SELECT id, title, status, priority, due_date, related_entity_type, related_entity_id"
                + " FROM task WHERE organization_id = ? AND deleted_at IS NULL AND status <> 'DONE'");
        List<Object> args = new ArrayList<>(List.of(org));
        if (in.path("mine_only").asBoolean(false) && CurrentUser.idOrNull() != null) {
            sql.append(" AND assigned_to_user_id = ?");
            args.add(CurrentUser.idOrNull());
        }
        if (in.path("overdue_only").asBoolean(false)) sql.append(" AND due_date < CURRENT_DATE");
        sql.append(" ORDER BY due_date NULLS LAST LIMIT ").append(MAX_ROWS);
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    private Object stockLevels(UUID org, JsonNode in) {
        String q = "%" + text(in, "query").toLowerCase() + "%";
        return jdbc.queryForList("""
                SELECT p.sku, p.name, w.name AS warehouse, l.quantity, p.reorder_level,
                       (p.reorder_level > 0 AND l.quantity <= p.reorder_level) AS low
                FROM stock_level l JOIN product p ON p.id = l.product_id JOIN warehouse w ON w.id = l.warehouse_id
                WHERE l.organization_id = ? AND p.deleted_at IS NULL AND (lower(p.name) LIKE ? OR lower(coalesce(p.sku, '')) LIKE ?)
                  AND (? = FALSE OR (p.reorder_level > 0 AND l.quantity <= p.reorder_level))
                ORDER BY lower(p.name) LIMIT ?
                """, org, q, q, in.path("low_only").asBoolean(false), MAX_ROWS);
    }

    private Object documentsToReview(UUID org) {
        return jdbc.queryForList("""
                SELECT d.id, f.file_name, d.sender, d.received_at, d.review_reason,
                       d.extraction->>'supplierName' AS supplier, d.extraction->>'total' AS total
                FROM inbound_document d JOIN stored_file f ON f.id = d.file_id
                WHERE d.organization_id = ? AND d.deleted_at IS NULL AND d.status = 'NEEDS_REVIEW' AND d.kind = 'VENDOR_INVOICE'
                ORDER BY d.received_at DESC LIMIT ?
                """, org, MAX_ROWS);
    }

    // Schema helpers

    private static Map<String, Object> props(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static Map<String, Object> str(String description) {
        return Map.of("type", "string", "description", description);
    }

    private static Map<String, Object> enumOf(String... values) {
        return Map.of("type", "string", "enum", List.of(values));
    }

    private static String text(JsonNode in, String f) {
        return in.path(f).isNull() ? "" : in.path(f).asText("").trim();
    }

    private static UUID uuid(JsonNode in, String f) {
        try {
            return UUID.fromString(text(in, f));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(f + " is not a valid id");
        }
    }
}
