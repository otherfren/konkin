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

package io.konkin.agent.mcp.entity;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * DTO contracts for MCP agent endpoints.
 */
public final class McpDataContracts {

    private McpDataContracts() {
    }

    public record RequirementItem(
            String key,
            String status,
            String message,
            String hint,
            boolean blocking
    ) {
    }

    public record RuntimeConfigRequirementsResponse(
            String coin,
            String status,
            String message,
            List<RequirementItem> checks,
            List<RequirementItem> missing,
            List<RequirementItem> invalid
    ) {
    }

    public record SendCoinActionRequest(
            String toAddress,
            String amountNative,
            String feePolicy,
            String feeCapNative,
            String memo,
            Long waitForFinalStateMs
    ) {
    }

    public record ApprovalVoteRequest(
            String decision,
            String reason
    ) {
    }

    public record SendCoinActionAcceptedResponse(
            String status,
            String requestId,
            String coin,
            String action,
            String state
    ) {
    }

    public record DecisionStatusResponse(
            String requestId,
            String coin,
            String state,
            boolean terminal,
            int minApprovalsRequired,
            int approvalsGranted,
            int approvalsDenied,
            String latestReasonCode,
            String latestReasonText,
            String txid
    ) {
    }

    public record DecisionEventResponse(
            String eventType,
            String requestId,
            String state,
            Instant timestamp,
            Map<String, Object> payload
    ) {
    }
}