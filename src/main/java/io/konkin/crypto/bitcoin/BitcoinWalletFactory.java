package io.konkin.crypto.bitcoin;

import io.konkin.crypto.Coin;
import io.konkin.crypto.CoinWallet;
import io.konkin.crypto.CoinWalletFactory;
import io.konkin.crypto.WalletConnectionConfig;

public final class BitcoinWalletFactory implements CoinWalletFactory {

    @Override
    public Coin coin() {
        return Coin.BTC;
    }

    @Override
    public CoinWallet create(WalletConnectionConfig config) {
        return new BitcoinWallet(config);
    }
}
