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

package io.konkin.web.controller;

import io.javalin.http.Context;
import io.konkin.config.ConfigManager;
import io.konkin.crypto.Coin;
import io.konkin.crypto.CoinWallet;
import io.konkin.crypto.CoinWalletFactory;
import io.konkin.crypto.DepositAddress;
import io.konkin.crypto.WalletConnectionConfig;
import io.konkin.crypto.WalletSecretWriter;
import io.konkin.crypto.WalletStatus;
import io.konkin.crypto.WalletSupervisor;
import io.konkin.crypto.bitcoin.BitcoinExtras;
import io.konkin.crypto.litecoin.LitecoinExtras;
import io.konkin.crypto.monero.MoneroExtras;
import io.konkin.web.LandingPageMapper;
import io.konkin.web.service.LandingPageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static io.konkin.web.WebUtils.defaultIfBlank;

/**
 * Handles wallet-related web UI pages and operations.
 */
public class WalletController {

    private static final Logger log = LoggerFactory.getLogger(WalletController.class);

    private static final long TEST_CONNECTION_TIMEOUT_MS = 10_000;

    private final LandingPageService landingPageService;
    private final LandingPageMapper mapper;
    private final Map<Coin, WalletSupervisor> walletSupervisors;
    private final boolean passwordProtectionEnabled;
    private final Predicate<Context> sessionValidator;
    private final Consumer<Context> loginRedirect;
    private final ConfigManager configManager;
    private final Path secretsDir;

    public WalletController(
            LandingPageService landingPageService,
            LandingPageMapper mapper,
            Map<Coin, WalletSupervisor> walletSupervisors,
            boolean passwordProtectionEnabled,
            Predicate<Context> sessionValidator,
            Consumer<Context> loginRedirect
    ) {
        this(landingPageService, mapper, walletSupervisors, passwordProtectionEnabled,
                sessionValidator, loginRedirect, null, null);
    }

    public WalletController(
            LandingPageService landingPageService,
            LandingPageMapper mapper,
            Map<Coin, WalletSupervisor> walletSupervisors,
            boolean passwordProtectionEnabled,
            Predicate<Context> sessionValidator,
            Consumer<Context> loginRedirect,
            ConfigManager configManager,
            Path secretsDir
    ) {
        this.landingPageService = landingPageService;
        this.mapper = mapper;
        this.walletSupervisors = walletSupervisors != null ? walletSupervisors : Map.of();
        this.passwordProtectionEnabled = passwordProtectionEnabled;
        this.sessionValidator = sessionValidator;
        this.loginRedirect = loginRedirect;
        this.configManager = configManager;
        this.secretsDir = secretsDir;
    }

    public void handleWalletsPage(Context ctx) {
        if (passwordProtectionEnabled && !sessionValidator.test(ctx)) {
            loginRedirect.accept(ctx);
            return;
        }

        ctx.contentType("text/html; charset=UTF-8");
        ctx.result(landingPageService.renderWallets(
                passwordProtectionEnabled,
                mapper.buildWalletsModel()
        ));
    }

    public void handleWalletPage(Context ctx) {
        if (passwordProtectionEnabled && !sessionValidator.test(ctx)) {
            loginRedirect.accept(ctx);
            return;
        }

        String coinId = ctx.pathParam("coin").toLowerCase(Locale.ROOT);
        Map<String, Object> walletData = mapper.buildSingleCoinWalletModel(coinId);
        if (walletData == null) {
            ctx.redirect("/wallets");
            return;
        }

        String errorParam = ctx.queryParam("error");
        if (errorParam != null && !errorParam.isBlank()) {
            walletData = new java.util.LinkedHashMap<>(walletData);
            walletData.put("ruleFlash", errorParam);
            // Also surface on the coin header for wallet operation errors
            @SuppressWarnings("unchecked")
            Map<String, Object> coinData = (Map<String, Object>) walletData.get("coin");
            if (coinData != null) {
                Map<String, Object> mutableCoin = new java.util.LinkedHashMap<>(coinData);
                mutableCoin.put("connectionError", errorParam);
                walletData.put("coin", mutableCoin);
            }
        }

        ctx.contentType("text/html; charset=UTF-8");
        ctx.result(landingPageService.renderWallet(
                passwordProtectionEnabled,
                coinId,
                walletData
        ));
    }

