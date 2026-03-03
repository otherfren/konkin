package io.konkin.crypto.monero;

import io.konkin.crypto.*;

import java.util.List;

// TODO: Implement using Monero wallet RPC (different API from Bitcoin)
public final class MoneroWallet extends CoinWallet {

    public MoneroWallet(WalletConnectionConfig config) {
        super(Coin.XMR, config);
    }

    @Override
    public WalletStatus status() {
        throw new UnsupportedOperationException("XMR status() not yet implemented — use get_info RPC");
    }

    @Override
    public WalletBalance balance() {
        throw new UnsupportedOperationException("XMR balance() not yet implemented — use get_balance RPC");
    }

    @Override
    public DepositAddress depositAddress() {
        throw new UnsupportedOperationException("XMR depositAddress() not yet implemented — use get_address RPC");
    }

    @Override
    public SendResult send(SendRequest request) {
        throw new UnsupportedOperationException("XMR send() not yet implemented — use transfer RPC");
    }

    @Override
    public List<Transaction> pendingIncoming() {
        throw new UnsupportedOperationException("XMR pendingIncoming() not yet implemented — use get_transfers RPC");
    }

    @Override
    public List<Transaction> pendingOutgoing() {
        throw new UnsupportedOperationException("XMR pendingOutgoing() not yet implemented — use get_transfers RPC");
    }

    @Override
    public SignedMessage signMessage(String message) {
        throw new UnsupportedOperationException("XMR signMessage() not yet implemented — use sign RPC");
    }

    @Override
    public boolean verifyMessage(String message, String address, String signature) {
        throw new UnsupportedOperationException("XMR verifyMessage() not yet implemented — use verify RPC");
    }
}
