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

    /**
     * Returns recent incoming transactions (both confirmed and unconfirmed).
     * Default implementation delegates to {@link #pendingIncoming()}.
     */
    public List<Transaction> recentIncoming() {
        return pendingIncoming();
    }

    /**
     * Returns recent outgoing transactions (both confirmed and unconfirmed).
     * Default implementation delegates to {@link #pendingOutgoing()}.
     */
    public List<Transaction> recentOutgoing() {
        return pendingOutgoing();
    }

    public abstract SignedMessage signMessage(String message);

    public abstract boolean verifyMessage(String message, String address, String signature);
}