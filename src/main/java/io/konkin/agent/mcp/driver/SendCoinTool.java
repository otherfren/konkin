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

package io.konkin.agent.mcp.driver;

import io.konkin.agent.mcp.entity.McpDataContracts.SendCoinActionAcceptedResponse;
import io.konkin.config.CoinConfig;
import io.konkin.config.KonkinConfig;
import io.konkin.db.ApprovalRequestRepository;
import io.konkin.db.HistoryRepository;
import io.konkin.db.entity.ApprovalRequestRow;
import io.konkin.db.entity.StateTransitionRow;
import io.konkin.telegram.TelegramApprovalNotifier;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static io.konkin.agent.mcp.driver.WalletToolSupport.*;

public final class SendCoinTool {

    private static final Logger log = LoggerFactory.getLogger(SendCoinTool.class);

    private SendCoinTool() {
    }

    public static SyncToolSpecification create(
            String agentName,
            ApprovalRequestRepository requestRepo,
            HistoryRepository historyRepo,
            KonkinConfig runtimeConfig,
            TelegramApprovalNotifier telegramNotifier
    ) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("coin", Map.of("type", "string", "description", "Coin identifier: bitcoin, litecoin, monero, testdummycoin"));
        properties.put("toAddress", Map.of("type", "string", "description", "Destination wallet address"));
        properties.put("amountNative", Map.of("type", "string", "description", "Amount in native coin units"));
        properties.put("feePolicy", Map.of("type", "string", "description", "Fee policy: normal, priority, economy"));
        properties.put("feeCapNative", Map.of("type", "string", "description", "Maximum fee cap in native units"));
        properties.put("memo", Map.of("type", "string", "description", "Optional memo/note for this transaction"));
        properties.put("reason", Map.of("type", "string", "description", "Mandatory reason WHY this send action is being requested"));

        McpSchema.Tool tool = new McpSchema.Tool(
                "send_coin",
                null,
                "Submit a cryptocurrency send action for approval. Creates an approval request that must be voted on by auth agents before execution.",
                new McpSchema.JsonSchema("object", properties, List.of("coin", "toAddress", "amountNative", "reason"), null, null, null),
                null, null, null
        );

