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
import io.konkin.crypto.litecoin.LitecoinExtras;
import io.konkin.crypto.monero.MoneroExtras;
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

        // [M-9] Validate that HTTP-only RPC connects to loopback; warn if not
        warnIfNotLoopback(rpcConnect, "Bitcoin RPC");
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

    public static WalletConnectionConfig loadLitecoin(String daemonSecretPath, String walletSecretPath) {
        Properties daemon = loadProperties(Path.of(daemonSecretPath), "litecoin daemon");
        Properties wallet = loadProperties(Path.of(walletSecretPath), "litecoin wallet");

        String rpcUser = daemon.getProperty("rpcuser", "").trim();
        String rpcPassword = daemon.getProperty("rpcpassword", "").trim();
        String rpcConnect = daemon.getProperty("rpcconnect", "127.0.0.1").trim();
        String rpcPort = daemon.getProperty("rpcport", "9332").trim();

        warnIfNotLoopback(rpcConnect, "Litecoin RPC");
        String rpcUrl = "http://" + rpcConnect + ":" + rpcPort;

        String walletName = wallet.getProperty("wallet", "").trim();

        Map<String, String> extras = new LinkedHashMap<>();
        if (!walletName.isEmpty()) {
            extras.put(LitecoinExtras.WALLET_NAME, walletName);
        }
        extras.put(LitecoinExtras.NETWORK, detectLitecoinNetwork(rpcPort));

        log.info("Loaded Litecoin wallet config — rpcUrl={}, wallet={}, network={}",
                rpcUrl, walletName.isEmpty() ? "(default)" : walletName, extras.get(LitecoinExtras.NETWORK));

        return new WalletConnectionConfig(Coin.LTC, rpcUrl, rpcUser, rpcPassword, extras);
    }

    public static WalletConnectionConfig loadMonero(String daemonSecretPath, String walletRpcSecretPath) {
        Properties daemon = loadProperties(Path.of(daemonSecretPath), "monero daemon");
        Properties walletRpc = loadProperties(Path.of(walletRpcSecretPath), "monero wallet-rpc");

        // Daemon (monerod) connection — used for sync status
        String daemonHost = daemon.getProperty("rpc-bind-ip", "127.0.0.1").trim();
        String daemonPort = daemon.getProperty("rpc-bind-port", "18081").trim();
        String daemonRpcUrl = "http://" + daemonHost + ":" + daemonPort;

        // Wallet RPC connection — primary endpoint for all wallet operations
        String walletHost = walletRpc.getProperty("rpc-bind-ip", "127.0.0.1").trim();
        String walletPort = walletRpc.getProperty("rpc-bind-port", "18083").trim();
        String walletRpcUrl = "http://" + walletHost + ":" + walletPort;

        // Parse rpc-login (format: "user:password")
        String rpcLogin = walletRpc.getProperty("rpc-login", "").trim();
        String rpcUser = "";
        String rpcPassword = "";
        if (!rpcLogin.isEmpty() && rpcLogin.contains(":")) {
            int colonIdx = rpcLogin.indexOf(':');
            rpcUser = rpcLogin.substring(0, colonIdx);
            rpcPassword = rpcLogin.substring(colonIdx + 1);
        }

        warnIfNotLoopback(walletHost, "Monero wallet-rpc");
        warnIfNotLoopback(daemonHost, "Monero daemon");

        String network = detectMoneroNetwork(daemonPort);

        // Parse daemon rpc-login (format: "user:password")
        String daemonLogin = daemon.getProperty("rpc-login", "").trim();
        String daemonUser = "";
        String daemonPassword = "";
        if (!daemonLogin.isEmpty() && daemonLogin.contains(":")) {
            int colonIdx = daemonLogin.indexOf(':');
            daemonUser = daemonLogin.substring(0, colonIdx);
            daemonPassword = daemonLogin.substring(colonIdx + 1);
        }

        Map<String, String> extras = new LinkedHashMap<>();
        extras.put(MoneroExtras.DAEMON_RPC_URL, daemonRpcUrl);
        if (!daemonUser.isEmpty()) {
            extras.put(MoneroExtras.DAEMON_RPC_USERNAME, daemonUser);
            extras.put(MoneroExtras.DAEMON_RPC_PASSWORD, daemonPassword);
        }
        extras.put(MoneroExtras.NETWORK, network);

        log.info("Loaded Monero wallet config — walletRpcUrl={}, daemonRpcUrl={}, network={}",
                walletRpcUrl, daemonRpcUrl, network);

        return new WalletConnectionConfig(Coin.XMR, walletRpcUrl, rpcUser, rpcPassword, extras);
    }

    private static void warnIfNotLoopback(String host, String label) {
        boolean isLoopback = "127.0.0.1".equals(host) || "localhost".equals(host) || "::1".equals(host);
        if (!isLoopback) {
            log.warn("{} connects to non-loopback address '{}' over plaintext HTTP. "
                    + "Consider using HTTPS or ensuring network-level encryption.", label, host);
        }
    }

    private static String detectMoneroNetwork(String port) {
        return switch (port) {
            case "28081", "28082" -> "testnet";
            case "38081", "38082" -> "stagenet";
            default -> "mainnet";
        };
    }

    private static Properties loadProperties(Path path, String label) {
        Properties props = new Properties();
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            props.load(reader);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + label + " secret file: " + path.toAbsolutePath().normalize(), e);
        }
        stripInlineComments(props);
        return props;
    }

    private static void stripInlineComments(Properties props) {
        for (String name : props.stringPropertyNames()) {
            String value = props.getProperty(name);
            int hashIdx = value.indexOf('#');
            if (hashIdx >= 0) {
                props.setProperty(name, value.substring(0, hashIdx).trim());
            }
        }
    }

    private static String detectNetwork(String port) {
        return switch (port) {
            case "18332", "18443" -> "testnet";
            case "38332" -> "signet";
            case "18444" -> "regtest";
            default -> "mainnet";
        };
    }

    private static String detectLitecoinNetwork(String port) {
        return switch (port) {
            case "19332" -> "testnet";
            case "19443" -> "regtest";
            default -> "mainnet";
        };
    }
}