package io.konkin.agent.mcp.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.konkin.config.KonkinConfig;
import io.konkin.db.AuthQueueStore;
import io.konkin.db.entity.ApprovalRequestRow;
import io.konkin.db.entity.PageResult;
import io.modelcontextprotocol.server.McpServerFeatures.SyncResourceSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PendingApprovalsResource {

    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private PendingApprovalsResource() {
    }

    public static SyncResourceSpecification resource(
            String agentName,
            AuthQueueStore authQueueStore,
            KonkinConfig runtimeConfig
    ) {
        return new SyncResourceSpecification(
                new McpSchema.Resource(
                        "konkin://approvals/pending",
                        "pending-approvals",
                        null,
                        "List of pending approval requests assigned to this auth agent",
                        "application/json",
                        null, null, null
                ),
                (exchange, request) -> {
                    List<ApprovalRequestRow> assigned = loadAssignedPendingRequests(agentName, authQueueStore, runtimeConfig);

                    List<Map<String, Object>> entries = new ArrayList<>();
                    for (ApprovalRequestRow row : assigned) {
                        Map<String, Object> entry = new LinkedHashMap<>();
                        entry.put("requestId", row.id());
                        entry.put("coin", row.coin());
                        entry.put("type", toApprovalType(row.toolName()));
                        if (row.toAddress() != null) entry.put("to", row.toAddress());
                        if (row.amountNative() != null) entry.put("amount", row.amountNative());
                        entry.put("nonce", row.nonceComposite());
                        entry.put("requestedAt", row.requestedAt());
                        entry.put("expiresAt", row.expiresAt());
                        entries.add(Map.copyOf(entry));
                    }

                    return new ReadResourceResult(List.of(
                            new TextResourceContents(request.uri(), "application/json", toJson(entries))
                    ));
                }
        );
    }

    static List<ApprovalRequestRow> loadAssignedPendingRequests(
            String agentName,
            AuthQueueStore authQueueStore,
            KonkinConfig runtimeConfig
    ) {
        PageResult<ApprovalRequestRow> page = authQueueStore.pagePendingApprovalRequests("requested_at", "asc", 1, 200);
        List<ApprovalRequestRow> assigned = new ArrayList<>();
        for (ApprovalRequestRow row : page.rows()) {
            if (VoteOnApprovalTool.isAgentAssignedToCoin(agentName, row.coin(), runtimeConfig)) {
                assigned.add(row);
            }
        }
        return List.copyOf(assigned);
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