        return new SyncToolSpecification(tool, (exchange, request) -> {
            try {
                return handleSendCoin(agentName, requestRepo, historyRepo, runtimeConfig, telegramNotifier, request.arguments());
            } catch (IllegalArgumentException e) {
                log.warn("send_coin validation error: {}", e.getMessage());
                return errorResult("validation_error", e.getMessage());
            } catch (Exception e) {
                log.error("send_coin unexpected error: {}", e.getMessage(), e);
                return errorResult("internal_error",
                        "Failed to create approval request: " + e.getMessage());
            }
        });
    }

    private static CallToolResult handleSendCoin(
            String agentName,
            ApprovalRequestRepository requestRepo,
            HistoryRepository historyRepo,
            KonkinConfig runtimeConfig,
            TelegramApprovalNotifier telegramNotifier,
            Map<String, Object> args
    ) {
        String coin = normalizeCoin(argString(args, "coin"));
        String toAddress = requireNonBlank(argString(args, "toAddress"), "toAddress is required");
        String amountNative = requireNonBlank(argString(args, "amountNative"), "amountNative is required");
        String feePolicy = optionalTrim(argString(args, "feePolicy"));
        String feeCapNative = optionalTrim(argString(args, "feeCapNative"));
        String memo = optionalTrim(argString(args, "memo"));
        String reason = requireNonBlank(argString(args, "reason"), "reason is required: describe WHY this send action is being requested");

        // Validate amountNative is a valid positive number
        BigDecimal parsedAmount = validateAmount(amountNative);

        // Validate feeCapNative if provided
        if (feeCapNative != null) {
            validateFeeCap(feeCapNative);
        }

        if (!hasAnyEnabledCoin(runtimeConfig)) {
            return errorResult("no_coin_runtime_available",
                    "No coin runtime is enabled on this server. Enable at least one coin before calling send actions.");
        }

        CoinConfig coinConfig = sendActionCoinConfig(runtimeConfig, coin);
        if (coinConfig == null) {
            return errorResult("unsupported_coin",
                    "Coin '" + coin + "' is not supported by this endpoint. Supported coins: bitcoin, litecoin, monero, testdummycoin.");
        }

        if (!coinConfig.enabled()) {
            String hint = "testdummycoin".equals(coin)
                    ? "Enable [debug].enabled=true and [coins.testdummycoin].enabled=true in config.toml."
                    : "Enable [coins." + coin + "].enabled=true in config.toml.";
            return errorResult("coin_not_enabled",
                    "Coin '" + coin + "' is currently disabled. " + hint);
        }

        // Validate address minimum sanity (after coin checks so coin errors take priority)
        if (toAddress.length() < 10) {
            return errorResult("invalid_address",
                    "Destination address '" + toAddress + "' is too short to be a valid cryptocurrency address.");
        }

        Instant now = Instant.now();
        String requestId = "req-" + UUID.randomUUID();
        String nonceUuid = UUID.randomUUID().toString();
        String payloadHash = sha256Hex(String.join("|",
                coin,
                toAddress,
                amountNative,
                Objects.toString(feePolicy, ""),
                Objects.toString(feeCapNative, ""),
                Objects.toString(memo, "")
        ));
        String nonceComposite = coin + "|" + nonceUuid + "|" + payloadHash;
        int minApprovalsRequired = coinConfig.auth().minApprovalsRequired();

        ApprovalRequestRow row = new ApprovalRequestRow(
                requestId,
                coin,
                "wallet_send",
                null,
                nonceUuid,
                payloadHash,
                nonceComposite,
                toAddress,
                parsedAmount.toPlainString(),
                feePolicy,
                feeCapNative,
                memo,
                reason,
                now,
                now.plus(runtimeConfig.telegramAutoDenyTimeout()),
                "QUEUED",
                "queued_for_approval",
                "Request accepted and queued for approval",
                minApprovalsRequired,
                0,
                0,
                "manual",
                now,
                now,
                null
        );

        try {
            requestRepo.insertApprovalRequest(row);
        } catch (Exception e) {
            log.error("Failed to insert approval request {}: {}", requestId, e.getMessage(), e);
            return errorResult("db_insert_failed",
                    "Failed to create approval request in database: " + e.getMessage());
        }

        try {
            historyRepo.insertStateTransition(new StateTransitionRow(
                    0L,
                    requestId,
                    null,
                    "QUEUED",
                    "driver_agent",
                    agentName,
                    "queued_for_approval",
                    now
            ));
        } catch (Exception e) {
            log.warn("Request {} created but state transition log failed: {}", requestId, e.getMessage());
            // Request was created successfully, proceed despite history log failure
        }

        if (telegramNotifier != null) {
            try {
                telegramNotifier.notifyIfTelegramEnabled(row);
            } catch (Exception e) {
                log.warn("Request {} created but Telegram notification failed: {}", requestId, e.getMessage());
            }
        }

        log.info("send_coin request created: id={}, coin={}, to={}, amount={}",
                requestId, coin, toAddress, parsedAmount.toPlainString());

        var accepted = new SendCoinActionAcceptedResponse("accepted", requestId, coin, "send", "QUEUED");
        return new CallToolResult(List.of(new TextContent(toJson(accepted))), false, null, null);
    }

    private static boolean hasAnyEnabledCoin(KonkinConfig config) {
        return config.bitcoin().enabled()
                || config.litecoin().enabled()
                || config.monero().enabled()
                || config.testDummyCoin().enabled();
    }

    private static CoinConfig sendActionCoinConfig(KonkinConfig config, String coin) {
        return switch (coin) {
            case "bitcoin" -> config.bitcoin();
            case "litecoin" -> config.litecoin();
            case "monero" -> config.monero();
            case "testdummycoin" -> config.testDummyCoin();
            default -> null;
        };
    }
}
