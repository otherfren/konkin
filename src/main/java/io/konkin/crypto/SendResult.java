package io.konkin.crypto;

import java.math.BigDecimal;
import java.util.Map;

public record SendResult(Coin coin, String txId, BigDecimal amount, BigDecimal fee, Map<String, String> extras) {
    public SendResult {
        if (coin == null) throw new IllegalArgumentException("coin must not be null");
        if (txId == null || txId.isBlank()) throw new IllegalArgumentException("txId must not be blank");
        if (amount == null) throw new IllegalArgumentException("amount must not be null");
        if (fee == null) throw new IllegalArgumentException("fee must not be null");
        extras = extras == null ? Map.of() : Map.copyOf(extras);
    }
}
