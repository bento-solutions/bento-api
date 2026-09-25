package com.bento.crm.mcp;

import com.bento.crm.apitoken.security.ApiTokenPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.transport.WebMvcStatelessServerTransport;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * Replaces the auto-configured stateless MCP transport with one that carries the caller into
 * every tool call.
 *
 * <p>TenantFilterInterceptor has already authenticated the request's API token and stored its
 * principal as a request attribute. The extractor copies it into the MCP transport context,
 * which tool methods receive as a parameter. Tools therefore never depend on ThreadLocals
 * ({@code TenantContext}, the security context), which do not follow a call that the MCP SDK
 * hands to another thread.
 */
@Configuration
public class McpConfig {

    @Bean
    public WebMvcStatelessServerTransport webMvcStatelessServerTransport(ObjectMapper objectMapper,
                                                                         McpServerStreamableHttpProperties properties) {
        return WebMvcStatelessServerTransport.builder()
                .jsonMapper(new JacksonMcpJsonMapper(objectMapper))
                .messageEndpoint(properties.getMcpEndpoint())
                .contextExtractor(request -> request.servletRequest()
                        .getAttribute(ApiTokenPrincipal.REQUEST_ATTRIBUTE) instanceof ApiTokenPrincipal principal
                        ? McpTransportContext.create(Map.of(McpCaller.CONTEXT_KEY, principal))
                        : McpTransportContext.EMPTY)
                .build();
    }
}
