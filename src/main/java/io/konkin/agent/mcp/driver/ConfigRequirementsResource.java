package io.konkin.agent.mcp.driver;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.konkin.agent.primary.PrimaryAgentConfigRequirementsService;
import io.modelcontextprotocol.server.McpServerFeatures.SyncResourceSpecification;
import io.modelcontextprotocol.server.McpServerFeatures.SyncResourceTemplateSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;

import java.util.List;

public final class ConfigRequirementsResource {

    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private ConfigRequirementsResource() {
    }

    public static SyncResourceSpecification serverResource(PrimaryAgentConfigRequirementsService service) {
        return new SyncResourceSpecification(
                new McpSchema.Resource(
                        "konkin://runtime/config/requirements",
                        "config-requirements",
                        null,
                        "Server-level runtime config readiness evaluation",
                        "application/json",
                        null, null, null
                ),
                (exchange, request) -> {
                    var response = service.evaluate(null);
                    return new ReadResourceResult(List.of(
                            new TextResourceContents(request.uri(), "application/json", toJson(response))
                    ));
                }
        );
    }

    public static SyncResourceTemplateSpecification coinTemplate(PrimaryAgentConfigRequirementsService service) {
        return new SyncResourceTemplateSpecification(
                new McpSchema.ResourceTemplate(
                        "konkin://runtime/config/requirements/{coin}",
                        "config-requirements-coin",
                        null,
                        "Coin-specific runtime config readiness evaluation",
                        "application/json",
                        null
                ),
                (exchange, request) -> {
                    String coin = extractCoinFromUri(request.uri());
                    var response = service.evaluate(coin);
                    return new ReadResourceResult(List.of(
                            new TextResourceContents(request.uri(), "application/json", toJson(response))
                    ));
                }
        );
    }

    private static String extractCoinFromUri(String uri) {
        // URI format: konkin://runtime/config/requirements/{coin}
        String prefix = "konkin://runtime/config/requirements/";
        if (uri != null && uri.startsWith(prefix) && uri.length() > prefix.length()) {
            return uri.substring(prefix.length()).trim().toLowerCase();
        }
        return null;
    }

    private static String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize to JSON", e);
        }
    }
}
