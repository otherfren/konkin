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
import io.konkin.crypto.Coin;
import io.konkin.crypto.DepositAddress;
import io.konkin.crypto.WalletSupervisor;
import io.konkin.web.LandingPageMapper;
import io.konkin.web.service.LandingPageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private final LandingPageService landingPageService;
    private final LandingPageMapper mapper;
    private final Map<Coin, WalletSupervisor> walletSupervisors;
    private final boolean passwordProtectionEnabled;
    private final Predicate<Context> sessionValidator;
    private final Consumer<Context> loginRedirect;

    public WalletController(
            LandingPageService landingPageService,
            LandingPageMapper mapper,
            Map<Coin, WalletSupervisor> walletSupervisors,
            boolean passwordProtectionEnabled,
            Predicate<Context> sessionValidator,
            Consumer<Context> loginRedirect
    ) {
        this.landingPageService = landingPageService;
        this.mapper = mapper;
        this.walletSupervisors = walletSupervisors != null ? walletSupervisors : Map.of();
        this.passwordProtectionEnabled = passwordProtectionEnabled;
        this.sessionValidator = sessionValidator;
        this.loginRedirect = loginRedirect;
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

    private static Coin resolveCoin(String coinId) {
        if (coinId == null) return null;
        return switch (coinId.toLowerCase(Locale.ROOT)) {
            case "bitcoin" -> Coin.BTC;
            case "monero" -> Coin.XMR;
            default -> null;
        };
    }
}
