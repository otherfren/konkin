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

package io.konkin.agent.mcp.driver;

import io.konkin.db.ApprovalRequestRepository;
import io.konkin.db.entity.ApprovalRequestRow;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.konkin.agent.mcp.driver.WalletToolSupport.*;

public final class PendingOutgoingTool {

    private PendingOutgoingTool() {}

    public static SyncToolSpecification create(ApprovalRequestRepository requestRepo) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("coin", Map.of("type", "string",
                "description", "Coin identifier to filter by (optional): bitcoin, litecoin, monero"));

        McpSchema.Tool tool = new McpSchema.Tool(
                "pending_outgoing",
                null,
                "List outgoing transactions that are queued for execution but not yet sent — typically waiting for sufficient spendable balance.",
                new McpSchema.JsonSchema("object", properties, List.of(), null, null, null),
                null, null, null
        );

        return new SyncToolSpecification(tool, (exchange, request) -> {
            String coin = argString(request.arguments(), "coin");

            try {
                List<ApprovalRequestRow> queued = requestRepo.findQueuedForExecution();

                // Filter by coin if specified
                if (coin != null && !coin.isBlank()) {
                    String normalizedCoin = coin.trim().toLowerCase();
                    queued = queued.stream()
                            .filter(r -> r.coin() != null && r.coin().toLowerCase().equals(normalizedCoin))
                            .toList();
                }

                List<Map<String, Object>> items = new ArrayList<>();
                for (ApprovalRequestRow row : queued) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("id", row.id());
                    entry.put("coin", row.coin());
                    entry.put("toolName", row.toolName());
                    entry.put("toAddress", row.toAddress());
                    entry.put("amountNative", row.amountNative());
                    entry.put("feePolicy", row.feePolicy());
                    entry.put("feeCapNative", row.feeCapNative());
                    entry.put("memo", row.memo());
                    entry.put("reason", row.reason());
                    entry.put("state", row.state());
                    entry.put("stateReasonText", row.stateReasonText());
                    entry.put("requestedAt", row.requestedAt() != null ? row.requestedAt().toString() : null);
                    entry.put("updatedAt", row.updatedAt() != null ? row.updatedAt().toString() : null);
                    items.add(entry);
                }

                Map<String, Object> result = new LinkedHashMap<>();
                if (coin != null && !coin.isBlank()) {
                    result.put("coin", coin.trim().toLowerCase());
                }
                result.put("pendingOutgoing", items);
                result.put("count", items.size());

                return jsonResult(result);
            } catch (Exception e) {
                return unexpectedError("pending_outgoing", e);
            }
        });
    }
}
