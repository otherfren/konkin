package io.konkin.crypto.monero;

import io.konkin.crypto.Coin;
import io.konkin.crypto.CoinWallet;
import io.konkin.crypto.CoinWalletFactory;
import io.konkin.crypto.WalletConnectionConfig;

public final class MoneroWalletFactory implements CoinWalletFactory {

    @Override
    public Coin coin() {
        return Coin.XMR;
    }

    @Override
    public CoinWallet create(WalletConnectionConfig config) {
        return new MoneroWallet(config);
    }
}
