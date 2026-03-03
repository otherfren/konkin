package io.konkin.crypto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

public record Transaction(
        Coin coin,
        String txId,
        TransactionDirection direction,
        String address,
        BigDecimal amount,
        BigDecimal fee,
        String txKey,
        int confirmations,
        boolean confirmed,
        Instant timestamp,
        Map<String, String> extras
) {
    public Transaction {
        if (coin == null) throw new IllegalArgumentException("coin must not be null");
        if (txId == null || txId.isBlank()) throw new IllegalArgumentException("txId must not be blank");
        if (direction == null) throw new IllegalArgumentException("direction must not be null");
        if (address == null || address.isBlank()) throw new IllegalArgumentException("address must not be blank");
        if (amount == null) throw new IllegalArgumentException("amount must not be null");
        if (fee == null) throw new IllegalArgumentException("fee must not be null");
        if (timestamp == null) throw new IllegalArgumentException("timestamp must not be null");
        extras = extras == null ? Map.of() : Map.copyOf(extras);
    }
}
