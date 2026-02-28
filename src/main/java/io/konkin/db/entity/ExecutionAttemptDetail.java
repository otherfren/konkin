package io.konkin.db.entity;

import java.time.Instant;

public record ExecutionAttemptDetail(
        long id,
        String requestId,
        int attemptNo,
        Instant startedAt,
        Instant finishedAt,
        String result,
        String errorClass,
        String errorMessage,
        String txid,
        String daemonFeeNative
) {
}
