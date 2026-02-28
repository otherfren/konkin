package io.konkin.db.entity;

import java.time.Instant;

public record StateTransitionRow(
        long id,
        String requestId,
        String fromState,
        String toState,
        String actorType,
        String actorId,
        String reasonCode,
        Instant createdAt
) {
}
