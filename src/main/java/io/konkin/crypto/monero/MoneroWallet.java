/*
 * Copyright 2026 Peter Geschel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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