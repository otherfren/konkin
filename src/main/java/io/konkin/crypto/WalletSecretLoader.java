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

import io.konkin.crypto.bitcoin.BitcoinExtras;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

public final class WalletSecretLoader {

    private static final Logger log = LoggerFactory.getLogger(WalletSecretLoader.class);

    private WalletSecretLoader() {}

    public static WalletConnectionConfig loadBitcoin(String daemonSecretPath, String walletSecretPath) {
        Properties daemon = loadProperties(Path.of(daemonSecretPath), "bitcoin daemon");
        Properties wallet = loadProperties(Path.of(walletSecretPath), "bitcoin wallet");

        String rpcUser = daemon.getProperty("rpcuser", "").trim();
        String rpcPassword = daemon.getProperty("rpcpassword", "").trim();
        String rpcConnect = daemon.getProperty("rpcconnect", "127.0.0.1").trim();
        String rpcPort = daemon.getProperty("rpcport", "8332").trim();

        String rpcUrl = "http://" + rpcConnect + ":" + rpcPort;

        String walletName = wallet.getProperty("wallet", "").trim();

        Map<String, String> extras = new LinkedHashMap<>();
        if (!walletName.isEmpty()) {
            extras.put(BitcoinExtras.WALLET_NAME, walletName);
        }
        extras.put(BitcoinExtras.NETWORK, detectNetwork(rpcPort));

        log.info("Loaded Bitcoin wallet config — rpcUrl={}, wallet={}, network={}",
                rpcUrl, walletName.isEmpty() ? "(default)" : walletName, extras.get(BitcoinExtras.NETWORK));

        return new WalletConnectionConfig(Coin.BTC, rpcUrl, rpcUser, rpcPassword, extras);
    }

    private static Properties loadProperties(Path path, String label) {
        Properties props = new Properties();
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            props.load(reader);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + label + " secret file: " + path.toAbsolutePath().normalize(), e);
        }
        return props;
    }

    private static String detectNetwork(String port) {
        return switch (port) {
            case "18332", "18443" -> "testnet";
            case "38332" -> "signet";
            case "18444" -> "regtest";
            default -> "mainnet";
        };
    }
}