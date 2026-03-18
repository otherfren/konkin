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

import io.konkin.db.ApprovalRequestRepository;
import io.konkin.db.HistoryRepository;
import io.konkin.db.entity.ApprovalRequestRow;
import io.konkin.db.entity.ExecutionAttemptDetail;
import io.konkin.db.entity.StateTransitionRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class TransactionExecutionService {

    private static final Logger log = LoggerFactory.getLogger(TransactionExecutionService.class);
    private static final Duration EXECUTING_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration QUEUED_FOR_EXECUTION_TIMEOUT = Duration.ofHours(24);

    private static final String MODE_ALWAYS_QUEUE = "always-queue";

    private final Map<Coin, WalletSupervisor> walletSupervisors;
    private final ApprovalRequestRepository requestRepo;
    private final HistoryRepository historyRepo;
    private final Supplier<String> spendingQueueModeSupplier;
    private ScheduledExecutorService scheduler;

    public TransactionExecutionService(
            Map<Coin, WalletSupervisor> walletSupervisors,
            ApprovalRequestRepository requestRepo,
            HistoryRepository historyRepo
    ) {
        this(walletSupervisors, requestRepo, historyRepo, () -> "balance-required");
    }

    public TransactionExecutionService(
            Map<Coin, WalletSupervisor> walletSupervisors,
            ApprovalRequestRepository requestRepo,
            HistoryRepository historyRepo,
            Supplier<String> spendingQueueModeSupplier
    ) {
        this.walletSupervisors = walletSupervisors;
        this.requestRepo = requestRepo;
        this.historyRepo = historyRepo;
        this.spendingQueueModeSupplier = spendingQueueModeSupplier;
    }

    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "tx-execution-poller");
            t.setDaemon(true);
            return t;
        });

        scheduler.scheduleAtFixedRate(this::poll, 5, 5, TimeUnit.SECONDS);
        log.info("Transaction execution service started (interval=5s)");
    }

    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    private void poll() {
        try {
            sweepStaleExecuting();
        } catch (Exception e) {
            log.warn("Stale EXECUTING sweep failed: {}", e.getMessage());
        }

        try {
            sweepStaleQueuedForExecution();
        } catch (Exception e) {
            log.warn("Stale QUEUED_FOR_EXECUTION sweep failed: {}", e.getMessage());
        }

        // Phase 1: Immediately promote APPROVED → QUEUED_FOR_EXECUTION
        try {
            promoteApprovedToQueued();
        } catch (Exception e) {
            log.warn("Phase 1 (promote APPROVED) failed: {}", e.getMessage());
        }

        // Phase 2: Execute from QUEUED_FOR_EXECUTION based on spendable balance
        try {
            executeQueuedRequests();
        } catch (Exception e) {
            log.warn("Phase 2 (execute queued) failed: {}", e.getMessage());
        }
    }

    /**
     * Phase 1: Transition APPROVED requests to QUEUED_FOR_EXECUTION.
     * In "balance-required" mode, checks that requested_amount + already_queued_amount <= total_balance.
     * In "always-queue" mode, promotes unconditionally.
     */
    private void promoteApprovedToQueued() {
        List<ApprovalRequestRow> approved = requestRepo.findApprovedRequests();
        if (approved.isEmpty()) return;

        boolean balanceRequired = !MODE_ALWAYS_QUEUE.equals(spendingQueueModeSupplier.get());

        for (ApprovalRequestRow row : approved) {
            try {
                // Balance gate: check total balance covers this request + already queued/executing amounts
                if (balanceRequired && !"wallet_sweep".equals(row.toolName())) {
                    Coin coin = resolveCoin(row.coin());
                    WalletSupervisor supervisor = walletSupervisors.get(coin);

                    if (supervisor == null) {
                        // No supervisor — will fail later in Phase 2, promote anyway
                    } else {
                        WalletSnapshot snapshot = supervisor.snapshot();
                        BigDecimal totalBalance = snapshot.totalBalance();

                        if (totalBalance != null && row.amountNative() != null) {
                            BigDecimal requestedAmount = new BigDecimal(row.amountNative());
                            BigDecimal alreadyQueued = requestRepo.sumQueuedAndExecutingAmounts(row.coin());
                            BigDecimal totalNeeded = requestedAmount.add(alreadyQueued);

                            if (totalNeeded.compareTo(totalBalance) > 0) {
                                Instant now = Instant.now();
                                boolean claimed = requestRepo.compareAndSetState(row.id(), "APPROVED", "FAILED",
                                        "insufficient_total_balance",
                                        "Insufficient total balance: need " + totalNeeded.toPlainString()
                                                + " (request " + requestedAmount.toPlainString()
                                                + " + queued " + alreadyQueued.toPlainString()
                                                + ") but total balance is " + totalBalance.toPlainString(),
                                        now);
                                if (claimed) {
                                    historyRepo.insertStateTransition(new StateTransitionRow(
                                            0L, row.id(), "APPROVED", "FAILED",
                                            "system", "tx-execution-service", "insufficient_total_balance", now
                                    ));
                                    log.warn("Request {} rejected: total balance {} < needed {} (request {} + queued {})",
                                            row.id(), totalBalance.toPlainString(), totalNeeded.toPlainString(),
                                            requestedAmount.toPlainString(), alreadyQueued.toPlainString());
                                }
                                continue;
                            }
                        }
                    }
                }

                Instant now = Instant.now();
                boolean claimed = requestRepo.compareAndSetState(row.id(), "APPROVED", "QUEUED_FOR_EXECUTION",
                        "queued_for_execution", "Waiting for spendable balance", now);
                if (claimed) {
                    historyRepo.insertStateTransition(new StateTransitionRow(
                            0L, row.id(), "APPROVED", "QUEUED_FOR_EXECUTION",
                            "system", "tx-execution-service", "queued_for_execution", now
                    ));
                    log.info("Request {} promoted APPROVED → QUEUED_FOR_EXECUTION", row.id());
                }
            } catch (RuntimeException e) {
                log.warn("Failed to promote request {} to QUEUED_FOR_EXECUTION: {}", row.id(), e.getMessage());
            }
        }
    }

    /**
     * Phase 2: Execute QUEUED_FOR_EXECUTION requests gated by spendable balance.
     */
    private void executeQueuedRequests() {
        List<ApprovalRequestRow> queued = requestRepo.findQueuedForExecution();
        if (queued.isEmpty()) {
            return;
        }

        // Group by coin
        Map<String, List<ApprovalRequestRow>> byCoin = queued.stream()
                .collect(Collectors.groupingBy(ApprovalRequestRow::coin, LinkedHashMap::new, Collectors.toList()));

        for (var entry : byCoin.entrySet()) {
            String coinName = entry.getKey();
            List<ApprovalRequestRow> requests = entry.getValue();

            try {
                Coin coin = resolveCoin(coinName);
                WalletSupervisor supervisor = walletSupervisors.get(coin);

                if (supervisor == null) {
                    for (ApprovalRequestRow row : requests) {
                        String reason = "No wallet supervisor configured for " + coin;
                        log.error("{} — cannot execute request {}", reason, row.id());
                        transitionAndFail(row, "no_wallet_supervisor", reason);
                    }
                    continue;
                }

                WalletSnapshot snapshot = supervisor.snapshot();
                if (snapshot.status() == WalletStatus.OFFLINE) {
                    log.debug("Wallet {} is offline, skipping queued requests", coin);
                    continue;
                }

                BigDecimal spendable = snapshot.spendableBalance();
                if (spendable == null || spendable.compareTo(BigDecimal.ZERO) <= 0) {
                    log.debug("Wallet {} spendable balance is zero or null, skipping", coin);
                    continue;
                }

                // Greedily select requests that fit in spendable balance (FIFO order)
                List<ApprovalRequestRow> selected = new ArrayList<>();
                BigDecimal remaining = spendable;

                for (ApprovalRequestRow row : requests) {
                    // Sweep requests always "fit" — they send whatever is available
                    if ("wallet_sweep".equals(row.toolName())) {
                        selected.add(row);
                        continue;
                    }

                    BigDecimal amount = new BigDecimal(row.amountNative());
                    if (amount.compareTo(remaining) <= 0) {
                        selected.add(row);
                        remaining = remaining.subtract(amount);
                    }
                }

                if (selected.isEmpty()) {
                    log.debug("No queued requests for {} fit in spendable balance {}", coin, spendable);
                    continue;
                }

                // Try batch if multiple non-sweep requests and chain supports it
                List<ApprovalRequestRow> nonSweep = selected.stream()
                        .filter(r -> !"wallet_sweep".equals(r.toolName()))
                        .toList();

                if (nonSweep.size() > 1) {
                    try {
                        CoinWallet wallet = supervisor.execute(CoinWallet::coin) != null ? null : null;
                    } catch (Exception ignored) {}

                    boolean supportsBatch;
                    try {
                        supportsBatch = supervisor.execute(CoinWallet::supportsBatchSend);
                    } catch (Exception e) {
                        supportsBatch = false;
                    }

                    if (supportsBatch) {
                        tryBatchSend(coin, supervisor, nonSweep);
                    } else {
                        for (ApprovalRequestRow row : nonSweep) {
                            trySingleSend(coin, supervisor, row);
                        }
                    }
                } else {
                    for (ApprovalRequestRow row : nonSweep) {
                        trySingleSend(coin, supervisor, row);
                    }
                }

                // Sweeps are always sent individually
                for (ApprovalRequestRow row : selected) {
                    if ("wallet_sweep".equals(row.toolName())) {
                        trySingleSend(coin, supervisor, row);
                    }
                }

            } catch (RuntimeException e) {
                log.warn("Failed to process queued requests for {}: {}", coinName, e.getMessage());
            }
        }
    }

    private void tryBatchSend(Coin coin, WalletSupervisor supervisor, List<ApprovalRequestRow> requests) {
        Instant now = Instant.now();

        // Claim all as EXECUTING
        List<ApprovalRequestRow> claimed = new ArrayList<>();
        for (ApprovalRequestRow row : requests) {
            boolean ok = requestRepo.compareAndSetState(row.id(), "QUEUED_FOR_EXECUTION", "EXECUTING",
                    "executing_batch_send", "Batch transaction execution in progress", now);
            if (ok) {
                historyRepo.insertStateTransition(new StateTransitionRow(
                        0L, row.id(), "QUEUED_FOR_EXECUTION", "EXECUTING",
                        "system", "tx-execution-service", "executing_batch_send", now
                ));
                claimed.add(row);
            }
        }

        if (claimed.isEmpty()) {
            return;
        }

        try {
            // Build SendRequests
            List<SendRequest> sendRequests = new ArrayList<>();
            for (ApprovalRequestRow row : claimed) {
                Map<String, String> extras = buildExtras(row);
                BigDecimal amount = new BigDecimal(row.amountNative());
                sendRequests.add(new SendRequest(coin, row.toAddress(), amount, extras));
            }

            BatchSendResult result = supervisor.execute(w -> w.batchSend(sendRequests));
            Instant finished = Instant.now();

            // Mark all COMPLETED
            for (ApprovalRequestRow row : claimed) {
                ExecutionAttemptDetail success = new ExecutionAttemptDetail(
                        0L, row.id(), 1, now, finished,
                        "success", null, null,
                        result.txId(), result.totalFee().toPlainString()
                );
                historyRepo.insertExecutionAttempt(success);

                ApprovalRequestRow completed = new ApprovalRequestRow(
                        row.id(), row.coin(), row.toolName(), row.requestSessionId(),
                        row.nonceUuid(), row.payloadHashSha256(), row.nonceComposite(),
                        row.toAddress(), row.amountNative(), row.feePolicy(), row.feeCapNative(), row.memo(), row.reason(),
                        row.requestedAt(), row.expiresAt(),
                        "COMPLETED", "send_completed", "Batch transaction sent: " + result.txId(),
                        row.minApprovalsRequired(), row.approvalsGranted(), row.approvalsDenied(),
                        row.policyActionAtCreation(), row.createdAt(), finished, finished
                );
                requestRepo.updateApprovalRequest(completed);
                historyRepo.insertStateTransition(new StateTransitionRow(
                        0L, row.id(), "EXECUTING", "COMPLETED",
                        "system", "tx-execution-service", "send_completed", finished
                ));
            }

            log.info("Batch transaction executed for {} requests on {} — txId={}", claimed.size(), coin, result.txId());

        } catch (Exception e) {
            log.warn("Batch send failed for {} on {}, falling back to individual sends: {}", claimed.size(), coin, e.getMessage());

            // Revert all to QUEUED_FOR_EXECUTION, then try individually
            for (ApprovalRequestRow row : claimed) {
                Instant revertTime = Instant.now();
                requestRepo.compareAndSetState(row.id(), "EXECUTING", "QUEUED_FOR_EXECUTION",
                        "batch_fallback", "Batch send failed, retrying individually", revertTime);
                historyRepo.insertStateTransition(new StateTransitionRow(
                        0L, row.id(), "EXECUTING", "QUEUED_FOR_EXECUTION",
                        "system", "tx-execution-service", "batch_fallback", revertTime
                ));
            }

            for (ApprovalRequestRow row : claimed) {
                trySingleSend(coin, supervisor, row);
            }
        }
    }

    private void trySingleSend(Coin coin, WalletSupervisor supervisor, ApprovalRequestRow row) {
        Instant now = Instant.now();

        boolean claimed = requestRepo.compareAndSetState(row.id(), "QUEUED_FOR_EXECUTION", "EXECUTING",
                "executing_send", "Transaction execution in progress", now);
        if (!claimed) {
            log.debug("Request {} was already claimed, skipping", row.id());
            return;
        }
        historyRepo.insertStateTransition(new StateTransitionRow(
                0L, row.id(), "QUEUED_FOR_EXECUTION", "EXECUTING",
                "system", "tx-execution-service", "executing_send", now
        ));

        Map<String, String> extras = buildExtras(row);

        try {
            String txIdSummary;
            String feeSummary;

            if ("wallet_sweep".equals(row.toolName())) {
                SweepRequest sweepRequest = new SweepRequest(coin, row.toAddress(), extras);
                SweepResult result = supervisor.execute(w -> w.sweep(sweepRequest));
                txIdSummary = String.join(",", result.txIds());
                feeSummary = result.totalFee().toPlainString();
            } else {
                BigDecimal amount = new BigDecimal(row.amountNative());
                SendRequest sendRequest = new SendRequest(coin, row.toAddress(), amount, extras);
                SendResult result = supervisor.execute(w -> w.send(sendRequest));
                txIdSummary = result.txId();
                feeSummary = result.fee() != null ? result.fee().toPlainString() : null;
            }

            Instant finished = Instant.now();

            ExecutionAttemptDetail success = new ExecutionAttemptDetail(
                    0L, row.id(), 1, now, finished,
                    "success", null, null,
                    txIdSummary, feeSummary
            );
            historyRepo.insertExecutionAttempt(success);

            ApprovalRequestRow completed = new ApprovalRequestRow(
                    row.id(), row.coin(), row.toolName(), row.requestSessionId(),
                    row.nonceUuid(), row.payloadHashSha256(), row.nonceComposite(),
                    row.toAddress(), row.amountNative(), row.feePolicy(), row.feeCapNative(), row.memo(), row.reason(),
                    row.requestedAt(), row.expiresAt(),
                    "COMPLETED", "send_completed", "Transaction sent: " + txIdSummary,
                    row.minApprovalsRequired(), row.approvalsGranted(), row.approvalsDenied(),
                    row.policyActionAtCreation(), row.createdAt(), finished, finished
            );
            requestRepo.updateApprovalRequest(completed);
            historyRepo.insertStateTransition(new StateTransitionRow(
                    0L, row.id(), "EXECUTING", "COMPLETED",
                    "system", "tx-execution-service", "send_completed", finished
            ));

            log.info("Transaction executed for request {} — txId={}", row.id(), txIdSummary);

        } catch (WalletInsufficientFundsException e) {
            // Return to QUEUED_FOR_EXECUTION — funds will eventually be available
            requeueRequest(row, now, e, "insufficient_funds",
                    "Insufficient funds: requested " + e.requested() + " but only " + e.available() + " available, will retry");
        } catch (WalletConnectionException e) {
            // Return to QUEUED_FOR_EXECUTION — transient, will retry
            requeueRequest(row, now, e, "wallet_offline", "Wallet offline: " + e.getMessage() + ", will retry");
        } catch (WalletException e) {
            failRequest(row, now, e, "non_retryable_error", "wallet_error", "Wallet error: " + e.getMessage());
        } catch (Exception e) {
            failRequest(row, now, e, "non_retryable_error", "execution_error", "Execution error: " + e.getMessage());
        }
    }

    private void sweepStaleExecuting() {
        List<ApprovalRequestRow> executing = requestRepo.findByState("EXECUTING");
        Instant cutoff = Instant.now().minus(EXECUTING_TIMEOUT);

        for (ApprovalRequestRow row : executing) {
            if (row.updatedAt() != null && row.updatedAt().isBefore(cutoff)) {
                Instant now = Instant.now();
                ApprovalRequestRow failed = new ApprovalRequestRow(
                        row.id(), row.coin(), row.toolName(), row.requestSessionId(),
                        row.nonceUuid(), row.payloadHashSha256(), row.nonceComposite(),
                        row.toAddress(), row.amountNative(), row.feePolicy(), row.feeCapNative(), row.memo(), row.reason(),
                        row.requestedAt(), row.expiresAt(),
                        "FAILED", "execution_timeout", "Execution timed out after " + EXECUTING_TIMEOUT,
                        row.minApprovalsRequired(), row.approvalsGranted(), row.approvalsDenied(),
                        row.policyActionAtCreation(), row.createdAt(), now, now
                );
                requestRepo.updateApprovalRequest(failed);
                historyRepo.insertStateTransition(new StateTransitionRow(
                        0L, row.id(), "EXECUTING", "FAILED",
                        "system", "tx-execution-service", "execution_timeout", now
                ));
                log.warn("Request {} timed out in EXECUTING state (since {})", row.id(), row.updatedAt());
            }
        }
    }

    private void sweepStaleQueuedForExecution() {
        List<ApprovalRequestRow> queued = requestRepo.findQueuedForExecution();
        Instant cutoff = Instant.now().minus(QUEUED_FOR_EXECUTION_TIMEOUT);

        for (ApprovalRequestRow row : queued) {
            if (row.updatedAt() != null && row.updatedAt().isBefore(cutoff)) {
                Instant now = Instant.now();
                ApprovalRequestRow failed = new ApprovalRequestRow(
                        row.id(), row.coin(), row.toolName(), row.requestSessionId(),
                        row.nonceUuid(), row.payloadHashSha256(), row.nonceComposite(),
                        row.toAddress(), row.amountNative(), row.feePolicy(), row.feeCapNative(), row.memo(), row.reason(),
                        row.requestedAt(), row.expiresAt(),
                        "FAILED", "balance_wait_timeout", "Timed out waiting for spendable balance after " + QUEUED_FOR_EXECUTION_TIMEOUT,
                        row.minApprovalsRequired(), row.approvalsGranted(), row.approvalsDenied(),
                        row.policyActionAtCreation(), row.createdAt(), now, now
                );
                requestRepo.updateApprovalRequest(failed);
                historyRepo.insertStateTransition(new StateTransitionRow(
                        0L, row.id(), "QUEUED_FOR_EXECUTION", "FAILED",
                        "system", "tx-execution-service", "balance_wait_timeout", now
                ));
                log.warn("Request {} timed out in QUEUED_FOR_EXECUTION state (since {})", row.id(), row.updatedAt());
            }
        }
    }

    /**
     * Return a request from EXECUTING back to QUEUED_FOR_EXECUTION for retry.
     */
    private void requeueRequest(ApprovalRequestRow row, Instant startedAt, Exception e,
                                String reasonCode, String reasonText) {
        Instant now = Instant.now();

        ExecutionAttemptDetail attempt = new ExecutionAttemptDetail(
                0L, row.id(), 1, startedAt, now,
                "transient_error", e.getClass().getSimpleName(), e.getMessage(), null, null
        );
        historyRepo.insertExecutionAttempt(attempt);

        boolean reverted = requestRepo.compareAndSetState(row.id(), "EXECUTING", "QUEUED_FOR_EXECUTION",
                reasonCode, reasonText, now);
        if (reverted) {
            historyRepo.insertStateTransition(new StateTransitionRow(
                    0L, row.id(), "EXECUTING", "QUEUED_FOR_EXECUTION",
                    "system", "tx-execution-service", reasonCode, now
            ));
            log.info("Request {} returned to QUEUED_FOR_EXECUTION: {}", row.id(), reasonText);
        }
    }

    private void failRequest(ApprovalRequestRow row, Instant startedAt, Exception e,
                             String attemptResult, String reasonCode, String reasonText) {
        Instant finished = Instant.now();

        ExecutionAttemptDetail failure = new ExecutionAttemptDetail(
                0L, row.id(), 1, startedAt, finished,
                attemptResult, e.getClass().getSimpleName(), e.getMessage(), null, null
        );
        historyRepo.insertExecutionAttempt(failure);

        ApprovalRequestRow failed = new ApprovalRequestRow(
                row.id(), row.coin(), row.toolName(), row.requestSessionId(),
                row.nonceUuid(), row.payloadHashSha256(), row.nonceComposite(),
                row.toAddress(), row.amountNative(), row.feePolicy(), row.feeCapNative(), row.memo(), row.reason(),
                row.requestedAt(), row.expiresAt(),
                "FAILED", reasonCode, reasonText,
                row.minApprovalsRequired(), row.approvalsGranted(), row.approvalsDenied(),
                row.policyActionAtCreation(), row.createdAt(), finished, finished
        );
        requestRepo.updateApprovalRequest(failed);
        historyRepo.insertStateTransition(new StateTransitionRow(
                0L, row.id(), "EXECUTING", "FAILED",
                "system", "tx-execution-service", reasonCode, finished
        ));

        log.warn("Transaction failed for request {}: {}", row.id(), reasonText);
    }

    /**
     * Transition directly to FAILED from QUEUED_FOR_EXECUTION (e.g., no wallet supervisor).
     */
    private void transitionAndFail(ApprovalRequestRow row, String reasonCode, String reasonText) {
        Instant now = Instant.now();
        boolean claimed = requestRepo.compareAndSetState(row.id(), "QUEUED_FOR_EXECUTION", "EXECUTING",
                "executing_send", "Attempting execution", now);
        if (!claimed) return;

        historyRepo.insertStateTransition(new StateTransitionRow(
                0L, row.id(), "QUEUED_FOR_EXECUTION", "EXECUTING",
                "system", "tx-execution-service", "executing_send", now
        ));

        failRequest(row, now, new IllegalStateException(reasonText),
                "non_retryable_error", reasonCode, reasonText);
    }

    private static Map<String, String> buildExtras(ApprovalRequestRow row) {
        Map<String, String> extras = new LinkedHashMap<>();
        if (row.feePolicy() != null) extras.put("feePolicy", row.feePolicy());
        if (row.feeCapNative() != null) extras.put("feeCapNative", row.feeCapNative());
        if (row.memo() != null) extras.put("memo", row.memo());
        return extras;
    }

    private static Coin resolveCoin(String coin) {
        return switch (coin.toLowerCase()) {
            case "bitcoin" -> Coin.BTC;
            case "litecoin" -> Coin.LTC;
            case "monero" -> Coin.XMR;
            default -> throw new IllegalArgumentException("Unrecognized coin: " + coin);
        };
    }
}
