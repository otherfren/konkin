package io.konkin.crypto.bitcoin;

import io.konkin.crypto.*;

import java.util.List;

// TODO: Implement using Bitcoin Core JSON-RPC (cj-btc-jsonrpc dependency)
public final class BitcoinWallet extends CoinWallet {

    public BitcoinWallet(WalletConnectionConfig config) {
        super(Coin.BTC, config);
    }

    @Override
    public WalletStatus status() {
        throw new UnsupportedOperationException("BTC status() not yet implemented — use getblockchaininfo RPC");
    }

    @Override
    public WalletBalance balance() {
        throw new UnsupportedOperationException("BTC balance() not yet implemented — use getbalances RPC");
    }

    @Override
    public DepositAddress depositAddress() {
        throw new UnsupportedOperationException("BTC depositAddress() not yet implemented — use getnewaddress RPC");
    }

    @Override
    public SendResult send(SendRequest request) {
        throw new UnsupportedOperationException("BTC send() not yet implemented — use sendtoaddress RPC");
    }

    @Override
    public List<Transaction> pendingIncoming() {
        throw new UnsupportedOperationException("BTC pendingIncoming() not yet implemented — use listtransactions RPC");
    }

    @Override
    public List<Transaction> pendingOutgoing() {
        throw new UnsupportedOperationException("BTC pendingOutgoing() not yet implemented — use listtransactions RPC");
    }

    @Override
    public SignedMessage signMessage(String message) {
        throw new UnsupportedOperationException("BTC signMessage() not yet implemented — use signmessage RPC");
    }

    @Override
    public boolean verifyMessage(String message, String address, String signature) {
        throw new UnsupportedOperationException("BTC verifyMessage() not yet implemented — use verifymessage RPC");
    }
}
