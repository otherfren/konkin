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
import io.konkin.approval.ApprovalPolicyEvaluator;
import io.konkin.approval.ApprovalPolicyEvaluator.PolicyDecision;
import io.konkin.config.CoinConfig;
import io.konkin.config.KonkinConfig;
import io.konkin.crypto.Coin;
import io.konkin.crypto.WalletSupervisor;
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
import java.util.UUID;

import static io.konkin.agent.mcp.driver.WalletToolSupport.*;

public final class SweepWalletTool {

    private static final Logger log = LoggerFactory.getLogger(SweepWalletTool.class);

    private SweepWalletTool() {
    }

    public static SyncToolSpecification create(
            String agentName,
            ApprovalRequestRepository requestRepo,
            HistoryRepository historyRepo,
            KonkinConfig runtimeConfig,
            TelegramApprovalNotifier telegramNotifier,
            Map<Coin, WalletSupervisor> walletSupervisors
    ) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("coin", Map.of("type", "string", "description", "Coin identifier: bitcoin, litecoin, monero"));
        properties.put("toAddress", Map.of("type", "string", "description", "Destination wallet address to sweep all funds into"));
        properties.put("reason", Map.of("type", "string", "description", "Mandatory reason WHY this sweep action is being requested"));

        McpSchema.Tool tool = new McpSchema.Tool(
                "sweep_wallet",
                null,
                "Sweep the entire wallet balance into a single destination address. Creates an approval request that must be voted on before execution.",
                new McpSchema.JsonSchema("object", properties, List.of("coin", "toAddress", "reason"), null, null, null),
                null, null, null
        );

        return new SyncToolSpecification(tool, (exchange, request) -> {
            try {
                return handleSweep(agentName, requestRepo, historyRepo, runtimeConfig, telegramNotifier, walletSupervisors, request.arguments());
            } catch (IllegalArgumentException e) {
                log.warn("sweep_wallet validation error: {}", e.getMessage());
                return errorResult("validation_error", e.getMessage());
            } catch (Exception e) {
                log.error("sweep_wallet unexpected error: {}", e.getMessage(), e);
                return errorResult("internal_error",
                        "Failed to create approval request: " + e.getMessage());
            }
        });
    }

    private static CallToolResult handleSweep(
            String agentName,
            ApprovalRequestRepository requestRepo,
            HistoryRepository historyRepo,
            KonkinConfig runtimeConfig,
            TelegramApprovalNotifier telegramNotifier,
            Map<Coin, WalletSupervisor> walletSupervisors,
            Map<String, Object> args
    ) {
        String coin = normalizeCoin(argString(args, "coin"));
        String toAddress = requireNonBlank(argString(args, "toAddress"), "toAddress is required");
        String reason = requireNonBlank(argString(args, "reason"), "reason is required: describe WHY this sweep action is being requested");

        if (!hasAnyEnabledCoin(runtimeConfig)) {
            return errorResult("no_coin_runtime_available",
                    "No coin runtime is enabled on this server. Enable at least one coin before calling sweep actions.");
        }

        CoinConfig coinConfig = sweepCoinConfig(runtimeConfig, coin);
        if (coinConfig == null) {
            return errorResult("unsupported_coin",
                    "Coin '" + coin + "' is not supported for sweep. Supported coins: bitcoin, litecoin, monero.");
        }

        if (!coinConfig.enabled()) {
            return errorResult("coin_not_enabled",
                    "Coin '" + coin + "' is currently disabled. Enable [coins." + coin + "].enabled=true in config.toml.");
        }

        if (toAddress.length() < 10) {
            return errorResult("invalid_address",
                    "Destination address '" + toAddress + "' is too short to be a valid cryptocurrency address.");
        }

        Instant now = Instant.now();
        String requestId = "req-" + UUID.randomUUID();
        String nonceUuid = UUID.randomUUID().toString();
        String payloadHash = sha256Hex(String.join("|", coin, toAddress, "ALL"));
        String nonceComposite = coin + "|" + nonceUuid + "|" + payloadHash;
        int minApprovalsRequired = coinConfig.auth().minApprovalsRequired();

        // Fetch wallet balance so policy rules can evaluate the actual sweep amount
        BigDecimal sweepAmount = null;
        Coin resolved = resolveCoin(coin);
        if (resolved != null && walletSupervisors != null) {
            WalletSupervisor supervisor = walletSupervisors.get(resolved);
            if (supervisor != null) {
                try {
                    sweepAmount = supervisor.execute(w -> w.balance()).spendable();
                } catch (Exception e) {
                    log.warn("Could not fetch {} balance for policy evaluation: {}", coin, e.getMessage());
                }
            }
        }

        PolicyDecision policy = ApprovalPolicyEvaluator.evaluate(
                coinConfig.auth(), coin, sweepAmount, requestRepo, now);

        ApprovalRequestRow row = new ApprovalRequestRow(
                requestId,
                coin,
                "wallet_sweep",
                null,
                nonceUuid,
                payloadHash,
                nonceComposite,
                toAddress,
                "ALL",
                null,
                null,
                null,
                reason,
                now,
                now.plus(runtimeConfig.telegramAutoDenyTimeout()),
                policy.state(),
                policy.reasonCode(),
                policy.reasonText(),
                minApprovalsRequired,
                0,
                0,
                policy.action(),
                now,
                now,
                policy.isAutoResolved() ? now : null
        );

        try {
            requestRepo.insertApprovalRequest(row);
        } catch (Exception e) {
            log.error("Failed to insert sweep approval request {}: {}", requestId, e.getMessage(), e);
            return errorResult("db_insert_failed",
                    "Failed to create approval request in database: " + e.getMessage());
        }

        try {
            historyRepo.insertStateTransition(new StateTransitionRow(
                    0L,
                    requestId,
                    null,
                    policy.state(),
                    policy.isAutoResolved() ? "policy" : "driver_agent",
                    policy.isAutoResolved() ? policy.action() : agentName,
                    policy.reasonCode(),
                    now
            ));
        } catch (Exception e) {
            log.warn("Request {} created but state transition log failed: {}", requestId, e.getMessage());
        }

        if (!policy.isAutoResolved() && telegramNotifier != null) {
            try {
                telegramNotifier.notifyIfTelegramEnabled(row);
            } catch (Exception e) {
                log.warn("Request {} created but Telegram notification failed: {}", requestId, e.getMessage());
            }
        }

        log.info("sweep_wallet request created: id={}, coin={}, to={}, policy={}", requestId, coin, toAddress, policy.action());

        String status = "auto_denied".equals(policy.action()) ? "denied" : "accepted";
        var response = new SendCoinActionAcceptedResponse(status, requestId, coin, "sweep", policy.state());
        boolean isError = "auto_denied".equals(policy.action());
        return new CallToolResult(List.of(new TextContent(toJson(response))), isError, null, null);
    }

    private static boolean hasAnyEnabledCoin(KonkinConfig config) {
        return config.bitcoin().enabled() || config.litecoin().enabled() || config.monero().enabled();
    }

    private static CoinConfig sweepCoinConfig(KonkinConfig config, String coin) {
        return switch (coin) {
            case "bitcoin" -> config.bitcoin();
            case "litecoin" -> config.litecoin();
            case "monero" -> config.monero();
            default -> null;
        };
    }
}
