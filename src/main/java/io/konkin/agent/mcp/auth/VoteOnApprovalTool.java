package io.konkin.agent.mcp.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.konkin.config.CoinConfig;
import io.konkin.config.KonkinConfig;
import io.konkin.db.ApprovalRequestRepository;
import io.konkin.db.ChannelRepository;
import io.konkin.db.HistoryRepository;
import io.konkin.db.VoteRepository;
import io.konkin.db.entity.ApprovalChannelRow;
import io.konkin.db.entity.ApprovalRequestRow;
import io.konkin.db.entity.StateTransitionRow;
import io.konkin.db.entity.VoteDetail;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class VoteOnApprovalTool {

    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private static final Set<String> TERMINAL_STATES = Set.of(
            "DENIED", "TIMED_OUT", "CANCELLED", "COMPLETED", "FAILED", "REJECTED", "EXPIRED"
    );

    private VoteOnApprovalTool() {
    }

    public static SyncToolSpecification create(
            String agentName,
            ApprovalRequestRepository requestRepo,
            VoteRepository voteRepo,
            ChannelRepository channelRepo,
            HistoryRepository historyRepo,
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
            Map<String, Object> args = request.arguments();
            String requestId = requireNonBlank(argString(args, "requestId"), "requestId is required");
            String decision = requireNonBlank(argString(args, "decision"), "decision is required").trim().toLowerCase();
            String reason = optionalTrim(argString(args, "reason"));

            if (!"approve".equals(decision) && !"deny".equals(decision)) {
                return errorResult("invalid_decision", "decision must be 'approve' or 'deny'");
            }

            ApprovalRequestRow requestRow = requestRepo.findApprovalRequestById(requestId);
            if (requestRow == null || !isVoteableState(requestRow.state())) {
                return errorResult("request_not_found_or_resolved",
                        "Request not found or already resolved");
            }

            if (requestRow.expiresAt() != null && requestRow.expiresAt().isBefore(Instant.now())) {
                return errorResult("request_expired",
                        "Request has expired and can no longer be voted on");
            }

            if (!isAgentAssignedToCoin(agentName, requestRow.coin(), runtimeConfig)) {
                return errorResult("agent_not_assigned_to_coin",
                        "This agent is not assigned to the coin '" + requestRow.coin() + "'");
            }

            String channelId = ensureAuthChannelId(agentName, channelRepo);
            List<VoteDetail> existingVotes = voteRepo.listVotesForRequest(requestId);
            boolean alreadyVoted = existingVotes.stream()
                    .anyMatch(vote -> vote.channelId() != null && vote.channelId().equalsIgnoreCase(channelId));
            if (alreadyVoted) {
                return errorResult("already_voted", "This agent has already voted on this request");
            }

            Instant now = Instant.now();
            voteRepo.insertVote(new VoteDetail(
                    0L, requestId, channelId, decision, reason, agentName, now
            ));

            List<VoteDetail> votes = voteRepo.listVotesForRequest(requestId);
            int approvalsGranted = (int) votes.stream().filter(v -> "approve".equalsIgnoreCase(v.decision())).count();
            int approvalsDenied = (int) votes.stream().filter(v -> "deny".equalsIgnoreCase(v.decision())).count();

            String previousState = requestRow.state();
            String nextState = previousState;
            String reasonCode = requestRow.stateReasonCode();
            String reasonText = requestRow.stateReasonText();
            Instant resolvedAt = requestRow.resolvedAt();

            if (approvalsDenied > 0) {
                nextState = "DENIED";
                reasonCode = "vote_denied";
                reasonText = "Denied by auth approval vote";
                resolvedAt = now;
            } else if (approvalsGranted >= Math.max(1, requestRow.minApprovalsRequired())) {
                nextState = "APPROVED";
                reasonCode = "approval_threshold_met";
                reasonText = "Minimum approvals reached";
            } else if ("QUEUED".equalsIgnoreCase(previousState)) {
                nextState = "PENDING";
                reasonCode = "awaiting_more_votes";
                reasonText = "Awaiting additional approvals";
            }

            ApprovalRequestRow updated = new ApprovalRequestRow(
                    requestRow.id(),
                    requestRow.coin(),
                    requestRow.toolName(),
                    requestRow.requestSessionId(),
                    requestRow.nonceUuid(),
                    requestRow.payloadHashSha256(),
                    requestRow.nonceComposite(),
                    requestRow.toAddress(),
                    requestRow.amountNative(),
                    requestRow.feePolicy(),
                    requestRow.feeCapNative(),
                    requestRow.memo(),
                    requestRow.requestedAt(),
                    requestRow.expiresAt(),
                    nextState,
                    reasonCode,
                    reasonText,
                    requestRow.minApprovalsRequired(),
                    approvalsGranted,
                    approvalsDenied,
                    requestRow.policyActionAtCreation(),
                    requestRow.createdAt(),
                    now,
                    resolvedAt
            );
            requestRepo.updateApprovalRequest(updated);

            if (!Objects.equals(previousState, nextState)) {
                historyRepo.insertStateTransition(new StateTransitionRow(
                        0L, requestId, previousState, nextState,
                        "agent", agentName, reasonCode, now
                ));
            }

            String json = toJson(Map.of("status", "accepted", "requestId", requestId, "decision", decision));
            return new CallToolResult(List.of(new TextContent(json)), false, null, null);
        });
    }

    private static boolean isVoteableState(String state) {
        if (state == null) return false;
        String normalized = state.trim().toUpperCase();
        return "QUEUED".equals(normalized) || "PENDING".equals(normalized);
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
