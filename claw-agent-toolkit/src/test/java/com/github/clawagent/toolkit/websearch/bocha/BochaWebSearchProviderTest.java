package com.github.clawagent.toolkit.websearch.bocha;

import com.github.clawagent.spi.WebSearchRequest;
import com.github.clawagent.spi.WebSearchResponse;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BochaWebSearchProviderTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void mapsBochaResponseToUnifiedResults() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/web-search", exchange -> {
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals("Bearer test-key", authorization);
            assertTrue(requestBody.contains("\"query\":\"Spring AI latest\""));
            byte[] body = """
                    {
                      "data": {
                        "webPages": {
                          "value": [
                            {
                              "name": "Spring AI",
                              "url": "https://spring.io/projects/spring-ai",
                              "snippet": "Spring AI project page",
                              "summary": "Spring AI latest project information",
                              "siteName": "spring.io",
                              "dateLastCrawled": "2026-05-27"
                            }
                          ]
                        }
                      }
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        BochaWebSearchProperties properties = new BochaWebSearchProperties();
        properties.setApiKey("test-key");
        properties.setEndpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/web-search");
        WebSearchResponse response = new BochaWebSearchProvider(properties).search(
                new WebSearchRequest("Spring AI latest", Map.of(
                        "count", "3",
                        "freshness", "noLimit",
                        "summary", "true",
                        "timeoutMs", "5000")));

        assertEquals("bocha", response.provider());
        assertEquals(1, response.results().size());
        assertEquals("Spring AI", response.results().get(0).title());
        assertEquals("https://spring.io/projects/spring-ai", response.results().get(0).url());
    }
}
