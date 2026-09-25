package com.bento.crm.mcp;

import com.bento.crm.apitoken.security.ApiTokenPrincipal;
import com.bento.crm.whatsapp.service.WaActor;
import io.modelcontextprotocol.common.McpTransportContext;

import java.util.Arrays;

/** Resolves and checks the API token behind an MCP tool call. */
public final class McpCaller {

    static final String CONTEXT_KEY = "bento.apiTokenPrincipal";

    private McpCaller() {
    }

    /**
     * @param anyOfScopes the call is allowed if the token has at least one of these
     * @throws McpToolException when the token lacks every one of them
     */
    public static ApiTokenPrincipal require(McpTransportContext context, String... anyOfScopes) {
        Object value = context == null ? null : context.get(CONTEXT_KEY);
        if (!(value instanceof ApiTokenPrincipal principal)) {
            throw new McpToolException("Not authenticated: connect with a Bento API token (Authorization: Bearer bento_pat_…)");
        }
        if (anyOfScopes.length > 0 && Arrays.stream(anyOfScopes).noneMatch(principal::hasScope)) {
            throw new McpToolException("This API token does not have the " + String.join(" or ", anyOfScopes)
                    + " scope. Create a token with it in Bento → Settings → API tokens.");
        }
        return principal;
    }

    /** The token as an inbox actor: its owner, narrowed to the token's authorities. */
    public static WaActor actor(ApiTokenPrincipal principal) {
        return new WaActor(principal.organizationId(), principal.userId(), principal.tokenId(), principal.authorities());
    }

    /** A tool failure whose message is safe and useful to show the agent. */
    public static class McpToolException extends RuntimeException {
        public McpToolException(String message) {
            super(message);
        }
    }
}
