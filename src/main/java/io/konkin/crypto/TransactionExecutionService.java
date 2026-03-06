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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class TransactionExecutionService {

    private static final Logger log = LoggerFactory.getLogger(TransactionExecutionService.class);
    private static final Duration EXECUTING_TIMEOUT = Duration.ofMinutes(10);

    private final Map<Coin, WalletSupervisor> walletSupervisors;
    private final ApprovalRequestRepository requestRepo;
    private final HistoryRepository historyRepo;
    private ScheduledExecutorService scheduler;

    public TransactionExecutionService(
            Map<Coin, WalletSupervisor> walletSupervisors,
            ApprovalRequestRepository requestRepo,
            HistoryRepository historyRepo
    ) {
        this.walletSupervisors = walletSupervisors;
        this.requestRepo = requestRepo;
        this.historyRepo = historyRepo;
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
            List<ApprovalRequestRow> approved = requestRepo.findApprovedRequests();
            if (approved.isEmpty()) {
                return;
            }

            for (ApprovalRequestRow row : approved) {
                try {
                    executeRequest(row);
                } catch (RuntimeException e) {
                    log.warn("Failed to execute request {}: {}", row.id(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Execution poll failed: {}", e.getMessage());
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

    private void executeRequest(ApprovalRequestRow row) {
        Instant now = Instant.now();

        // [H-6] Optimistic locking: atomically transition APPROVED → EXECUTING
        boolean claimed = requestRepo.compareAndSetState(row.id(), "APPROVED", "EXECUTING",
                "executing_send", "Transaction execution in progress", now);
        if (!claimed) {
            log.debug("Request {} was already claimed by another poller, skipping", row.id());
            return;
        }
        historyRepo.insertStateTransition(new StateTransitionRow(
                0L, row.id(), "APPROVED", "EXECUTING",
                "system", "tx-execution-service", "executing_send", now
        ));

        // Build SendRequest
        BigDecimal amount = new BigDecimal(row.amountNative());
        Map<String, String> extras = new LinkedHashMap<>();
        if (row.feePolicy() != null) extras.put("feePolicy", row.feePolicy());
        if (row.feeCapNative() != null) extras.put("feeCapNative", row.feeCapNative());
        if (row.memo() != null) extras.put("memo", row.memo());

        Coin coin = resolveCoin(row.coin());
        SendRequest sendRequest = new SendRequest(coin, row.toAddress(), amount, extras);

        WalletSupervisor supervisor = walletSupervisors.get(coin);
        if (supervisor == null) {
            String reason = "No wallet supervisor configured for " + coin;
            log.error("{} — cannot execute request {}", reason, row.id());
            failRequest(row, now, new IllegalStateException(reason),
                    "non_retryable_error", "no_wallet_supervisor", reason);
            return;
        }

        try {
            SendResult result = supervisor.execute(w -> w.send(sendRequest));

            Instant finished = Instant.now();

            // Update attempt with success
            ExecutionAttemptDetail success = new ExecutionAttemptDetail(
                    0L, row.id(), 1, now, finished,
                    "success", null, null,
                    result.txId(), result.fee() != null ? result.fee().toPlainString() : null
            );
            historyRepo.insertExecutionAttempt(success);

            // Transition to COMPLETED
            ApprovalRequestRow completed = new ApprovalRequestRow(
                    row.id(), row.coin(), row.toolName(), row.requestSessionId(),
                    row.nonceUuid(), row.payloadHashSha256(), row.nonceComposite(),
                    row.toAddress(), row.amountNative(), row.feePolicy(), row.feeCapNative(), row.memo(), row.reason(),
                    row.requestedAt(), row.expiresAt(),
                    "COMPLETED", "send_completed", "Transaction sent: " + result.txId(),
                    row.minApprovalsRequired(), row.approvalsGranted(), row.approvalsDenied(),
                    row.policyActionAtCreation(), row.createdAt(), finished, finished
            );
            requestRepo.updateApprovalRequest(completed);
            historyRepo.insertStateTransition(new StateTransitionRow(
                    0L, row.id(), "EXECUTING", "COMPLETED",
                    "system", "tx-execution-service", "send_completed", finished
            ));

            log.info("Transaction executed for request {} — txId={}", row.id(), result.txId());

        } catch (WalletInsufficientFundsException e) {
            failRequest(row, now, e, "non_retryable_error", "insufficient_funds",
                    "Insufficient funds: requested " + e.requested() + " but only " + e.available() + " available");
        } catch (WalletConnectionException e) {
            failRequest(row, now, e, "transient_error", "wallet_offline", "Wallet offline: " + e.getMessage());
        } catch (WalletException e) {
            failRequest(row, now, e, "non_retryable_error", "wallet_error", "Wallet error: " + e.getMessage());
        } catch (Exception e) {
            failRequest(row, now, e, "non_retryable_error", "execution_error", "Execution error: " + e.getMessage());
        }
    }

    private void failRequest(ApprovalRequestRow row, Instant startedAt, Exception e,
                             String attemptResult, String reasonCode, String reasonText) {
        Instant finished = Instant.now();

        // Update attempt with failure
        ExecutionAttemptDetail failure = new ExecutionAttemptDetail(
                0L, row.id(), 1, startedAt, finished,
                attemptResult, e.getClass().getSimpleName(), e.getMessage(), null, null
        );
        historyRepo.insertExecutionAttempt(failure);

        // Transition to FAILED
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

    private static Coin resolveCoin(String coin) {
        return switch (coin.toLowerCase()) {
            case "bitcoin" -> Coin.BTC;
            case "litecoin" -> Coin.LTC;
            case "monero" -> Coin.XMR;
            default -> throw new IllegalArgumentException("Unrecognized coin: " + coin);
        };
    }
}