    public void handleGenerateDepositAddress(Context ctx) {
        if (passwordProtectionEnabled && !sessionValidator.test(ctx)) {
            loginRedirect.accept(ctx);
            return;
        }

        String coinId = defaultIfBlank(ctx.formParam("coin"), "").trim().toLowerCase(Locale.ROOT);
        if (coinId.isEmpty()) {
            ctx.status(400);
            ctx.contentType("text/plain; charset=UTF-8");
            ctx.result("Missing required form parameter: coin");
            return;
        }

        Coin coin = resolveCoin(coinId);
        WalletSupervisor supervisor = coin != null ? walletSupervisors.get(coin) : null;
        if (supervisor == null) {
            log.warn("Generate deposit address requested but no wallet supervisor available for {}", coinId);
            ctx.redirect("/wallets");
            return;
        }

        try {
            DepositAddress depositAddress = supervisor.execute(wallet -> wallet.depositAddress());
            String address = depositAddress.address();

            mapper.persistDepositAddress(coinId, address);
            log.info("Generated new {} deposit address and persisted to KvStore", coinId);
        } catch (Exception e) {
            log.warn("Failed to generate deposit address for {}: {}", coinId, e.getMessage());
            ctx.redirect("/wallets/" + coinId + "?error=" + URLEncoder.encode(e.getMessage(), StandardCharsets.UTF_8));
            return;
        }

        ctx.redirect("/wallets/" + coinId);
    }

    public void handleWalletReconnect(Context ctx) {
        if (passwordProtectionEnabled && !sessionValidator.test(ctx)) {
            loginRedirect.accept(ctx);
            return;
        }

        String coinId = defaultIfBlank(ctx.formParam("coin"), "").trim().toLowerCase(Locale.ROOT);
        if (coinId.isEmpty()) {
            ctx.status(400);
            ctx.contentType("text/plain; charset=UTF-8");
            ctx.result("Missing required form parameter: coin");
            return;
        }

        Coin coin = resolveCoin(coinId);
        WalletSupervisor supervisor = coin != null ? walletSupervisors.get(coin) : null;
        if (supervisor != null) {
            supervisor.reconnect();
            log.info("Triggered reconnect for wallet {}", coinId);
        } else {
            log.warn("Reconnect requested but no wallet supervisor available for {}", coinId);
        }

        ctx.redirect("/wallets/" + coinId);
    }

    // ── Connection Wizard endpoints ──────────────────────────────────────────

