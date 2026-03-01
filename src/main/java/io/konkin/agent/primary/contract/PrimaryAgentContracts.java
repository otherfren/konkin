package io.konkin.agent.primary.contract;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * DTO contracts for driver agent endpoints.
 */
public final class PrimaryAgentContracts {

    private PrimaryAgentContracts() {
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
