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

package io.konkin.api;

import io.javalin.http.Context;
import io.konkin.approval.ApprovalPolicyEvaluator;
import io.konkin.approval.ApprovalPolicyEvaluator.PolicyDecision;
import io.konkin.config.CoinConfig;
import io.konkin.config.ConfigManager;
import io.konkin.config.KonkinConfig;
import io.konkin.crypto.*;
import io.konkin.db.ApprovalRequestRepository;
import io.konkin.db.HistoryRepository;
import io.konkin.db.entity.ApprovalRequestRow;
import io.konkin.db.entity.StateTransitionRow;
import io.konkin.telegram.TelegramApprovalNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

public class WalletApiController {

    private static final Logger log = LoggerFactory.getLogger(WalletApiController.class);

    private final Map<Coin, WalletSupervisor> walletSupervisors;
    private final ConfigManager configManager;
    private final ApprovalRequestRepository requestRepo;
    private final HistoryRepository historyRepo;
    private final TelegramApprovalNotifier telegramNotifier;

    public WalletApiController(
            Map<Coin, WalletSupervisor> walletSupervisors,
            ConfigManager configManager,
            ApprovalRequestRepository requestRepo,
            HistoryRepository historyRepo,
            TelegramApprovalNotifier telegramNotifier
    ) {
        this.walletSupervisors = walletSupervisors;
        this.configManager = configManager;
        this.requestRepo = requestRepo;
        this.historyRepo = historyRepo;
        this.telegramNotifier = telegramNotifier;
    }

    // ── Balance ──────────────────────────────────────────────────────────────

    public void getBalance(Context ctx) {
        Coin coin = validateCoin(ctx);
        if (coin == null) return;
        WalletSupervisor supervisor = requireSupervisor(ctx, coin);
        if (supervisor == null) return;

        try {
            WalletBalance balance = supervisor.execute(CoinWallet::balance);
            ctx.json(Map.of(
                    "coin", ctx.pathParam("coin").trim().toLowerCase(),
                    "total", balance.total().toPlainString(),
                    "spendable", balance.spendable().toPlainString()
            ));
        } catch (WalletException e) {
            walletError(ctx, e);
        }
    }

    // ── Status ───────────────────────────────────────────────────────────────

    public void getStatus(Context ctx) {
        Coin coin = validateCoin(ctx);
        if (coin == null) return;
        WalletSupervisor supervisor = requireSupervisor(ctx, coin);
        if (supervisor == null) return;

        try {
            WalletStatus status = supervisor.execute(CoinWallet::status);
            ctx.json(Map.of(
                    "coin", ctx.pathParam("coin").trim().toLowerCase(),
                    "status", status.name()
            ));
        } catch (WalletException e) {
            walletError(ctx, e);
        }
    }

    // ── Deposit Address ──────────────────────────────────────────────────────

    public void createDepositAddress(Context ctx) {
        Coin coin = validateCoin(ctx);
        if (coin == null) return;
        WalletSupervisor supervisor = requireSupervisor(ctx, coin);
        if (supervisor == null) return;

        try {
            DepositAddress addr = supervisor.execute(CoinWallet::depositAddress);
            ctx.status(201).json(Map.of(
                    "coin", ctx.pathParam("coin").trim().toLowerCase(),
                    "address", addr.address(),
                    "extras", addr.extras()
            ));
        } catch (WalletException e) {
            walletError(ctx, e);
        }
    }

    // ── Pending Incoming ─────────────────────────────────────────────────────

    public void getPendingIncoming(Context ctx) {
        Coin coin = validateCoin(ctx);
        if (coin == null) return;
        WalletSupervisor supervisor = requireSupervisor(ctx, coin);
        if (supervisor == null) return;

        try {
            List<Transaction> txns = supervisor.execute(CoinWallet::pendingIncoming);
            List<Map<String, Object>> txList = txns.stream().map(this::mapTransaction).toList();
            ctx.json(Map.of(
                    "coin", ctx.pathParam("coin").trim().toLowerCase(),
                    "transactions", txList,
                    "count", txList.size()
            ));
        } catch (WalletException e) {
            walletError(ctx, e);
        }
    }

    // ── Pending Outgoing ─────────────────────────────────────────────────────

    public void getPendingOutgoing(Context ctx) {
        List<ApprovalRequestRow> queued = requestRepo.findQueuedForExecution();
        List<Map<String, Object>> items = queued.stream().map(this::mapQueuedRequest).toList();
        ctx.json(Map.of("pendingOutgoing", items, "count", items.size()));
    }