    public void handleTestConnection(Context ctx) {
        if (passwordProtectionEnabled && !sessionValidator.test(ctx)) {
            ctx.status(401).json(Map.of("success", false, "message", "Unauthorized"));
            return;
        }

        String coinId = ctx.pathParam("coin").toLowerCase(Locale.ROOT);
        Coin coin = resolveCoin(coinId);
        if (coin == null) {
            ctx.status(400).json(Map.of("success", false, "message", "Unknown coin: " + coinId));
            return;
        }

        Map<String, String> params = extractConnectionParams(ctx, coinId);
        List<String> errors = SettingsValidator.validateConnectionForm(coinId, params);
        if (!errors.isEmpty()) {
            ctx.json(Map.of("success", false, "message", String.join("; ", errors), "warnings", List.of()));
            return;
        }

        List<String> warnings = new ArrayList<>();
        try {
            WalletConnectionConfig config = buildConnectionConfig(coin, coinId, params, warnings);
            CoinWalletFactory factory = resolveFactory(coin);
            CoinWallet wallet = factory.create(config);

            WalletStatus status;
            try {
                var future = java.util.concurrent.CompletableFuture.supplyAsync(wallet::status);
                status = future.get(TEST_CONNECTION_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                ctx.json(Map.of("success", false, "message", "Connection timed out after " + (TEST_CONNECTION_TIMEOUT_MS / 1000) + "s",
                        "network", (Object) null, "warnings", List.copyOf(warnings)));
                return;
            }

            String network = config.extras().getOrDefault(
                    coin == Coin.XMR ? MoneroExtras.NETWORK : (coin == Coin.LTC ? LitecoinExtras.NETWORK : BitcoinExtras.NETWORK),
                    "unknown");
            String statusLabel = status == WalletStatus.AVAILABLE ? "Connected" :
                    status == WalletStatus.SYNCING ? "Connected (syncing)" : "Offline";

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", status != WalletStatus.OFFLINE);
            result.put("message", statusLabel + " — " + network);
            result.put("network", network);
            result.put("warnings", List.copyOf(warnings));

            if (status == WalletStatus.OFFLINE) {
                result.put("message", "Node reachable but wallet reports offline");
            }

            ctx.json(result);
        } catch (Exception e) {
            log.warn("Connection test failed for {}: {}", coinId, e.getMessage());
            ctx.json(Map.of("success", false, "message", e.getMessage() != null ? e.getMessage() : "Connection failed",
                    "network", (Object) null, "warnings", List.copyOf(warnings)));
        }
    }

    public void handleSaveConnection(Context ctx) {
        boolean isJsonRequest = isJsonContentType(ctx);

        if (passwordProtectionEnabled && !sessionValidator.test(ctx)) {
            if (isJsonRequest) {
                ctx.status(401).json(Map.of("success", false, "message", "Unauthorized"));
            } else {
                ctx.status(401).redirect("/wallets");
            }
            return;
        }

        if (configManager == null || secretsDir == null) {
            if (isJsonRequest) {
                ctx.status(500).json(Map.of("success", false, "message", "Wizard not available — server configuration missing"));
            } else {
                ctx.status(500).redirect("/wallets");
            }
            return;
        }

        String coinId = ctx.pathParam("coin").toLowerCase(Locale.ROOT);
        Coin coin = resolveCoin(coinId);
        if (coin == null) {
            if (isJsonRequest) {
                ctx.status(400).json(Map.of("success", false, "message", "Unknown coin: " + coinId));
            } else {
                ctx.redirect("/wallets");
            }
            return;
        }

        Map<String, String> params = extractConnectionParams(ctx, coinId);
        List<String> errors = SettingsValidator.validateConnectionForm(coinId, params);
        if (!errors.isEmpty()) {
            if (isJsonRequest) {
                ctx.json(Map.of("success", false, "message", String.join("; ", errors)));
            } else {
                ctx.redirect("/wallets");
            }
            return;
        }

        // Connection test before saving
        List<String> warnings = new ArrayList<>();
        try {
            WalletConnectionConfig testConfig = buildConnectionConfig(coin, coinId, params, warnings);
            CoinWalletFactory factory = resolveFactory(coin);
            CoinWallet wallet = factory.create(testConfig);
            var future = java.util.concurrent.CompletableFuture.supplyAsync(wallet::status);
            WalletStatus status = future.get(TEST_CONNECTION_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (status == WalletStatus.OFFLINE) {
                if (isJsonRequest) {
                    ctx.json(Map.of("success", false, "message", "Connection test failed: wallet reports offline"));
                } else {
                    ctx.redirect("/wallets");
                }
                return;
            }
        } catch (Exception e) {
            if (isJsonRequest) {
                ctx.json(Map.of("success", false, "message", "Connection test failed: " + (e.getMessage() != null ? e.getMessage() : "unknown error")));
            } else {
                ctx.redirect("/wallets");
            }
            return;
        }

        // Write secret files
        try {
            WalletSecretWriter.WrittenSecrets written = switch (coin) {
                case BTC -> WalletSecretWriter.writeBitcoinSecrets(secretsDir,
                        params.get("rpcHost"), params.get("rpcPort"),
                        params.get("rpcUser"), params.get("rpcPassword"),
                        params.get("walletName"));
                case LTC -> WalletSecretWriter.writeLitecoinSecrets(secretsDir,
                        params.get("rpcHost"), params.get("rpcPort"),
                        params.get("rpcUser"), params.get("rpcPassword"),
                        params.get("walletName"));
                case XMR -> WalletSecretWriter.writeMoneroSecrets(secretsDir,
                        params.get("daemonHost"), params.get("daemonPort"),
                        params.get("daemonUser"), params.get("daemonPassword"),
                        params.get("walletRpcHost"), params.get("walletRpcPort"),
                        params.get("walletRpcUser"), params.get("walletRpcPassword"));
                default -> throw new IllegalStateException("Unsupported coin: " + coin);
            };

            // Update config.toml
            Map<String, Object> configUpdates = new LinkedHashMap<>();
            configUpdates.put("enabled", true);
            String secretsDirPlaceholder = "{secrets-dir}";
            switch (coin) {
                case BTC -> {
                    configUpdates.put("secret-files.bitcoin-daemon-config-file", secretsDirPlaceholder + "/" + written.daemonConfigPath().getFileName());
                    configUpdates.put("secret-files.bitcoin-wallet-config-file", secretsDirPlaceholder + "/" + written.walletConfigPath().getFileName());
                }
                case LTC -> {
                    configUpdates.put("secret-files.litecoin-daemon-config-file", secretsDirPlaceholder + "/" + written.daemonConfigPath().getFileName());
                    configUpdates.put("secret-files.litecoin-wallet-config-file", secretsDirPlaceholder + "/" + written.walletConfigPath().getFileName());
                }
                case XMR -> {
                    configUpdates.put("secret-files.monero-daemon-config-file", secretsDirPlaceholder + "/" + written.daemonConfigPath().getFileName());
                    configUpdates.put("secret-files.monero-wallet-rpc-config-file", secretsDirPlaceholder + "/" + written.walletConfigPath().getFileName());
                }
                default -> { }
            }
            configManager.updateSection("coins." + coinId, configUpdates);

            // Ensure default auth config exists
            ensureDefaultAuthConfig(coinId);

            // Start or reconnect wallet supervisor
            startOrReconnectSupervisor(coin, coinId);

            log.info("Saved connection config for {} and started supervisor", coinId);
            if (isJsonRequest) {
                ctx.json(Map.of("success", true, "redirectUrl", "/wallets"));
            } else {
                ctx.redirect("/wallets");
            }
        } catch (Exception e) {
            log.error("Failed to save connection config for {}: {}", coinId, e.getMessage(), e);
            if (isJsonRequest) {
                ctx.json(Map.of("success", false, "message", "Failed to save: " + e.getMessage()));
            } else {
                ctx.redirect("/wallets");
            }
        }
    }

    private void ensureDefaultAuthConfig(String coinId) {
        var config = configManager.get();
        var coinConfig = config.resolveCoinConfig(coinId);
        if (coinConfig != null && coinConfig.auth() != null) {
            return; // auth config already exists
        }
        Map<String, Object> authDefaults = new LinkedHashMap<>();
        authDefaults.put("auth.web-ui", true);
        authDefaults.put("auth.rest-api", false);
        authDefaults.put("auth.telegram", false);
        authDefaults.put("auth.min-approvals-required", 1);
        configManager.updateSection("coins." + coinId, authDefaults);
    }

    private void startOrReconnectSupervisor(Coin coin, String coinId) {
        WalletSupervisor existing = walletSupervisors.get(coin);
        if (existing != null) {
            existing.reconnect();
            return;
        }

        try {
            var config = configManager.get();
            var coinConfig = config.resolveCoinConfig(coinId);
            if (coinConfig == null) return;

            WalletConnectionConfig walletConfig = switch (coin) {
                case BTC -> io.konkin.crypto.WalletSecretLoader.loadBitcoin(
                        coinConfig.daemonConfigSecretFile(), coinConfig.walletConfigSecretFile());
                case LTC -> io.konkin.crypto.WalletSecretLoader.loadLitecoin(
                        coinConfig.daemonConfigSecretFile(), coinConfig.walletConfigSecretFile());
                case XMR -> io.konkin.crypto.WalletSecretLoader.loadMonero(
                        coinConfig.daemonConfigSecretFile(), coinConfig.walletConfigSecretFile());
                default -> null;
            };
            if (walletConfig == null) return;

            CoinWalletFactory factory = resolveFactory(coin);
            WalletSupervisor supervisor = new WalletSupervisor(walletConfig, factory);
            supervisor.start();
            walletSupervisors.put(coin, supervisor);
        } catch (Exception e) {
            log.warn("Failed to start wallet supervisor for {} after save: {}", coinId, e.getMessage());
        }
    }

    private static boolean isJsonContentType(Context ctx) {
        String ct = ctx.contentType();
        return ct != null && ct.contains("application/json");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> extractConnectionParams(Context ctx, String coinId) {
        Map<String, String> params = new LinkedHashMap<>();

        // Support both JSON body and form params
        Map<String, Object> body = null;
        String contentType = ctx.contentType() != null ? ctx.contentType() : "";
        if (contentType.contains("json")) {
            try {
                body = ctx.bodyAsClass(Map.class);
            } catch (Exception e) {
                // fall through to form params
            }
        }

        if ("monero".equals(coinId)) {
            for (String key : new String[]{"daemonHost", "daemonPort", "daemonUser", "daemonPassword",
                    "walletRpcHost", "walletRpcPort", "walletRpcUser", "walletRpcPassword"}) {
                params.put(key, resolveParam(ctx, body, key));
            }
        } else {
            for (String key : new String[]{"rpcHost", "rpcPort", "rpcUser", "rpcPassword", "walletName"}) {
                params.put(key, resolveParam(ctx, body, key));
            }
        }
        return params;
    }

    private static String resolveParam(Context ctx, Map<String, Object> jsonBody, String key) {
        if (jsonBody != null && jsonBody.containsKey(key)) {
            Object val = jsonBody.get(key);
            return val != null ? val.toString().trim() : "";
        }
        return defaultIfBlank(ctx.formParam(key), "").trim();
    }

    private static WalletConnectionConfig buildConnectionConfig(Coin coin, String coinId, Map<String, String> params, List<String> warnings) {
        if (coin == Coin.XMR) {
            String daemonHost = params.get("daemonHost");
            String daemonPort = params.get("daemonPort");
            String walletRpcHost = params.get("walletRpcHost");
            String walletRpcPort = params.get("walletRpcPort");

            warnIfNotLoopback(daemonHost, "Monero daemon RPC", warnings);
            warnIfNotLoopback(walletRpcHost, "Monero wallet-rpc", warnings);

            String walletRpcUrl = "http://" + walletRpcHost + ":" + walletRpcPort;
            String daemonRpcUrl = "http://" + daemonHost + ":" + daemonPort;

            String walletRpcUser = params.get("walletRpcUser");
            String walletRpcPassword = params.get("walletRpcPassword");

            Map<String, String> extras = new LinkedHashMap<>();
            extras.put(MoneroExtras.DAEMON_RPC_URL, daemonRpcUrl);
            String daemonUser = params.get("daemonUser");
            String daemonPassword = params.get("daemonPassword");
            if (daemonUser != null && !daemonUser.isEmpty()) {
                extras.put(MoneroExtras.DAEMON_RPC_USERNAME, daemonUser);
                extras.put(MoneroExtras.DAEMON_RPC_PASSWORD, daemonPassword);
            }
            extras.put(MoneroExtras.NETWORK, detectMoneroNetwork(daemonPort));

            return new WalletConnectionConfig(Coin.XMR, walletRpcUrl, walletRpcUser, walletRpcPassword, extras);
        } else {
            String rpcHost = params.get("rpcHost");
            String rpcPort = params.get("rpcPort");
            warnIfNotLoopback(rpcHost, coinId + " RPC", warnings);

            String rpcUrl = "http://" + rpcHost + ":" + rpcPort;
            String rpcUser = params.get("rpcUser");
            String rpcPassword = params.get("rpcPassword");
            String walletName = params.get("walletName");

            Map<String, String> extras = new LinkedHashMap<>();
            if (walletName != null && !walletName.isEmpty()) {
                extras.put(coin == Coin.LTC ? LitecoinExtras.WALLET_NAME : BitcoinExtras.WALLET_NAME, walletName);
            }
            extras.put(coin == Coin.LTC ? LitecoinExtras.NETWORK : BitcoinExtras.NETWORK,
                    coin == Coin.LTC ? detectLitecoinNetwork(rpcPort) : detectBitcoinNetwork(rpcPort));

            return new WalletConnectionConfig(coin, rpcUrl, rpcUser, rpcPassword, extras);
        }
    }

    private static CoinWalletFactory resolveFactory(Coin coin) {
        return switch (coin) {
            case BTC -> new io.konkin.crypto.bitcoin.BitcoinWalletFactory();
            case LTC -> new io.konkin.crypto.litecoin.LitecoinWalletFactory();
            case XMR -> new io.konkin.crypto.monero.MoneroWalletFactory();
            default -> throw new IllegalArgumentException("No factory for coin: " + coin);
        };
    }

    private static void warnIfNotLoopback(String host, String label, List<String> warnings) {
        boolean isLoopback = "127.0.0.1".equals(host) || "localhost".equals(host) || "::1".equals(host);
        if (!isLoopback) {
            warnings.add(label + " connects to non-loopback address '" + host + "' over plaintext HTTP");
        }
    }

    private static String detectBitcoinNetwork(String port) {
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

    private static String detectMoneroNetwork(String port) {
        return switch (port) {
            case "28081", "28082" -> "testnet";
            case "38081", "38082" -> "stagenet";
            default -> "mainnet";
        };
    }

    private static Coin resolveCoin(String coinId) {
        if (coinId == null) return null;
        return switch (coinId.toLowerCase(Locale.ROOT)) {
            case "bitcoin" -> Coin.BTC;
            case "litecoin" -> Coin.LTC;
            case "monero" -> Coin.XMR;
            default -> null;
        };
    }
}
