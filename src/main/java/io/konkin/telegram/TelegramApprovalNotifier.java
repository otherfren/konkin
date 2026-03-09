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

package io.konkin.telegram;

import io.konkin.config.CoinAuthConfig;
import io.konkin.config.CoinConfig;
import io.konkin.config.KonkinConfig;
import io.konkin.db.entity.ApprovalRequestRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bridges approval request creation and Telegram notification delivery.
 * Checks the per-coin auth config to decide whether to send a Telegram prompt.
 */
public class TelegramApprovalNotifier {

    private static final Logger log = LoggerFactory.getLogger(TelegramApprovalNotifier.class);

    private final TelegramService telegramService;
    private final KonkinConfig config;

    public TelegramApprovalNotifier(TelegramService telegramService, KonkinConfig config) {
        this.telegramService = telegramService;
        this.config = config;
    }

    public TelegramService telegramService() {
        return telegramService;
    }

    public void notifyIfTelegramEnabled(ApprovalRequestRow row) {
        CoinConfig coinConfig = resolveCoinConfig(row.coin());
        if (coinConfig == null) {
            log.debug("No coin config found for '{}', skipping Telegram notification for request {}",
                    row.coin(), row.id());
            return;
        }

        CoinAuthConfig authConfig = coinConfig.auth();
        if (authConfig == null || !authConfig.telegram()) {
            log.debug("Telegram not enabled for coin '{}', skipping notification for request {}",
                    row.coin(), row.id());
            return;
        }

        String messageText = formatApprovalMessage(row);

        try {
            TelegramService.SendResult result = telegramService.sendApprovalPrompt(messageText, row.id());
            if (result.success()) {
                log.info("Telegram approval prompt sent for request {}: {}", row.id(), result.detail());
            } else {
                log.warn("Telegram approval prompt failed for request {}: {}", row.id(), result.detail());
            }
        } catch (Exception e) {
            log.error("Unexpected error sending Telegram approval prompt for request {}", row.id(), e);
        }
    }

    private CoinConfig resolveCoinConfig(String coin) {
        return config.resolveCoinConfig(coin);
    }

    private static String formatApprovalMessage(ApprovalRequestRow row) {
        StringBuilder sb = new StringBuilder();
        sb.append("\uD83D\uDD14 New approval request\n\n");
        sb.append("Coin: ").append(row.coin()).append('\n');
        sb.append("Action: ").append(row.toolName()).append('\n');
        sb.append("To: ").append(row.toAddress()).append('\n');
        sb.append("Amount: ").append(row.amountNative()).append(' ').append(coinTicker(row.coin())).append('\n');
        if (row.feePolicy() != null) {
            sb.append("Fee policy: ").append(row.feePolicy()).append('\n');
        }
        if (row.feeCapNative() != null) {
            sb.append("Fee cap: ").append(row.feeCapNative()).append(' ').append(coinTicker(row.coin())).append('\n');
        }
        if (row.memo() != null && !row.memo().isBlank()) {
            sb.append("Memo: ").append(row.memo()).append('\n');
        }
        if (row.reason() != null && !row.reason().isBlank()) {
            sb.append("Reason: ").append(row.reason()).append('\n');
        }
        sb.append("Request ID: ").append(row.id()).append('\n');
        sb.append("\nExpires: ").append(row.expiresAt()).append('\n');
        return sb.toString();
    }

    private static String coinTicker(String coin) {
        if (coin == null) {
            return "";
        }
        return switch (coin) {
            case "bitcoin" -> "BTC";
            case "litecoin" -> "LTC";
            case "monero" -> "XMR";
            case "testdummycoin" -> "TEST";
            default -> coin.toUpperCase();
        };
    }
}