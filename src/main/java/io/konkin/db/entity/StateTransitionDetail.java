package io.konkin.db.entity;

import java.time.Instant;

public record StateTransitionDetail(
        long id,
        String requestId,
        String fromState,
        String toState,
        String actorType,
        String actorId,
        String reasonCode,
        String reasonText,
        String metadataJson,
        Instant createdAt
) {
}
