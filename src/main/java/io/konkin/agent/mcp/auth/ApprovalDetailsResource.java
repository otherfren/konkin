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
import io.konkin.db.VoteRepository;
import io.konkin.db.entity.ApprovalRequestRow;
import io.konkin.db.entity.VoteDetail;
import io.modelcontextprotocol.server.McpServerFeatures.SyncResourceTemplateSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ApprovalDetailsResource {

    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static final Set<String> TERMINAL_STATES = Set.of(
            "DENIED", "TIMED_OUT", "CANCELLED", "COMPLETED", "FAILED", "REJECTED", "EXPIRED"
    );

    private ApprovalDetailsResource() {
    }

    public static SyncResourceTemplateSpecification template(
            String agentName,
            ApprovalRequestRepository requestRepo,
            VoteRepository voteRepo,
            KonkinConfig runtimeConfig
    ) {
        return new SyncResourceTemplateSpecification(
                new McpSchema.ResourceTemplate(
                        "konkin://approvals/{requestId}",
                        "approval-details",
                        null,
                        "Approval request details with votes",
                        "application/json",
                        null
                ),
                (exchange, request) -> {
                    String requestId = extractRequestId(request.uri());
                    ApprovalRequestRow requestRow = requestRepo.findApprovalRequestById(requestId);
                    if (requestRow == null) {
                        return new ReadResourceResult(List.of(
                                new TextResourceContents(request.uri(), "application/json",
                                        "{\"error\":\"request_not_found\"}")
                        ));
                    }

                    if (!VoteOnApprovalTool.isAgentAssignedToCoin(agentName, requestRow.coin(), runtimeConfig)) {
                        return new ReadResourceResult(List.of(
                                new TextResourceContents(request.uri(), "application/json",
                                        "{\"error\":\"agent_not_assigned_to_coin\"}")
                        ));
                    }

                    List<VoteDetail> votes = voteRepo.listVotesForRequest(requestId);
                    List<Map<String, Object>> votePayload = new ArrayList<>();
                    for (VoteDetail vote : votes) {
                        Map<String, Object> voteEntry = new LinkedHashMap<>();
                        voteEntry.put("decision", vote.decision());
                        if (vote.channelId() != null) voteEntry.put("channelId", vote.channelId());
                        if (vote.decisionReason() != null) voteEntry.put("reason", vote.decisionReason());
                        if (vote.decidedBy() != null) voteEntry.put("decidedBy", vote.decidedBy());
                        if (vote.decidedAt() != null) voteEntry.put("decidedAt", vote.decidedAt());
                        votePayload.add(Map.copyOf(voteEntry));
                    }

                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("requestId", requestRow.id());
                    payload.put("coin", requestRow.coin());
                    payload.put("type", toApprovalType(requestRow.toolName()));
                    payload.put("state", requestRow.state());
                    payload.put("terminal", isTerminalState(requestRow.state()));
                    payload.put("minApprovalsRequired", requestRow.minApprovalsRequired());
                    payload.put("approvalsGranted", requestRow.approvalsGranted());
                    payload.put("approvalsDenied", requestRow.approvalsDenied());
                    payload.put("requestedAt", requestRow.requestedAt());
                    payload.put("expiresAt", requestRow.expiresAt());
                    payload.put("votes", List.copyOf(votePayload));

                    if (requestRow.toAddress() != null) payload.put("to", requestRow.toAddress());
                    if (requestRow.amountNative() != null) payload.put("amount", requestRow.amountNative());
                    if (requestRow.nonceComposite() != null) payload.put("nonce", requestRow.nonceComposite());
                    if (requestRow.stateReasonCode() != null) payload.put("reasonCode", requestRow.stateReasonCode());
                    if (requestRow.stateReasonText() != null) payload.put("reasonText", requestRow.stateReasonText());

                    return new ReadResourceResult(List.of(
                            new TextResourceContents(request.uri(), "application/json", toJson(Map.copyOf(payload)))
                    ));
                }
        );
    }

    private static boolean isTerminalState(String state) {
        return state != null && TERMINAL_STATES.contains(state.trim().toUpperCase());
    }

    private static String toApprovalType(String toolName) {
        if (toolName == null || toolName.isBlank()) return "unknown";
        String normalized = toolName.trim().toLowerCase();
        if (normalized.endsWith("_send") || normalized.contains("send")) return "send";
        return normalized;
    }

    private static String extractRequestId(String uri) {
        String prefix = "konkin://approvals/";
        if (uri != null && uri.startsWith(prefix) && uri.length() > prefix.length()) {
            String remainder = uri.substring(prefix.length());
            // Don't match the "pending" resource
            if (!"pending".equals(remainder)) {
                return remainder;
            }
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