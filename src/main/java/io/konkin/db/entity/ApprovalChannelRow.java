package io.konkin.db.entity;

import java.time.Instant;

public record ApprovalChannelRow(
        String id,
        String channelType,
        String displayName,
        boolean enabled,
        String configFingerprint,
        Instant createdAt
) {
}
