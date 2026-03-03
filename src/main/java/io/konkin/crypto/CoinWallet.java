package io.konkin.crypto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public abstract class CoinWallet {

    protected final Logger log = LoggerFactory.getLogger(getClass());
    private final Coin coin;
    private final WalletConnectionConfig config;

    protected CoinWallet(Coin coin, WalletConnectionConfig config) {
        if (coin == null) throw new IllegalArgumentException("coin must not be null");
        if (config == null) throw new IllegalArgumentException("config must not be null");
        this.coin = coin;
        this.config = config;
    }

    public final Coin coin() {
        return coin;
    }

    protected final WalletConnectionConfig config() {
        return config;
    }

    public abstract WalletStatus status();

    public abstract WalletBalance balance();

    public abstract DepositAddress depositAddress();

    public abstract SendResult send(SendRequest request);

    public abstract List<Transaction> pendingIncoming();

    public abstract List<Transaction> pendingOutgoing();

    public abstract SignedMessage signMessage(String message);

    public abstract boolean verifyMessage(String message, String address, String signature);
}
