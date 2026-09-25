package com.bento.crm.apitoken.model;

import com.bento.crm.common.model.Permission;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

/**
 * What a token may do. Each scope maps to backend authorities; a token's effective authorities
 * are those of its scopes that its owner's role also has, so a token can never do more than the
 * person who created it.
 */
public enum ApiScope {
    /** Read conversations and messages (as far as the owner may see them). */
    WHATSAPP_READ("whatsapp:read", Set.of(Permission.WHATSAPP_READ, Permission.WHATSAPP_READ_ALL)),
    /** Propose messages that a person approves in the inbox before they are sent. */
    WHATSAPP_DRAFT("whatsapp:draft", Set.of(Permission.WHATSAPP_DRAFT)),
    /** Queue messages for sending directly (still paced and capped). */
    WHATSAPP_SEND("whatsapp:send", Set.of(Permission.WHATSAPP_SEND, Permission.WHATSAPP_DRAFT)),
    /** Look up partners (leads, customers). */
    PARTNERS_READ("partners:read", Set.of(Permission.PARTNERS_READ));

    private final String value;
    private final Set<Permission> permissions;

    ApiScope(String value, Set<Permission> permissions) {
        this.value = value;
        this.permissions = permissions;
    }

    public String value() {
        return value;
    }

    public Set<Permission> permissions() {
        return permissions;
    }

    public static Optional<ApiScope> of(String value) {
        return Arrays.stream(values()).filter(s -> s.value.equals(value)).findFirst();
    }
}
