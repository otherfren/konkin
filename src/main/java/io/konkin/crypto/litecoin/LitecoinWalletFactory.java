package io.konkin.crypto.litecoin;

import io.konkin.crypto.Coin;
import io.konkin.crypto.CoinWallet;
import io.konkin.crypto.CoinWalletFactory;
import io.konkin.crypto.WalletConnectionConfig;

public final class LitecoinWalletFactory implements CoinWalletFactory {

    @Override
    public Coin coin() {
        return Coin.LTC;
    }

    @Override
    public CoinWallet create(WalletConnectionConfig config) {
        return new LitecoinWallet(config);
    }
}
