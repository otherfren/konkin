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
import io.konkin.config.KonkinConfig;
import io.konkin.db.ApprovalRequestRepository;
import io.konkin.db.ChannelRepository;
import io.konkin.db.VoteRepository;
import io.konkin.db.entity.ApprovalRequestRow;
import io.konkin.db.entity.VoteDetail;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * MCP tool that lists all pending approval requests the auth agent is eligible to vote on.
 *
 * <p>Eligible means:
 * <ul>
 *   <li>The request is in QUEUED or PENDING state</li>
 *   <li>The request's coin is assigned to this agent</li>
 *   <li>The agent has not already voted on the request</li>
 *   <li>The request has not expired</li>
 * </ul>
 */
public final class ListEligibleRequestsTool {

    private static final Logger log = LoggerFactory.getLogger(ListEligibleRequestsTool.class);

    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private ListEligibleRequestsTool() {
    }

    public static SyncToolSpecification create(
            String agentName,
            ApprovalRequestRepository requestRepo,
            VoteRepository voteRepo,
            ChannelRepository channelRepo,
            KonkinConfig runtimeConfig
    ) {
        McpSchema.Tool tool = new McpSchema.Tool(
                "list_eligible_requests",
                null,
                "List all pending approval requests that this auth agent is eligible to vote on. "
                        + "Returns requests assigned to the agent's coins that have not been voted on yet.",
                new McpSchema.JsonSchema("object", Map.of(), List.of(), null, null, null),
                null, null, null
        );

        return new SyncToolSpecification(tool, (exchange, request) -> {
            try {
                List<ApprovalRequestRow> votable = requestRepo.findVotableRequests();
                Instant now = Instant.now();

                // Resolve the agent's channel id (may or may not exist yet)
                String channelId = resolveChannelId(agentName, channelRepo);

                // Collect request IDs the agent has already voted on
                Set<String> alreadyVotedRequestIds = collectAlreadyVotedRequestIds(channelId, votable, voteRepo);

                List<Map<String, Object>> eligible = new ArrayList<>();
                for (ApprovalRequestRow row : votable) {
                    // Skip if agent is not assigned to this coin
                    if (!VoteOnApprovalTool.isAgentAssignedToCoin(agentName, row.coin(), runtimeConfig)) {
                        continue;
                    }
                    // Skip if already expired
                    if (row.expiresAt() != null && row.expiresAt().isBefore(now)) {
                        continue;
                    }
                    // Skip if agent has already voted
                    if (alreadyVotedRequestIds.contains(row.id())) {
                        continue;
                    }

                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("requestId", row.id());
                    entry.put("coin", row.coin());
                    entry.put("type", toApprovalType(row.toolName()));
                    entry.put("state", row.state());
                    if (row.toAddress() != null) entry.put("to", row.toAddress());
                    if (row.amountNative() != null) entry.put("amount", row.amountNative());
                    if (row.reason() != null && !row.reason().isBlank()) entry.put("reason", row.reason());
                    if (row.memo() != null && !row.memo().isBlank()) entry.put("memo", row.memo());
                    entry.put("approvalsGranted", row.approvalsGranted());
                    entry.put("minApprovalsRequired", row.minApprovalsRequired());
                    entry.put("requestedAt", row.requestedAt());
                    entry.put("expiresAt", row.expiresAt());
                    eligible.add(Map.copyOf(entry));
                }

                Map<String, Object> result = new LinkedHashMap<>();
                result.put("agentName", agentName);
                result.put("eligibleCount", eligible.size());
                result.put("requests", eligible);

                String json = toJson(result);
                return new CallToolResult(List.of(new TextContent(json)), false, null, null);
            } catch (Exception e) {
                log.error("list_eligible_requests unexpected error: {}", e.getMessage(), e);
                String json = toJson(Map.of("error", "internal_error",
                        "message", "Unexpected error in list_eligible_requests: " + e.getMessage()));
                return new CallToolResult(List.of(new TextContent(json)), true, null, null);
            }
        });
    }

    private static String resolveChannelId(String agentName, ChannelRepository channelRepo) {
        try {
            var existing = channelRepo.findChannelById(agentName);
            return existing != null ? existing.id() : agentName;
        } catch (RuntimeException e) {
            return agentName;
        }
    }

    private static Set<String> collectAlreadyVotedRequestIds(
            String channelId,
            List<ApprovalRequestRow> votable,
            VoteRepository voteRepo
    ) {
        if (channelId == null) return Set.of();

        // Collect votes for each votable request and check if this agent has voted
        return votable.stream()
                .filter(row -> {
                    List<VoteDetail> votes = voteRepo.listVotesForRequest(row.id());
                    return votes.stream().anyMatch(v ->
                            v.channelId() != null && v.channelId().equalsIgnoreCase(channelId));
                })
                .map(ApprovalRequestRow::id)
                .collect(Collectors.toSet());
    }

    private static String toApprovalType(String toolName) {
        if (toolName == null || toolName.isBlank()) return "unknown";
        String normalized = toolName.trim().toLowerCase();
        if (normalized.endsWith("_send") || normalized.contains("send")) return "send";
        return normalized;
    }

    private static String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize to JSON", e);
        }
    }
}
