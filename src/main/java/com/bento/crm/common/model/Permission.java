package com.bento.crm.common.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

@Getter
@AllArgsConstructor
public enum Permission {
    // Partner permissions
    PARTNERS_READ("PARTNERS_READ"),
    PARTNERS_CREATE("PARTNERS_CREATE"),
    PARTNERS_WRITE("PARTNERS_WRITE"),
    PARTNERS_DELETE("PARTNERS_DELETE"),

    // Deal permissions
    DEALS_READ("DEALS_READ"),
    DEALS_CREATE("DEALS_CREATE"),
    DEALS_WRITE("DEALS_WRITE"),
    DEALS_DELETE("DEALS_DELETE"),

    // Deal activity permissions
    DEAL_ACTIVITIES_READ("DEAL_ACTIVITIES_READ"),
    DEAL_ACTIVITIES_CREATE("DEAL_ACTIVITIES_CREATE"),
    DEAL_ACTIVITIES_WRITE("DEAL_ACTIVITIES_WRITE"),
    DEAL_ACTIVITIES_DELETE("DEAL_ACTIVITIES_DELETE"),

    // Proposal permissions
    PROPOSALS_READ("PROPOSALS_READ"),
    PROPOSALS_CREATE("PROPOSALS_CREATE"),
    PROPOSALS_WRITE("PROPOSALS_WRITE"),
    PROPOSALS_DELETE("PROPOSALS_DELETE"),

    // Purchase Order permissions
    PURCHASE_ORDERS_READ("PURCHASE_ORDERS_READ"),
    PURCHASE_ORDERS_CREATE("PURCHASE_ORDERS_CREATE"),
    PURCHASE_ORDERS_WRITE("PURCHASE_ORDERS_WRITE"),
    PURCHASE_ORDERS_DELETE("PURCHASE_ORDERS_DELETE"),

    // Invoice permissions
    INVOICES_READ("INVOICES_READ"),
    INVOICES_CREATE("INVOICES_CREATE"),
    INVOICES_WRITE("INVOICES_WRITE"),
    INVOICES_DELETE("INVOICES_DELETE"),

    // Payment permissions
    PAYMENTS_READ("PAYMENTS_READ"),
    PAYMENTS_CREATE("PAYMENTS_CREATE"),
    PAYMENTS_WRITE("PAYMENTS_WRITE"),
    PAYMENTS_DELETE("PAYMENTS_DELETE"),

    // Ticket permissions
    TICKETS_READ("TICKETS_READ"),
    TICKETS_CREATE("TICKETS_CREATE"),
    TICKETS_WRITE("TICKETS_WRITE"),
    TICKETS_DELETE("TICKETS_DELETE"),

    // Task permissions
    TASKS_READ("TASKS_READ"),
    TASKS_CREATE("TASKS_CREATE"),
    TASKS_WRITE("TASKS_WRITE"),
    TASKS_DELETE("TASKS_DELETE"),

    // Campaign permissions
    CAMPAIGNS_READ("CAMPAIGNS_READ"),
    CAMPAIGNS_CREATE("CAMPAIGNS_CREATE"),
    CAMPAIGNS_WRITE("CAMPAIGNS_WRITE"),
    CAMPAIGNS_DELETE("CAMPAIGNS_DELETE"),

    // Automation rule permissions
    AUTOMATION_RULES_READ("AUTOMATION_RULES_READ"),
    AUTOMATION_RULES_CREATE("AUTOMATION_RULES_CREATE"),
    AUTOMATION_RULES_WRITE("AUTOMATION_RULES_WRITE"),
    AUTOMATION_RULES_DELETE("AUTOMATION_RULES_DELETE"),

    // User & Team management
    USERS_READ("USERS_READ"),
    USERS_WRITE("USERS_WRITE"),
    TEAMS_READ("TEAMS_READ"),
    TEAMS_WRITE("TEAMS_WRITE"),
    TEAMS_CREATE("TEAMS_CREATE"),
    TEAMS_DELETE("TEAMS_DELETE"),

    // Group permissions
    GROUPS_READ("GROUPS_READ"),
    GROUPS_CREATE("GROUPS_CREATE"),
    GROUPS_WRITE("GROUPS_WRITE"),
    GROUPS_DELETE("GROUPS_DELETE"),

    // File attachment permissions. Files hang off other entities (partners, deals, tickets,
    // tasks), so there is no separate _DELETE: removing an attachment is part of write access.
    FILES_READ("FILES_READ"),
    FILES_WRITE("FILES_WRITE"),

    // Analytics
    ANALYTICS_READ("ANALYTICS_READ"),

    // WhatsApp inbox. READ sees the conversations of partners assigned to or owned by the user;
    // READ_ALL sees every conversation, including numbers not linked to any partner. DRAFT may
    // only propose a message for a human to approve; SEND puts it straight in the queue. ADMIN
    // links the number and configures pacing, lead creation and visibility.
    WHATSAPP_READ("WHATSAPP_READ"),
    WHATSAPP_READ_ALL("WHATSAPP_READ_ALL"),
    WHATSAPP_SEND("WHATSAPP_SEND"),
    WHATSAPP_DRAFT("WHATSAPP_DRAFT"),
    WHATSAPP_ADMIN("WHATSAPP_ADMIN"),

    // Personal API tokens (for AI agents and integrations).
    API_TOKENS_MANAGE("API_TOKENS_MANAGE"),

    // Admin
    ADMIN_ACCESS("ADMIN_ACCESS");

    private final String authority;

