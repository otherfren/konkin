package io.konkin.crypto;

import java.util.Map;

public record WalletConnectionConfig(Coin coin, String rpcUrl, String username, String password, Map<String, String> extras) {
    public WalletConnectionConfig {
        if (coin == null) throw new IllegalArgumentException("coin must not be null");
        if (rpcUrl == null || rpcUrl.isBlank()) throw new IllegalArgumentException("rpcUrl must not be blank");
        extras = extras == null ? Map.of() : Map.copyOf(extras);
    }
}
