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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.konkin.agent.mcp.driver.WalletToolSupport.*;

public final class RequestStatusTool {

    private RequestStatusTool() {}

    public static SyncToolSpecification create(ApprovalRequestRepository requestRepo) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("requestId", Map.of("type", "string",
                "description", "The approval request ID (e.g. req-4b80b169-...)"));

        McpSchema.Tool tool = new McpSchema.Tool(
                "request_status",
                null,
                "Check the current status of an approval request by ID.",
                new McpSchema.JsonSchema("object", properties, List.of("requestId"), null, null, null),
                null, null, null
        );

        return new SyncToolSpecification(tool, (exchange, request) -> {
            String requestId = argString(request.arguments(), "requestId");
            if (requestId == null || requestId.isBlank()) {
                return errorResult("validation_error", "requestId is required");
            }

            try {
                ApprovalRequestRow row = requestRepo.findApprovalRequestById(requestId.trim());
                if (row == null) {
                    return errorResult("not_found", "No approval request found with ID: " + requestId.trim());
                }

                Map<String, Object> result = new LinkedHashMap<>();
                result.put("id", row.id());
                result.put("coin", row.coin());
                result.put("toolName", row.toolName());
                result.put("toAddress", row.toAddress());
                result.put("amountNative", row.amountNative());
                result.put("feePolicy", row.feePolicy());
                result.put("memo", row.memo());
                result.put("reason", row.reason());
                result.put("state", row.state());
                result.put("stateReasonCode", row.stateReasonCode());
                result.put("stateReasonText", row.stateReasonText());
                result.put("approvalsGranted", row.approvalsGranted());
                result.put("approvalsDenied", row.approvalsDenied());
                result.put("minApprovalsRequired", row.minApprovalsRequired());
                result.put("requestedAt", row.requestedAt() != null ? row.requestedAt().toString() : null);
                result.put("expiresAt", row.expiresAt() != null ? row.expiresAt().toString() : null);
                result.put("updatedAt", row.updatedAt() != null ? row.updatedAt().toString() : null);

                return jsonResult(result);
            } catch (Exception e) {
                return unexpectedError("request_status", e);
            }
        });
    }
}
