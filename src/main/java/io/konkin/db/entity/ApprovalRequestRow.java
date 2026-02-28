package io.konkin.db.entity;

import java.time.Instant;

public record ApprovalRequestRow(
        String id,
        String coin,
        String toolName,
        String requestSessionId,
        String nonceUuid,
        String payloadHashSha256,
        String nonceComposite,
        String toAddress,
        String amountNative,
        String feePolicy,
        String feeCapNative,
        String memo,
        Instant requestedAt,
        Instant expiresAt,
        String state,
        String stateReasonCode,
        String stateReasonText,
        int minApprovalsRequired,
        int approvalsGranted,
        int approvalsDenied,
        String policyActionAtCreation,
        Instant createdAt,
        Instant updatedAt,
        Instant resolvedAt
) {
}
