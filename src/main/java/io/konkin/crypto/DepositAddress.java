package io.konkin.crypto;

import java.util.Map;

public record DepositAddress(Coin coin, String address, Map<String, String> extras) {
    public DepositAddress {
        if (coin == null) throw new IllegalArgumentException("coin must not be null");
        if (address == null || address.isBlank()) throw new IllegalArgumentException("address must not be blank");
        extras = extras == null ? Map.of() : Map.copyOf(extras);
    }
}
