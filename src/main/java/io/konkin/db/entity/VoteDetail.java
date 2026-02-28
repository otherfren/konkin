package io.konkin.db.entity;

import java.time.Instant;

public record VoteDetail(
        long id,
        String requestId,
        String channelId,
        String decision,
        String decisionReason,
        String decidedBy,
        Instant decidedAt
) {
}
