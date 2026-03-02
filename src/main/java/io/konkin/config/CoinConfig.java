package io.konkin.config;

public record CoinConfig(
        boolean enabled,
        String bitcoinDaemonConfigSecretFile,
        String bitcoinWalletConfigSecretFile,
        CoinAuthConfig auth
) {
}
