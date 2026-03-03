package io.konkin.crypto;

public interface CoinWalletFactory {

    Coin coin();

    CoinWallet create(WalletConnectionConfig config);
}
