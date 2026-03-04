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

import java.util.concurrent.ConcurrentHashMap;

public final class CoinWalletRegistry {

    private final ConcurrentHashMap<Coin, CoinWalletFactory> factories = new ConcurrentHashMap<>();

    public void register(CoinWalletFactory factory) {
        if (factory == null) throw new IllegalArgumentException("factory must not be null");
        factories.put(factory.coin(), factory);
    }

    public CoinWallet create(WalletConnectionConfig config) {
        if (config == null) throw new IllegalArgumentException("config must not be null");
        CoinWalletFactory factory = factories.get(config.coin());
        if (factory == null) {
            throw new IllegalArgumentException("No factory registered for coin: " + config.coin());
        }
        return factory.create(config);
    }

    public boolean supports(Coin coin) {
        return factories.containsKey(coin);
    }
}