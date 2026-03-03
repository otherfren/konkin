package io.konkin.crypto;

import java.util.concurrent.ConcurrentHashMap;

public final class CoinWalletRegistry {

    private final ConcurrentHashMap<Coin, CoinWalletFactory> factories = new ConcurrentHashMap<>();

    public void register(CoinWalletFactory factory) {
        if (factory == null) throw new IllegalArgumentException("factory must not be null");
        factories.put(factory.coin(), factory);
    }

    public CoinWallet create(WalletConnectionConfig config) {
        if (config == null) throw new IllegalArgumentException("config must not be null");
        CoinWalletFactory factory = factories.get(config.coin());
        if (factory == null) {
            throw new IllegalArgumentException("No factory registered for coin: " + config.coin());
        }
        return factory.create(config);
    }

    public boolean supports(Coin coin) {
        return factories.containsKey(coin);
    }
}