    public static Set<Permission> forRole(UserRole role) {
        return switch (role) {
            case ADMIN -> Set.of(
                    PARTNERS_READ, PARTNERS_CREATE, PARTNERS_WRITE, PARTNERS_DELETE,
                    DEALS_READ, DEALS_CREATE, DEALS_WRITE, DEALS_DELETE,
                    DEAL_ACTIVITIES_READ, DEAL_ACTIVITIES_CREATE, DEAL_ACTIVITIES_WRITE, DEAL_ACTIVITIES_DELETE,
                    PROPOSALS_READ, PROPOSALS_CREATE, PROPOSALS_WRITE, PROPOSALS_DELETE,
                    PURCHASE_ORDERS_READ, PURCHASE_ORDERS_CREATE, PURCHASE_ORDERS_WRITE, PURCHASE_ORDERS_DELETE,
                    INVOICES_READ, INVOICES_CREATE, INVOICES_WRITE, INVOICES_DELETE,
                    PAYMENTS_READ, PAYMENTS_CREATE, PAYMENTS_WRITE, PAYMENTS_DELETE,
                    TICKETS_READ, TICKETS_CREATE, TICKETS_WRITE, TICKETS_DELETE,
                    TASKS_READ, TASKS_CREATE, TASKS_WRITE, TASKS_DELETE,
                    CAMPAIGNS_READ, CAMPAIGNS_CREATE, CAMPAIGNS_WRITE, CAMPAIGNS_DELETE,
                    AUTOMATION_RULES_READ, AUTOMATION_RULES_CREATE, AUTOMATION_RULES_WRITE, AUTOMATION_RULES_DELETE,
                    USERS_READ, USERS_WRITE,
                    TEAMS_READ, TEAMS_WRITE, TEAMS_CREATE, TEAMS_DELETE,
                    GROUPS_READ, GROUPS_CREATE, GROUPS_WRITE, GROUPS_DELETE,
                    FILES_READ, FILES_WRITE,
                    ANALYTICS_READ,
                    WHATSAPP_READ, WHATSAPP_READ_ALL, WHATSAPP_SEND, WHATSAPP_DRAFT, WHATSAPP_ADMIN,
                    API_TOKENS_MANAGE,
                    ADMIN_ACCESS
            );
            case MANAGER -> Set.of(
                    PARTNERS_READ, PARTNERS_CREATE, PARTNERS_WRITE,
                    DEALS_READ, DEALS_CREATE, DEALS_WRITE,
                    DEAL_ACTIVITIES_READ, DEAL_ACTIVITIES_CREATE, DEAL_ACTIVITIES_WRITE,
                    PROPOSALS_READ, PROPOSALS_CREATE, PROPOSALS_WRITE,
                    PURCHASE_ORDERS_READ, PURCHASE_ORDERS_CREATE, PURCHASE_ORDERS_WRITE,
                    INVOICES_READ, INVOICES_CREATE, INVOICES_WRITE,
                    PAYMENTS_READ, PAYMENTS_CREATE, PAYMENTS_WRITE,
                    TICKETS_READ, TICKETS_WRITE,
                    TASKS_READ, TASKS_CREATE, TASKS_WRITE,
                    CAMPAIGNS_READ, CAMPAIGNS_CREATE, CAMPAIGNS_WRITE,
                    AUTOMATION_RULES_READ, AUTOMATION_RULES_CREATE, AUTOMATION_RULES_WRITE,
                    USERS_READ, TEAMS_READ, TEAMS_WRITE,
                    GROUPS_READ, GROUPS_CREATE, GROUPS_WRITE,
                    FILES_READ, FILES_WRITE,
                    ANALYTICS_READ,
                    WHATSAPP_READ, WHATSAPP_READ_ALL, WHATSAPP_SEND, WHATSAPP_DRAFT,
                    API_TOKENS_MANAGE
            );
            case SALESPERSON -> Set.of(
                    PARTNERS_READ, PARTNERS_CREATE, PARTNERS_WRITE,
                    DEALS_READ, DEALS_CREATE, DEALS_WRITE,
                    DEAL_ACTIVITIES_READ, DEAL_ACTIVITIES_CREATE, DEAL_ACTIVITIES_WRITE,
                    PROPOSALS_READ, PROPOSALS_CREATE, PROPOSALS_WRITE,
                    TASKS_READ, TASKS_CREATE, TASKS_WRITE,
                    GROUPS_READ,
                    FILES_READ, FILES_WRITE,
                    ANALYTICS_READ,
                    WHATSAPP_READ, WHATSAPP_SEND, WHATSAPP_DRAFT,
                    API_TOKENS_MANAGE
            );
            case SUPPORT -> Set.of(
                    PARTNERS_READ, PARTNERS_WRITE,
                    TICKETS_READ, TICKETS_CREATE, TICKETS_WRITE,
                    TASKS_READ, TASKS_CREATE, TASKS_WRITE,
                    GROUPS_READ,
                    FILES_READ, FILES_WRITE,
                    ANALYTICS_READ,
                    WHATSAPP_READ, WHATSAPP_SEND, WHATSAPP_DRAFT,
                    API_TOKENS_MANAGE
            );
            case VIEWER -> Set.of(
                    PARTNERS_READ, DEALS_READ, DEAL_ACTIVITIES_READ, PROPOSALS_READ,
                    TICKETS_READ, TASKS_READ, GROUPS_READ,
                    FILES_READ,
                    ANALYTICS_READ,
                    WHATSAPP_READ
            );
        };
    }
}
