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

package io.konkin.crypto.bitcoin;

import io.konkin.crypto.Coin;
import io.konkin.crypto.CoinWallet;
import io.konkin.crypto.CoinWalletFactory;
import io.konkin.crypto.WalletConnectionConfig;
import org.bitcoinj.base.BitcoinNetwork;
import org.consensusj.bitcoin.jsonrpc.BitcoinClient;
import org.consensusj.jsonrpc.JsonRpcStatusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;

public final class BitcoinWalletFactory implements CoinWalletFactory {

    private static final Logger log = LoggerFactory.getLogger(BitcoinWalletFactory.class);

    @Override
    public Coin coin() {
        return Coin.BTC;
    }

    @Override
    public CoinWallet create(WalletConnectionConfig config) {
        return new BitcoinWallet(config);
    }

    @Override
    public void prepareNode(WalletConnectionConfig config) {
        String walletName = config.extras().get(BitcoinExtras.WALLET_NAME);
        if (walletName == null || walletName.isBlank()) {
            return;
        }

        try {
            URI baseUri = URI.create(config.rpcUrl());
            BitcoinClient bareClient = new BitcoinClient(
                    BitcoinNetwork.MAINNET, baseUri, config.username(), config.password());
            bareClient.send("loadwallet", Object.class, walletName);
            log.info("Loaded Bitcoin wallet '{}' on node", walletName);
        } catch (JsonRpcStatusException e) {
            String msg = e.getMessage();
            if (msg != null && msg.contains("already loaded")) {
                log.debug("Bitcoin wallet '{}' already loaded", walletName);
            } else {
                log.warn("Failed to load Bitcoin wallet '{}': {}", walletName, msg);
            }
        } catch (Exception e) {
            log.warn("Failed to send loadwallet RPC to {}: {} ({})",
                    config.rpcUrl(), e.getMessage(), e.getClass().getSimpleName());
        }
    }
}