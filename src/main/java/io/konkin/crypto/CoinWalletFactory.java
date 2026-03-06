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

public interface CoinWalletFactory {

    Coin coin();

    CoinWallet create(WalletConnectionConfig config);

    /**
     * Optional hook called by the supervisor before creating a wallet instance.
     * Allows coin-specific node preparation (e.g., Bitcoin's loadwallet RPC).
     * Default implementation does nothing.
     */
    default void prepareNode(WalletConnectionConfig config) {
        // no-op by default
    }
}