    public void getPendingOutgoingByCoin(Context ctx) {
        String coinParam = ctx.pathParam("coin").trim().toLowerCase();
        List<ApprovalRequestRow> queued = requestRepo.findQueuedForExecution();
        List<Map<String, Object>> items = queued.stream()
                .filter(r -> r.coin() != null && r.coin().toLowerCase().equals(coinParam))
                .map(this::mapQueuedRequest)
                .toList();
        ctx.json(Map.of("coin", coinParam, "pendingOutgoing", items, "count", items.size()));
    }

    // ── Send ─────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public void send(Context ctx) {
        Coin coin = validateCoin(ctx);
        if (coin == null) return;

        Map<String, Object> body = ctx.bodyAsClass(Map.class);
        String coinName = ctx.pathParam("coin").trim().toLowerCase();
        String toAddress = stringField(body, "toAddress");
        String amountNative = stringField(body, "amountNative");
        String feePolicy = stringField(body, "feePolicy");
        String feeCapNative = stringField(body, "feeCapNative");
        String memo = stringField(body, "memo");
        String reason = stringField(body, "reason");

        if (toAddress == null || toAddress.isBlank()) {
            ctx.status(400).json(errorJson("validation_error", "toAddress is required"));
            return;
        }
        if (amountNative == null || amountNative.isBlank()) {
            ctx.status(400).json(errorJson("validation_error", "amountNative is required"));
            return;
        }
        if (reason == null || reason.isBlank()) {
            ctx.status(400).json(errorJson("validation_error", "reason is required"));
            return;
        }

        BigDecimal parsedAmount;
        try {
            parsedAmount = new BigDecimal(amountNative);
            if (parsedAmount.compareTo(BigDecimal.ZERO) <= 0) {
                ctx.status(400).json(errorJson("validation_error", "amountNative must be > 0"));
                return;
            }
        } catch (NumberFormatException e) {
            ctx.status(400).json(errorJson("validation_error", "amountNative is not a valid number"));
            return;
        }

        if (feeCapNative != null && !feeCapNative.isBlank()) {
            try {
                BigDecimal cap = new BigDecimal(feeCapNative);
                if (cap.compareTo(BigDecimal.ZERO) <= 0) {
                    ctx.status(400).json(errorJson("validation_error", "feeCapNative must be > 0"));
                    return;
                }
            } catch (NumberFormatException e) {
                ctx.status(400).json(errorJson("validation_error", "feeCapNative is not a valid number"));
                return;
            }
        }

        if (toAddress.trim().length() < 10) {
            ctx.status(400).json(errorJson("invalid_address", "Address is too short"));
            return;
        }

        KonkinConfig config = configManager.get();
        CoinConfig coinConfig = config.resolveCoinConfig(coinName);

        // Early balance gate (balance-required mode)
        if (!"always-queue".equals(config.spendingQueueMode())) {
            WalletSupervisor supervisor = walletSupervisors.get(coin);
            if (supervisor != null) {
                WalletSnapshot snapshot = supervisor.snapshot();
                BigDecimal totalBalance = snapshot.totalBalance();
                if (totalBalance != null) {
                    BigDecimal alreadyQueued = requestRepo.sumQueuedAndExecutingAmounts(coinName);
                    BigDecimal totalNeeded = parsedAmount.add(alreadyQueued);
                    if (totalNeeded.compareTo(totalBalance) > 0) {
                        ctx.status(422).json(errorJson("insufficient_total_balance",
                                "Insufficient total balance: need " + totalNeeded.toPlainString()
                                        + " (request " + parsedAmount.toPlainString()
                                        + " + queued " + alreadyQueued.toPlainString()
                                        + ") but total balance is " + totalBalance.toPlainString()));
                        return;
                    }
                }
            }
        }

        Instant now = Instant.now();
        String requestId = "req-" + UUID.randomUUID();
        String nonceUuid = UUID.randomUUID().toString();
        String payloadHash = sha256Hex(String.join("|", coinName, toAddress.trim(),
                parsedAmount.toPlainString(),
                Objects.toString(feePolicy, ""),
                Objects.toString(feeCapNative, ""),
                Objects.toString(memo, "")));
        String nonceComposite = coinName + "|" + nonceUuid + "|" + payloadHash;

        PolicyDecision policy = ApprovalPolicyEvaluator.evaluate(
                coinConfig.auth(), coinName, parsedAmount, requestRepo, now);

        ApprovalRequestRow row = new ApprovalRequestRow(
                requestId, coinName, "wallet_send", null,
                nonceUuid, payloadHash, nonceComposite,
                toAddress.trim(), parsedAmount.toPlainString(),
                feePolicy != null ? feePolicy.trim() : null,
                feeCapNative != null ? feeCapNative.trim() : null,
                memo != null ? memo.trim() : null,
                reason.trim(),
                now, now.plus(config.telegramAutoDenyTimeout()),
                policy.state(), policy.reasonCode(), policy.reasonText(),
                coinConfig.auth().minApprovalsRequired(), 0, 0,
                policy.action(), now, now,
                policy.isAutoResolved() ? now : null
        );

