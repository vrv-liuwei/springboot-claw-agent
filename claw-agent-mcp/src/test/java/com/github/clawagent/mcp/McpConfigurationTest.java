package com.github.clawagent.mcp;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpConfigurationTest {
    @Test
    void importerKeepsStreamableHttpHeadersAndAutoApproveRules() {
        String json = """
                {
                  "mcpServers": {
                    "anysearch": {
                      "type": "streamable-http",
                      "url": "https://api.anysearch.com/mcp",
                      "headers": {
                        "Authorization": "Bearer ${ANYSEARCH_API_KEY}"
                      },
                      "autoApprove": ["*"]
                    }
                  }
                }
                """;

        McpServerConfig config = new McpConfigImporter().parse(json).get(0);

        assertEquals("anysearch", config.id());
        assertEquals(McpTransport.STREAMABLE_HTTP, config.transport());
        assertEquals("Bearer ${ANYSEARCH_API_KEY}", config.headers().get("Authorization"));
        assertEquals(List.of("*"), config.autoApprove());
        assertTrue(config.enabled());
    }

    @Test
    void valueResolverUsesServerEnvBeforeProcessEnvironment() {
        Map<String, String> resolved = McpValueResolver.resolveMap(
                Map.of("Authorization", "Bearer ${ANYSEARCH_API_KEY}"),
                Map.of("ANYSEARCH_API_KEY", "local-key"));

        assertEquals("Bearer local-key", resolved.get("Authorization"));
    }

    @Test
    void autoApproveSupportsWildcardToolNameAndAgentToolId() {
        McpToolDescriptor descriptor = new McpToolDescriptor("anysearch", "search", "search", Map.of());

        assertTrue(FileMcpRegistry.isAutoApproved(descriptor, List.of("*")));
        assertTrue(FileMcpRegistry.isAutoApproved(descriptor, List.of("search")));
        assertTrue(FileMcpRegistry.isAutoApproved(descriptor, List.of("mcp.anysearch.search")));
        assertTrue(FileMcpRegistry.isAutoApproved(descriptor, List.of("mcp.anysearch.*")));
        assertFalse(FileMcpRegistry.isAutoApproved(descriptor, List.of("mcp.other.*")));
    }
}
