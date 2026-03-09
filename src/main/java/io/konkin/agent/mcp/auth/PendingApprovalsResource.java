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

import io.konkin.config.KonkinConfig;
import io.konkin.db.ApprovalRequestRepository;
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

import static io.konkin.agent.mcp.driver.WalletToolSupport.toJson;

public final class PendingApprovalsResource {

    private PendingApprovalsResource() {
    }

    public static SyncResourceSpecification resource(
            String agentName,
            ApprovalRequestRepository requestRepo,
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
                    List<ApprovalRequestRow> assigned = loadAssignedPendingRequests(agentName, requestRepo, runtimeConfig);

                    List<Map<String, Object>> entries = new ArrayList<>();
                    for (ApprovalRequestRow row : assigned) {
                        Map<String, Object> entry = new LinkedHashMap<>();
                        entry.put("requestId", row.id());
                        entry.put("coin", row.coin());
                        entry.put("type", toApprovalType(row.toolName()));
                        if (row.toAddress() != null) entry.put("to", row.toAddress());
                        if (row.amountNative() != null) entry.put("amount", row.amountNative());
                        if (row.reason() != null && !row.reason().isBlank()) entry.put("reason", row.reason());
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
            ApprovalRequestRepository requestRepo,
            KonkinConfig runtimeConfig
    ) {
        PageResult<ApprovalRequestRow> page = requestRepo.pagePendingApprovalRequests("requested_at", "asc", 1, 200);
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

}