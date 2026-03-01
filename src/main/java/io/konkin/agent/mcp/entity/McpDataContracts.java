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
