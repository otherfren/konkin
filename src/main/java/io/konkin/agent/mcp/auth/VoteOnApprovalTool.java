/*
 * Copyright 2026 Peter Geschel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.konkin.agent.mcp.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.konkin.config.CoinConfig;
import io.konkin.config.KonkinConfig;
import io.konkin.db.ApprovalRequestRepository;
import io.konkin.db.ChannelRepository;
import io.konkin.db.VoteService;
import io.konkin.db.entity.ApprovalChannelRow;
import io.konkin.db.entity.ApprovalRequestRow;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class VoteOnApprovalTool {

    private static final Logger log = LoggerFactory.getLogger(VoteOnApprovalTool.class);

    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private VoteOnApprovalTool() {
    }

    public static SyncToolSpecification create(
            String agentName,
            ApprovalRequestRepository requestRepo,
            VoteService voteService,
            ChannelRepository channelRepo,
            KonkinConfig runtimeConfig
    ) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("requestId", Map.of("type", "string", "description", "The approval request ID to vote on"));
        properties.put("decision", Map.of("type", "string", "enum", List.of("approve", "deny"), "description", "Vote decision"));
        properties.put("reason", Map.of("type", "string", "description", "Optional reason for the vote"));

        McpSchema.Tool tool = new McpSchema.Tool(
                "vote_on_approval",
                null,
                "Cast an approve or deny vote on a pending approval request.",
                new McpSchema.JsonSchema("object", properties, List.of("requestId", "decision"), null, null, null),
                null, null, null
        );

        return new SyncToolSpecification(tool, (exchange, request) -> {
            try {
                Map<String, Object> args = request.arguments();
                String requestId = requireNonBlank(argString(args, "requestId"), "requestId is required");
                String decision = requireNonBlank(argString(args, "decision"), "decision is required").trim().toLowerCase();
                String reason = optionalTrim(argString(args, "reason"));

                if (!"approve".equals(decision) && !"deny".equals(decision)) {
                    return errorResult("invalid_decision", "decision must be 'approve' or 'deny'");
                }

                // Pre-check: agent must be assigned to the coin (read-only, before locking)
                ApprovalRequestRow requestRow = requestRepo.findApprovalRequestById(requestId);
                if (requestRow == null) {
                    return errorResult("request_not_found_or_resolved",
                            "Request not found or already resolved");
                }

                if (!isAgentAssignedToCoin(agentName, requestRow.coin(), runtimeConfig)) {
                    return errorResult("agent_not_assigned_to_coin",
                            "This agent is not assigned to the coin '" + requestRow.coin() + "'");
                }

                // Ensure channel row exists (idempotent, outside the vote transaction)
                String channelId = ensureAuthChannelId(agentName, channelRepo);

                // Resolve veto channels for this coin
                List<String> vetoChannels = resolveVetoChannels(requestRow.coin(), runtimeConfig);

                // Cast vote transactionally (locks the request row, prevents race conditions)
                VoteService.VoteResult result = voteService.castVote(
                        requestId, channelId, decision, reason, agentName,
                        "agent", agentName, vetoChannels
                );

                if (!result.success()) {
                    return errorResult(result.error(), result.errorMessage());
                }

                String json = toJson(Map.of("status", "accepted", "requestId", requestId, "decision", decision));
                return new CallToolResult(List.of(new TextContent(json)), false, null, null);
            } catch (IllegalArgumentException e) {
                return errorResult("validation_error", e.getMessage());
            } catch (Exception e) {
                log.error("vote_on_approval unexpected error: {}", e.getMessage(), e);
                return errorResult("internal_error", "Unexpected error in vote_on_approval: " + e.getMessage());
            }
        });
    }

    static List<String> resolveVetoChannels(String coin, KonkinConfig runtimeConfig) {
        if (coin == null || runtimeConfig == null) return List.of();
        String normalized = coin.trim().toLowerCase();
        CoinConfig coinConfig = switch (normalized) {
            case "bitcoin" -> runtimeConfig.bitcoin();
            case "litecoin" -> runtimeConfig.litecoin();
            case "monero" -> runtimeConfig.monero();
            case "testdummycoin" -> runtimeConfig.testDummyCoin();
            default -> null;
        };
        if (coinConfig == null || coinConfig.auth() == null || coinConfig.auth().vetoChannels() == null) {
            return List.of();
        }
        return coinConfig.auth().vetoChannels();
    }

    static boolean isAgentAssignedToCoin(String agentName, String coin, KonkinConfig runtimeConfig) {
        if (coin == null || runtimeConfig == null) return false;
        String normalized = coin.trim().toLowerCase();
        if (normalized.isEmpty()) return false;
        return switch (normalized) {
            case "bitcoin" -> isAgentAssigned(agentName, runtimeConfig.bitcoin());
            case "litecoin" -> isAgentAssigned(agentName, runtimeConfig.litecoin());
            case "monero" -> isAgentAssigned(agentName, runtimeConfig.monero());
            case "testdummycoin" -> isAgentAssigned(agentName, runtimeConfig.testDummyCoin());
            default -> false;
        };
    }

    private static boolean isAgentAssigned(String agentName, CoinConfig coinConfig) {
        if (coinConfig == null || coinConfig.auth() == null || !coinConfig.enabled()) return false;
        for (String channel : coinConfig.auth().mcpAuthChannels()) {
            if (channel != null && channel.equalsIgnoreCase(agentName)) return true;
        }
        return false;
    }

    static String ensureAuthChannelId(String agentName, ChannelRepository channelRepo) {
        ApprovalChannelRow existing = channelRepo.findChannelById(agentName);
        if (existing != null) return existing.id();

        try {
            channelRepo.insertChannel(new ApprovalChannelRow(
                    agentName, "mcp_agent", agentName, true, "agent-endpoint", Instant.now()
            ));
        } catch (RuntimeException ignored) {
        }

        ApprovalChannelRow reloaded = channelRepo.findChannelById(agentName);
        if (reloaded == null) {
            throw new IllegalStateException("Failed to resolve approval channel for auth agent: " + agentName);
        }
        return reloaded.id();
    }

    private static CallToolResult errorResult(String error, String message) {
        String json = toJson(Map.of("error", error, "message", message));
        return new CallToolResult(List.of(new TextContent(json)), true, null, null);
    }

    private static String requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(message);
        return value.trim();
    }

    private static String optionalTrim(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String argString(Map<String, Object> args, String key) {
        Object value = args == null ? null : args.get(key);
        return value == null ? null : value.toString();
    }

    private static String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize to JSON", e);
        }
    }
}