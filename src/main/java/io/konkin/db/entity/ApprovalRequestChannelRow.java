package io.konkin.db.entity;

import java.time.Instant;

public record ApprovalRequestChannelRow(
        long id,
        String requestId,
        String channelId,
        String deliveryState,
        Instant firstSentAt,
        Instant lastAttemptAt,
        int attemptCount,
        String lastError,
        Instant createdAt
) {
}
