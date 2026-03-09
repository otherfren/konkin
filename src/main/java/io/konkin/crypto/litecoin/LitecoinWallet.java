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

package io.konkin.crypto.litecoin;

import io.konkin.crypto.*;

import java.util.List;

// TODO: Implement using Litecoin Core JSON-RPC (same API as Bitcoin Core)
public final class LitecoinWallet extends CoinWallet {

    public LitecoinWallet(WalletConnectionConfig config) {
        super(Coin.LTC, config);
    }

    @Override
    public WalletStatus status() {
        throw new UnsupportedOperationException("LTC status() not yet implemented — use getblockchaininfo RPC");
    }

    @Override
    public WalletBalance balance() {
        throw new UnsupportedOperationException("LTC balance() not yet implemented — use getbalances RPC");
    }

    @Override
    public DepositAddress depositAddress() {
        throw new UnsupportedOperationException("LTC depositAddress() not yet implemented — use getnewaddress RPC");
    }

    @Override
    public SendResult send(SendRequest request) {
        throw new UnsupportedOperationException("LTC send() not yet implemented — use sendtoaddress RPC");
    }

    @Override
    public SweepResult sweep(SweepRequest request) {
        throw new UnsupportedOperationException("LTC sweep() not yet implemented — use sendtoaddress with subtractfeefromamount RPC");
    }

    @Override
    public List<Transaction> pendingIncoming() {
        throw new UnsupportedOperationException("LTC pendingIncoming() not yet implemented — use listtransactions RPC");
    }

    @Override
    public List<Transaction> pendingOutgoing() {
        throw new UnsupportedOperationException("LTC pendingOutgoing() not yet implemented — use listtransactions RPC");
    }

    @Override
    public SignedMessage signMessage(String message) {
        throw new UnsupportedOperationException("LTC signMessage() not yet implemented — use signmessage RPC");
    }

    @Override
    public boolean verifyMessage(String message, String address, String signature) {
        throw new UnsupportedOperationException("LTC verifyMessage() not yet implemented — use verifymessage RPC");
    }
}