        requestRepo.insertApprovalRequest(row);
        historyRepo.insertStateTransition(new StateTransitionRow(
                0L, requestId, null, policy.state(),
                policy.isAutoResolved() ? "policy" : "rest_api",
                policy.isAutoResolved() ? policy.action() : "rest-api",
                policy.reasonCode(), now
        ));

        if (!policy.isAutoResolved() && telegramNotifier != null) {
            try { telegramNotifier.notifyIfTelegramEnabled(row); } catch (Exception ignored) {}
        }

        log.info("REST API send request: id={}, coin={}, to={}, amount={}, policy={}",
                requestId, coinName, toAddress, parsedAmount.toPlainString(), policy.action());

        ctx.status(202).json(Map.of(
                "requestId", requestId,
                "state", policy.state(),
                "message", "Approval request created"
        ));
    }

    // ── Sweep ────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public void sweep(Context ctx) {
        Coin coin = validateCoin(ctx);
        if (coin == null) return;

        Map<String, Object> body = ctx.bodyAsClass(Map.class);
        String coinName = ctx.pathParam("coin").trim().toLowerCase();
        String toAddress = stringField(body, "toAddress");
        String reason = stringField(body, "reason");

        if (toAddress == null || toAddress.isBlank()) {
            ctx.status(400).json(errorJson("validation_error", "toAddress is required"));
            return;
        }
        if (reason == null || reason.isBlank()) {
            ctx.status(400).json(errorJson("validation_error", "reason is required"));
            return;
        }
        if (toAddress.trim().length() < 10) {
            ctx.status(400).json(errorJson("invalid_address", "Address is too short"));
            return;
        }

        KonkinConfig config = configManager.get();
        CoinConfig coinConfig = config.resolveCoinConfig(coinName);

        // Fetch spendable balance for policy evaluation
        BigDecimal sweepAmount = null;
        WalletSupervisor supervisor = walletSupervisors.get(coin);
        if (supervisor != null) {
            try { sweepAmount = supervisor.execute(w -> w.balance()).spendable(); } catch (Exception ignored) {}
        }

        Instant now = Instant.now();
        String requestId = "req-" + UUID.randomUUID();
        String nonceUuid = UUID.randomUUID().toString();
        String payloadHash = sha256Hex(String.join("|", coinName, toAddress.trim(), "ALL"));
        String nonceComposite = coinName + "|" + nonceUuid + "|" + payloadHash;

        PolicyDecision policy = ApprovalPolicyEvaluator.evaluate(
                coinConfig.auth(), coinName, sweepAmount, requestRepo, now);

        ApprovalRequestRow row = new ApprovalRequestRow(
                requestId, coinName, "wallet_sweep", null,
                nonceUuid, payloadHash, nonceComposite,
                toAddress.trim(), "ALL", null, null, null,
                reason.trim(),
                now, now.plus(config.telegramAutoDenyTimeout()),
                policy.state(), policy.reasonCode(), policy.reasonText(),
                coinConfig.auth().minApprovalsRequired(), 0, 0,
                policy.action(), now, now,
                policy.isAutoResolved() ? now : null
        );

        requestRepo.insertApprovalRequest(row);
        historyRepo.insertStateTransition(new StateTransitionRow(
                0L, requestId, null, policy.state(),
                policy.isAutoResolved() ? "policy" : "rest_api",
                policy.isAutoResolved() ? policy.action() : "rest-api",
                policy.reasonCode(), now
        ));

        if (!policy.isAutoResolved() && telegramNotifier != null) {
            try { telegramNotifier.notifyIfTelegramEnabled(row); } catch (Exception ignored) {}
        }

        log.info("REST API sweep request: id={}, coin={}, to={}, policy={}",
                requestId, coinName, toAddress, policy.action());

        ctx.status(202).json(Map.of(
                "requestId", requestId,
                "state", policy.state(),
                "message", "Sweep approval request created"
        ));
    }

    // ── Sign Message ─────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public void signMessage(Context ctx) {
        Coin coin = validateCoin(ctx);
        if (coin == null) return;
        WalletSupervisor supervisor = requireSupervisor(ctx, coin);
        if (supervisor == null) return;

        Map<String, Object> body = ctx.bodyAsClass(Map.class);
        String message = stringField(body, "message");
        if (message == null || message.isBlank()) {
            ctx.status(400).json(errorJson("validation_error", "message is required"));
            return;
        }

        try {
            SignedMessage signed = supervisor.execute(w -> w.signMessage(message.trim()));
            ctx.json(Map.of(
                    "coin", ctx.pathParam("coin").trim().toLowerCase(),
                    "address", signed.address(),
                    "message", signed.message(),
                    "signature", signed.signature()
            ));
        } catch (WalletException e) {
            walletError(ctx, e);
        }
    }

    // ── Verify Message ───────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public void verifyMessage(Context ctx) {
        Coin coin = validateCoin(ctx);
        if (coin == null) return;
        WalletSupervisor supervisor = requireSupervisor(ctx, coin);
        if (supervisor == null) return;

        Map<String, Object> body = ctx.bodyAsClass(Map.class);
        String message = stringField(body, "message");
        String address = stringField(body, "address");
        String signature = stringField(body, "signature");

        if (message == null || message.isBlank()) {
            ctx.status(400).json(errorJson("validation_error", "message is required"));
            return;
        }
        if (address == null || address.isBlank()) {
            ctx.status(400).json(errorJson("validation_error", "address is required"));
            return;
        }
        if (signature == null || signature.isBlank()) {
            ctx.status(400).json(errorJson("validation_error", "signature is required"));
            return;
        }

        try {
            boolean valid = supervisor.execute(w -> w.verifyMessage(message.trim(), address.trim(), signature.trim()));
            ctx.json(Map.of(
                    "coin", ctx.pathParam("coin").trim().toLowerCase(),
                    "address", address.trim(),
                    "valid", valid
            ));
        } catch (WalletException e) {
            walletError(ctx, e);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Coin validateCoin(Context ctx) {
        String coinParam = ctx.pathParam("coin").trim().toLowerCase();
        KonkinConfig config = configManager.get();
        CoinConfig coinConfig = config.resolveCoinConfig(coinParam);

        if (coinConfig == null) {
            ctx.status(400).json(errorJson("unsupported_coin", "Unsupported coin: " + coinParam));
            return null;
        }
        if (!coinConfig.enabled()) {
            ctx.status(404).json(errorJson("coin_not_enabled", coinParam + " is currently disabled"));
            return null;
        }

        return switch (coinParam) {
            case "bitcoin" -> Coin.BTC;
            case "litecoin" -> Coin.LTC;
            case "monero" -> Coin.XMR;
            default -> {
                ctx.status(400).json(errorJson("unsupported_coin", "Unsupported coin: " + coinParam));
                yield null;
            }
        };
    }

    private WalletSupervisor requireSupervisor(Context ctx, Coin coin) {
        WalletSupervisor supervisor = walletSupervisors.get(coin);
        if (supervisor == null) {
            ctx.status(502).json(errorJson("wallet_offline", "No wallet supervisor for " + coin));
            return null;
        }
        return supervisor;
    }

    private void walletError(Context ctx, WalletException e) {
        if (e instanceof WalletConnectionException) {
            ctx.status(502).json(errorJson("wallet_offline", e.getMessage()));
        } else if (e instanceof WalletInsufficientFundsException ise) {
            ctx.status(422).json(errorJson("insufficient_funds",
                    "Requested " + ise.requested() + " but only " + ise.available() + " available"));
        } else {
            ctx.status(500).json(errorJson("wallet_error", e.getMessage()));
        }
    }

    private Map<String, Object> mapTransaction(Transaction tx) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("txId", tx.txId());
        entry.put("direction", tx.direction().name());
        entry.put("address", tx.address());
        entry.put("amount", tx.amount().toPlainString());
        entry.put("fee", tx.fee().toPlainString());
        entry.put("txKey", tx.txKey());
        entry.put("confirmations", tx.confirmations());
        entry.put("confirmed", tx.confirmed());
        entry.put("timestamp", tx.timestamp().toString());
        entry.put("extras", tx.extras());
        return entry;
    }

    private Map<String, Object> mapQueuedRequest(ApprovalRequestRow row) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", row.id());
        entry.put("coin", row.coin());
        entry.put("toolName", row.toolName());
        entry.put("toAddress", row.toAddress());
        entry.put("amountNative", row.amountNative());
        entry.put("feePolicy", row.feePolicy());
        entry.put("feeCapNative", row.feeCapNative());
        entry.put("memo", row.memo());
        entry.put("reason", row.reason());
        entry.put("state", row.state());
        entry.put("stateReasonText", row.stateReasonText());
        entry.put("requestedAt", row.requestedAt() != null ? row.requestedAt().toString() : null);
        entry.put("updatedAt", row.updatedAt() != null ? row.updatedAt().toString() : null);
        return entry;
    }

    private static Map<String, String> errorJson(String error, String message) {
        return Map.of("error", error, "message", message);
    }

    private static String stringField(Map<String, Object> body, String key) {
        Object val = body.get(key);
        return val == null ? null : val.toString();
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >>> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
