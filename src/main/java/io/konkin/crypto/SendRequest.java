package io.konkin.crypto;

import java.math.BigDecimal;
import java.util.Map;

public record SendRequest(Coin coin, String toAddress, BigDecimal amount, Map<String, String> extras) {
    public SendRequest {
        if (coin == null) throw new IllegalArgumentException("coin must not be null");
        if (toAddress == null || toAddress.isBlank()) throw new IllegalArgumentException("toAddress must not be blank");
        if (amount == null) throw new IllegalArgumentException("amount must not be null");
        extras = extras == null ? Map.of() : Map.copyOf(extras);
    }
}
