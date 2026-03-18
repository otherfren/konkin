package io.konkin.crypto;

import io.konkin.db.ApprovalRequestRepository;
import io.konkin.db.HistoryRepository;
import io.konkin.db.entity.ApprovalRequestRow;
import io.konkin.db.entity.ExecutionAttemptDetail;
import io.konkin.db.entity.StateTransitionRow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TransactionExecutionServiceTest {

    private ApprovalRequestRepository requestRepo;
    private HistoryRepository historyRepo;
    private WalletSupervisor btcSupervisor;
    private TransactionExecutionService service;
    private AtomicReference<String> spendingQueueMode;

    @BeforeEach
    void setUp() {
        requestRepo = mock(ApprovalRequestRepository.class);
        historyRepo = mock(HistoryRepository.class);
        btcSupervisor = mock(WalletSupervisor.class);
        spendingQueueMode = new AtomicReference<>("balance-required");
        service = new TransactionExecutionService(
                Map.of(Coin.BTC, btcSupervisor),
                requestRepo, historyRepo, spendingQueueMode::get);
    }

    @AfterEach
    void tearDown() {
        service.stop();
    }

    private ApprovalRequestRow approvedRequest(String id, String coin, String toolName, String amount) {
        Instant now = Instant.now();
        return new ApprovalRequestRow(
                id, coin, toolName, "session-1",
                "nonce-1", "hash-1", "composite-1",
                "addr-1", amount, "normal", null, null, "test",
                now, now.plusSeconds(3600), "APPROVED", "approved", "Approved",
                1, 1, 0, "require_approval",
                now, now, null);
    }

    private ApprovalRequestRow queuedRequest(String id, String coin, String toolName, String amount) {
        Instant now = Instant.now();
        return new ApprovalRequestRow(
                id, coin, toolName, "session-1",
                "nonce-1", "hash-1", "composite-1",
                "addr-1", amount, "normal", null, null, "test",
                now, now.plusSeconds(3600), "QUEUED_FOR_EXECUTION", "queued_for_execution", "Waiting for spendable balance",
                1, 1, 0, "require_approval",
                now, now, null);
    }

    @Test
    void startAndStop() {
        service.start();
        service.stop();
        service.stop(); // double-stop is safe
    }

    // ── Phase 1: APPROVED → QUEUED_FOR_EXECUTION ─────────────────────────────

    @Test
    void phase1_promotesApprovedToQueuedForExecution() throws InterruptedException {
        ApprovalRequestRow row = approvedRequest("req-1", "bitcoin", "send_coin", "0.5");
        when(requestRepo.findApprovedRequests()).thenReturn(List.of(row));
        when(requestRepo.findQueuedForExecution()).thenReturn(List.of());
        when(requestRepo.findByState("EXECUTING")).thenReturn(List.of());

        // Balance check: total=10, queued=0, request=0.5 → fits
        WalletSnapshot snapshot = new WalletSnapshot(Coin.BTC, WalletStatus.AVAILABLE,
                new BigDecimal("10.0"), new BigDecimal("5.0"), Instant.now());
        when(btcSupervisor.snapshot()).thenReturn(snapshot);
        when(requestRepo.sumQueuedAndExecutingAmounts("bitcoin")).thenReturn(BigDecimal.ZERO);

        when(requestRepo.compareAndSetState(eq("req-1"), eq("APPROVED"), eq("QUEUED_FOR_EXECUTION"), any(), any(), any()))
                .thenReturn(true);

        service.start();
        Thread.sleep(6500);
        service.stop();

        verify(requestRepo, atLeastOnce()).compareAndSetState(
                eq("req-1"), eq("APPROVED"), eq("QUEUED_FOR_EXECUTION"), any(), any(), any());
    }

    @Test
    void phase1_balanceRequired_rejectsWhenTotalInsufficient() throws InterruptedException {
        ApprovalRequestRow row = approvedRequest("req-1", "bitcoin", "send_coin", "5.0");
        when(requestRepo.findApprovedRequests()).thenReturn(List.of(row));
        when(requestRepo.findQueuedForExecution()).thenReturn(List.of());
        when(requestRepo.findByState("EXECUTING")).thenReturn(List.of());

        // Total balance = 3.0, request = 5.0, queued = 0 → 5.0 > 3.0 → reject
        WalletSnapshot snapshot = new WalletSnapshot(Coin.BTC, WalletStatus.AVAILABLE,
                new BigDecimal("3.0"), new BigDecimal("3.0"), Instant.now());
        when(btcSupervisor.snapshot()).thenReturn(snapshot);
        when(requestRepo.sumQueuedAndExecutingAmounts("bitcoin")).thenReturn(BigDecimal.ZERO);

        when(requestRepo.compareAndSetState(eq("req-1"), eq("APPROVED"), eq("FAILED"), any(), any(), any()))
                .thenReturn(true);

        service.start();
        Thread.sleep(6500);
        service.stop();

        // Should transition directly to FAILED, not QUEUED_FOR_EXECUTION
        verify(requestRepo, atLeastOnce()).compareAndSetState(
                eq("req-1"), eq("APPROVED"), eq("FAILED"),
                eq("insufficient_total_balance"), any(), any());

        // Should NOT be promoted to QUEUED_FOR_EXECUTION
        verify(requestRepo, never()).compareAndSetState(
                eq("req-1"), eq("APPROVED"), eq("QUEUED_FOR_EXECUTION"), any(), any(), any());
    }

    @Test
    void phase1_balanceRequired_accountsForAlreadyQueuedAmounts() throws InterruptedException {
        ApprovalRequestRow row = approvedRequest("req-1", "bitcoin", "send_coin", "3.0");
        when(requestRepo.findApprovedRequests()).thenReturn(List.of(row));
        when(requestRepo.findQueuedForExecution()).thenReturn(List.of());
        when(requestRepo.findByState("EXECUTING")).thenReturn(List.of());

        // Total balance = 5.0, already queued = 3.0, request = 3.0 → 3.0 + 3.0 = 6.0 > 5.0 → reject
        WalletSnapshot snapshot = new WalletSnapshot(Coin.BTC, WalletStatus.AVAILABLE,
                new BigDecimal("5.0"), new BigDecimal("2.0"), Instant.now());
        when(btcSupervisor.snapshot()).thenReturn(snapshot);
        when(requestRepo.sumQueuedAndExecutingAmounts("bitcoin")).thenReturn(new BigDecimal("3.0"));

        when(requestRepo.compareAndSetState(eq("req-1"), eq("APPROVED"), eq("FAILED"), any(), any(), any()))
                .thenReturn(true);

        service.start();
        Thread.sleep(6500);
        service.stop();

        verify(requestRepo, atLeastOnce()).compareAndSetState(
                eq("req-1"), eq("APPROVED"), eq("FAILED"),
                eq("insufficient_total_balance"), any(), any());
    }

    @Test
    void phase1_balanceRequired_allowsWhenQueuedPlusRequestFitsInTotal() throws InterruptedException {
        ApprovalRequestRow row = approvedRequest("req-1", "bitcoin", "send_coin", "2.0");
        when(requestRepo.findApprovedRequests()).thenReturn(List.of(row));
        when(requestRepo.findQueuedForExecution()).thenReturn(List.of());
        when(requestRepo.findByState("EXECUTING")).thenReturn(List.of());

        // Total balance = 10.0, already queued = 3.0, request = 2.0 → 3.0 + 2.0 = 5.0 <= 10.0 → allow
        WalletSnapshot snapshot = new WalletSnapshot(Coin.BTC, WalletStatus.AVAILABLE,
                new BigDecimal("10.0"), new BigDecimal("5.0"), Instant.now());
        when(btcSupervisor.snapshot()).thenReturn(snapshot);
        when(requestRepo.sumQueuedAndExecutingAmounts("bitcoin")).thenReturn(new BigDecimal("3.0"));

        when(requestRepo.compareAndSetState(eq("req-1"), eq("APPROVED"), eq("QUEUED_FOR_EXECUTION"), any(), any(), any()))
                .thenReturn(true);

        service.start();
        Thread.sleep(6500);
        service.stop();

        verify(requestRepo, atLeastOnce()).compareAndSetState(
                eq("req-1"), eq("APPROVED"), eq("QUEUED_FOR_EXECUTION"), any(), any(), any());
    }

    @Test
    void phase1_alwaysQueue_promotesEvenWhenTotalInsufficient() throws InterruptedException {
        spendingQueueMode.set("always-queue");

        ApprovalRequestRow row = approvedRequest("req-1", "bitcoin", "send_coin", "100.0");
        when(requestRepo.findApprovedRequests()).thenReturn(List.of(row));
        when(requestRepo.findQueuedForExecution()).thenReturn(List.of());
        when(requestRepo.findByState("EXECUTING")).thenReturn(List.of());

        // Total balance = 1.0, request = 100.0 → but always-queue mode, so promote anyway
        WalletSnapshot snapshot = new WalletSnapshot(Coin.BTC, WalletStatus.AVAILABLE,
                new BigDecimal("1.0"), new BigDecimal("1.0"), Instant.now());
        when(btcSupervisor.snapshot()).thenReturn(snapshot);

        when(requestRepo.compareAndSetState(eq("req-1"), eq("APPROVED"), eq("QUEUED_FOR_EXECUTION"), any(), any(), any()))
                .thenReturn(true);

        service.start();
        Thread.sleep(6500);
        service.stop();

        verify(requestRepo, atLeastOnce()).compareAndSetState(
                eq("req-1"), eq("APPROVED"), eq("QUEUED_FOR_EXECUTION"), any(), any(), any());

        // Should NOT check balance or fail
        verify(requestRepo, never()).compareAndSetState(
                eq("req-1"), eq("APPROVED"), eq("FAILED"), any(), any(), any());
        verify(requestRepo, never()).sumQueuedAndExecutingAmounts(any());
    }

    @Test
    void phase1_balanceRequired_sweepBypassesBalanceCheck() throws InterruptedException {
        ApprovalRequestRow row = approvedRequest("req-1", "bitcoin", "wallet_sweep", null);
        when(requestRepo.findApprovedRequests()).thenReturn(List.of(row));
        when(requestRepo.findQueuedForExecution()).thenReturn(List.of());
        when(requestRepo.findByState("EXECUTING")).thenReturn(List.of());

        when(requestRepo.compareAndSetState(eq("req-1"), eq("APPROVED"), eq("QUEUED_FOR_EXECUTION"), any(), any(), any()))
                .thenReturn(true);

        service.start();
        Thread.sleep(6500);
        service.stop();

        // Sweep should be promoted regardless of balance
        verify(requestRepo, atLeastOnce()).compareAndSetState(
                eq("req-1"), eq("APPROVED"), eq("QUEUED_FOR_EXECUTION"), any(), any(), any());
        // Should NOT query balance for sweeps
        verify(requestRepo, never()).sumQueuedAndExecutingAmounts(any());
    }

    // ── Phase 2: QUEUED_FOR_EXECUTION → EXECUTING ────────────────────────────

    @Test
    void phase2_executesQueuedRequest_success() throws InterruptedException {
        when(requestRepo.findApprovedRequests()).thenReturn(List.of());
        when(requestRepo.findByState("EXECUTING")).thenReturn(List.of());

        ApprovalRequestRow queuedRow = queuedRequest("req-1", "bitcoin", "send_coin", "0.5");
        when(requestRepo.findQueuedForExecution()).thenReturn(List.of(queuedRow));

        WalletSnapshot snapshot = new WalletSnapshot(Coin.BTC, WalletStatus.AVAILABLE,
                new BigDecimal("1.0"), new BigDecimal("1.0"), Instant.now());
        when(btcSupervisor.snapshot()).thenReturn(snapshot);

        when(requestRepo.compareAndSetState(eq("req-1"), eq("QUEUED_FOR_EXECUTION"), eq("EXECUTING"), any(), any(), any()))
                .thenReturn(true);

        SendResult sendResult = new SendResult(Coin.BTC, "txid-abc", new BigDecimal("0.5"), new BigDecimal("0.0001"), null);
        when(btcSupervisor.execute(any())).thenReturn(sendResult);

        service.start();
        Thread.sleep(6500);
        service.stop();

        verify(requestRepo, atLeastOnce()).compareAndSetState(
                eq("req-1"), eq("QUEUED_FOR_EXECUTION"), eq("EXECUTING"), any(), any(), any());

        ArgumentCaptor<ExecutionAttemptDetail> attemptCaptor = ArgumentCaptor.forClass(ExecutionAttemptDetail.class);
        verify(historyRepo, atLeastOnce()).insertExecutionAttempt(attemptCaptor.capture());
        ExecutionAttemptDetail attempt = attemptCaptor.getAllValues().stream()
                .filter(a -> "success".equals(a.result()))
                .findFirst().orElse(null);
        assertNotNull(attempt);
        assertEquals("txid-abc", attempt.txid());

        ArgumentCaptor<ApprovalRequestRow> reqCaptor = ArgumentCaptor.forClass(ApprovalRequestRow.class);
        verify(requestRepo, atLeastOnce()).updateApprovalRequest(reqCaptor.capture());
        assertTrue(reqCaptor.getAllValues().stream().anyMatch(r -> "COMPLETED".equals(r.state())));
    }

    @Test
    void phase2_insufficientSpendable_skipsRequest() throws InterruptedException {
        when(requestRepo.findApprovedRequests()).thenReturn(List.of());
        when(requestRepo.findByState("EXECUTING")).thenReturn(List.of());

        ApprovalRequestRow queuedRow = queuedRequest("req-1", "bitcoin", "send_coin", "5.0");
        when(requestRepo.findQueuedForExecution()).thenReturn(List.of(queuedRow));

        WalletSnapshot snapshot = new WalletSnapshot(Coin.BTC, WalletStatus.AVAILABLE,
                new BigDecimal("10.0"), new BigDecimal("1.0"), Instant.now());
        when(btcSupervisor.snapshot()).thenReturn(snapshot);

        service.start();
        Thread.sleep(6500);
        service.stop();

        verify(requestRepo, never()).compareAndSetState(
                eq("req-1"), eq("QUEUED_FOR_EXECUTION"), eq("EXECUTING"), any(), any(), any());
    }

    @Test
    void phase2_insufficientFundsAtExecution_requeues() throws InterruptedException {
        when(requestRepo.findApprovedRequests()).thenReturn(List.of());
        when(requestRepo.findByState("EXECUTING")).thenReturn(List.of());

        ApprovalRequestRow queuedRow = queuedRequest("req-1", "bitcoin", "send_coin", "0.5");
        when(requestRepo.findQueuedForExecution()).thenReturn(List.of(queuedRow));

        WalletSnapshot snapshot = new WalletSnapshot(Coin.BTC, WalletStatus.AVAILABLE,
                new BigDecimal("1.0"), new BigDecimal("1.0"), Instant.now());
        when(btcSupervisor.snapshot()).thenReturn(snapshot);

        when(requestRepo.compareAndSetState(eq("req-1"), eq("QUEUED_FOR_EXECUTION"), eq("EXECUTING"), any(), any(), any()))
                .thenReturn(true);
        when(btcSupervisor.execute(any())).thenThrow(
                new WalletInsufficientFundsException(new BigDecimal("0.5"), new BigDecimal("0.3")));
        when(requestRepo.compareAndSetState(eq("req-1"), eq("EXECUTING"), eq("QUEUED_FOR_EXECUTION"), any(), any(), any()))
                .thenReturn(true);

        service.start();
        Thread.sleep(6500);
        service.stop();

        verify(requestRepo, atLeastOnce()).compareAndSetState(
                eq("req-1"), eq("EXECUTING"), eq("QUEUED_FOR_EXECUTION"), any(), any(), any());
    }

    @Test
    void phase2_walletConnectionException_requeues() throws InterruptedException {
        when(requestRepo.findApprovedRequests()).thenReturn(List.of());
        when(requestRepo.findByState("EXECUTING")).thenReturn(List.of());

        ApprovalRequestRow queuedRow = queuedRequest("req-1", "bitcoin", "send_coin", "1.0");
        when(requestRepo.findQueuedForExecution()).thenReturn(List.of(queuedRow));

        WalletSnapshot snapshot = new WalletSnapshot(Coin.BTC, WalletStatus.AVAILABLE,
                new BigDecimal("2.0"), new BigDecimal("2.0"), Instant.now());
        when(btcSupervisor.snapshot()).thenReturn(snapshot);

        when(requestRepo.compareAndSetState(eq("req-1"), eq("QUEUED_FOR_EXECUTION"), eq("EXECUTING"), any(), any(), any()))
                .thenReturn(true);
        when(btcSupervisor.execute(any())).thenThrow(new WalletConnectionException("offline"));
        when(requestRepo.compareAndSetState(eq("req-1"), eq("EXECUTING"), eq("QUEUED_FOR_EXECUTION"), any(), any(), any()))
                .thenReturn(true);

        service.start();
        Thread.sleep(6500);
        service.stop();

        verify(requestRepo, atLeastOnce()).compareAndSetState(
                eq("req-1"), eq("EXECUTING"), eq("QUEUED_FOR_EXECUTION"), any(), any(), any());
    }

    @Test
    void phase2_walletException_fails() throws InterruptedException {
        when(requestRepo.findApprovedRequests()).thenReturn(List.of());
        when(requestRepo.findByState("EXECUTING")).thenReturn(List.of());

        ApprovalRequestRow queuedRow = queuedRequest("req-1", "bitcoin", "send_coin", "1.0");
        when(requestRepo.findQueuedForExecution()).thenReturn(List.of(queuedRow));

        WalletSnapshot snapshot = new WalletSnapshot(Coin.BTC, WalletStatus.AVAILABLE,
                new BigDecimal("2.0"), new BigDecimal("2.0"), Instant.now());
        when(btcSupervisor.snapshot()).thenReturn(snapshot);

        when(requestRepo.compareAndSetState(eq("req-1"), eq("QUEUED_FOR_EXECUTION"), eq("EXECUTING"), any(), any(), any()))
                .thenReturn(true);
        when(btcSupervisor.execute(any())).thenThrow(new WalletOperationException("bad address"));

        service.start();
        Thread.sleep(6500);
        service.stop();

        ArgumentCaptor<ApprovalRequestRow> captor = ArgumentCaptor.forClass(ApprovalRequestRow.class);
        verify(requestRepo, atLeastOnce()).updateApprovalRequest(captor.capture());
        assertTrue(captor.getAllValues().stream().anyMatch(r -> "FAILED".equals(r.state())));
    }

    @Test
    void phase2_noSupervisor_failsRequest() throws InterruptedException {
        when(requestRepo.findApprovedRequests()).thenReturn(List.of());
        when(requestRepo.findByState("EXECUTING")).thenReturn(List.of());

        ApprovalRequestRow queuedRow = queuedRequest("req-1", "monero", "send_coin", "1.0");
        when(requestRepo.findQueuedForExecution()).thenReturn(List.of(queuedRow));

        when(requestRepo.compareAndSetState(eq("req-1"), eq("QUEUED_FOR_EXECUTION"), eq("EXECUTING"), any(), any(), any()))
                .thenReturn(true);

        service.start();
        Thread.sleep(6500);
        service.stop();

        ArgumentCaptor<ApprovalRequestRow> captor = ArgumentCaptor.forClass(ApprovalRequestRow.class);
        verify(requestRepo, atLeastOnce()).updateApprovalRequest(captor.capture());
        assertTrue(captor.getAllValues().stream().anyMatch(r -> "FAILED".equals(r.state())));
    }

    @Test
    void phase2_walletSweep_success() throws InterruptedException {
        when(requestRepo.findApprovedRequests()).thenReturn(List.of());
        when(requestRepo.findByState("EXECUTING")).thenReturn(List.of());

        ApprovalRequestRow queuedRow = queuedRequest("req-1", "bitcoin", "wallet_sweep", null);
        when(requestRepo.findQueuedForExecution()).thenReturn(List.of(queuedRow));

        WalletSnapshot snapshot = new WalletSnapshot(Coin.BTC, WalletStatus.AVAILABLE,
                new BigDecimal("5.0"), new BigDecimal("5.0"), Instant.now());
        when(btcSupervisor.snapshot()).thenReturn(snapshot);

        when(requestRepo.compareAndSetState(eq("req-1"), eq("QUEUED_FOR_EXECUTION"), eq("EXECUTING"), any(), any(), any()))
                .thenReturn(true);

        SweepResult sweepResult = new SweepResult(Coin.BTC, List.of("tx1", "tx2"),
                new BigDecimal("5.0"), new BigDecimal("0.001"), null);
        when(btcSupervisor.execute(any())).thenReturn(sweepResult);

        service.start();
        Thread.sleep(6500);
        service.stop();

        ArgumentCaptor<ExecutionAttemptDetail> captor = ArgumentCaptor.forClass(ExecutionAttemptDetail.class);
        verify(historyRepo, atLeastOnce()).insertExecutionAttempt(captor.capture());
        ExecutionAttemptDetail success = captor.getAllValues().stream()
                .filter(a -> "success".equals(a.result()))
                .findFirst().orElse(null);
        assertNotNull(success);
        assertEquals("tx1,tx2", success.txid());
        assertEquals("0.001", success.daemonFeeNative());
    }

    // ── Timeout sweeps ───────────────────────────────────────────────────────

    @Test
    void sweepStaleExecuting_timesOutStaleRequests() throws InterruptedException {
        Instant staleTime = Instant.now().minusSeconds(700); // > 10 min ago
        ApprovalRequestRow staleRow = new ApprovalRequestRow(
                "req-stale", "bitcoin", "send_coin", "session-1",
                "nonce-1", "hash-1", "composite-1",
                "addr-1", "1.0", null, null, null, "test",
                staleTime, staleTime.plusSeconds(3600), "EXECUTING", "executing", "In progress",
                1, 1, 0, "require_approval",
                staleTime, staleTime, null);

        when(requestRepo.findByState("EXECUTING")).thenReturn(List.of(staleRow));
        when(requestRepo.findApprovedRequests()).thenReturn(List.of());
        when(requestRepo.findQueuedForExecution()).thenReturn(List.of());

        service.start();
        Thread.sleep(6500);
        service.stop();

        ArgumentCaptor<ApprovalRequestRow> captor = ArgumentCaptor.forClass(ApprovalRequestRow.class);
        verify(requestRepo, atLeastOnce()).updateApprovalRequest(captor.capture());
        assertTrue(captor.getAllValues().stream().anyMatch(r ->
                "FAILED".equals(r.state()) && "execution_timeout".equals(r.stateReasonCode())));
    }

    @Test
    void sweepStaleQueuedForExecution_timesOutStaleRequests() throws InterruptedException {
        Instant staleTime = Instant.now().minusSeconds(90000); // > 24h ago
        ApprovalRequestRow staleRow = new ApprovalRequestRow(
                "req-stale", "bitcoin", "send_coin", "session-1",
                "nonce-1", "hash-1", "composite-1",
                "addr-1", "1.0", null, null, null, "test",
                staleTime, staleTime.plusSeconds(3600), "QUEUED_FOR_EXECUTION", "queued_for_execution", "Waiting",
                1, 1, 0, "require_approval",
                staleTime, staleTime, null);

        when(requestRepo.findByState("EXECUTING")).thenReturn(List.of());
        when(requestRepo.findApprovedRequests()).thenReturn(List.of());
        when(requestRepo.findQueuedForExecution()).thenReturn(List.of(staleRow));

        service.start();
        Thread.sleep(6500);
        service.stop();

        ArgumentCaptor<ApprovalRequestRow> captor = ArgumentCaptor.forClass(ApprovalRequestRow.class);
        verify(requestRepo, atLeastOnce()).updateApprovalRequest(captor.capture());
        assertTrue(captor.getAllValues().stream().anyMatch(r ->
                "FAILED".equals(r.state()) && "balance_wait_timeout".equals(r.stateReasonCode())));
    }

    // ── Edge cases ───────────────────────────────────────────────────────────

    @Test
    void poll_noRequestsAtAll_noop() throws InterruptedException {
        when(requestRepo.findApprovedRequests()).thenReturn(List.of());
        when(requestRepo.findQueuedForExecution()).thenReturn(List.of());
        when(requestRepo.findByState("EXECUTING")).thenReturn(List.of());

        service.start();
        Thread.sleep(6500);
        service.stop();

        verify(requestRepo, never()).compareAndSetState(any(), any(), any(), any(), any(), any());
    }

    @Test
    void phase2_walletOffline_skipsRequests() throws InterruptedException {
        when(requestRepo.findApprovedRequests()).thenReturn(List.of());
        when(requestRepo.findByState("EXECUTING")).thenReturn(List.of());

        ApprovalRequestRow queuedRow = queuedRequest("req-1", "bitcoin", "send_coin", "0.5");
        when(requestRepo.findQueuedForExecution()).thenReturn(List.of(queuedRow));

        WalletSnapshot snapshot = new WalletSnapshot(Coin.BTC, WalletStatus.OFFLINE, null, null, null);
        when(btcSupervisor.snapshot()).thenReturn(snapshot);

        service.start();
        Thread.sleep(6500);
        service.stop();

        verify(requestRepo, never()).compareAndSetState(
                eq("req-1"), eq("QUEUED_FOR_EXECUTION"), eq("EXECUTING"), any(), any(), any());
    }

    @Test
    void phase1_defaultConstructor_usesBalanceRequired() throws InterruptedException {
        // Test that the no-supplier constructor defaults to balance-required
        TransactionExecutionService defaultService = new TransactionExecutionService(
                Map.of(Coin.BTC, btcSupervisor), requestRepo, historyRepo);

        ApprovalRequestRow row = approvedRequest("req-1", "bitcoin", "send_coin", "100.0");
        when(requestRepo.findApprovedRequests()).thenReturn(List.of(row));
        when(requestRepo.findQueuedForExecution()).thenReturn(List.of());
        when(requestRepo.findByState("EXECUTING")).thenReturn(List.of());

        // Total balance = 1.0, request = 100.0 → should fail with balance-required default
        WalletSnapshot snapshot = new WalletSnapshot(Coin.BTC, WalletStatus.AVAILABLE,
                new BigDecimal("1.0"), new BigDecimal("1.0"), Instant.now());
        when(btcSupervisor.snapshot()).thenReturn(snapshot);
        when(requestRepo.sumQueuedAndExecutingAmounts("bitcoin")).thenReturn(BigDecimal.ZERO);
        when(requestRepo.compareAndSetState(eq("req-1"), eq("APPROVED"), eq("FAILED"), any(), any(), any()))
                .thenReturn(true);

        defaultService.start();
        Thread.sleep(6500);
        defaultService.stop();

        verify(requestRepo, atLeastOnce()).compareAndSetState(
                eq("req-1"), eq("APPROVED"), eq("FAILED"),
                eq("insufficient_total_balance"), any(), any());
    }
}
