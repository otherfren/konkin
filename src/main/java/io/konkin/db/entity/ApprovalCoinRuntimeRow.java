package io.konkin.db.entity;

import java.time.Instant;

public record ApprovalCoinRuntimeRow(
        String coin,
        String activeRequestId,
        Instant cooldownUntil,
        Instant lockdownUntil,
        Instant updatedAt
) {
}
