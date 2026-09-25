package com.bento.crm.apitoken.security;

import java.util.List;
import java.util.regex.Pattern;

/**
 * The part of the API a personal token may call. Everything else — token management, draft
 * approval, users, settings, every other module — answers 403 to a token even when the owner's
 * role could reach it: a leaked agent token must not be a full login.
 */
public final class ApiTokenPaths {

    private record Rule(String method, Pattern path) {
    }

    private static final String UUID = "[0-9a-fA-F-]{36}";

    private static final List<Rule> ALLOWED = List.of(
            new Rule("GET", Pattern.compile("^/whatsapp/conversations(/.*)?$")),
            new Rule("POST", Pattern.compile("^/whatsapp/conversations/" + UUID + "/(messages|read)$")),
            new Rule("POST", Pattern.compile("^/whatsapp/messages$")),
            new Rule("GET", Pattern.compile("^/whatsapp/messages/" + UUID + "$")),
            new Rule("DELETE", Pattern.compile("^/whatsapp/messages/" + UUID + "$")),
            new Rule("GET", Pattern.compile("^/whatsapp/unread-summary$")),
            new Rule("GET", Pattern.compile("^/partners$")),
            new Rule("GET", Pattern.compile("^/partners/" + UUID + "$")),
            new Rule("GET", Pattern.compile("^/api-tokens/me$")),
            new Rule("*", Pattern.compile("^/mcp$"))
    );

    private ApiTokenPaths() {
    }

    /** @param path the request path without the servlet context path */
    public static boolean allows(String method, String path) {
        return ALLOWED.stream().anyMatch(r -> (r.method().equals("*") || r.method().equalsIgnoreCase(method))
                && r.path().matcher(path).matches());
    }
}
