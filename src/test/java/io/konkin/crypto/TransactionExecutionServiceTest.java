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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TransactionExecutionServiceTest {

    private ApprovalRequestRepository requestRepo;
    private HistoryRepository historyRepo;
    private WalletSupervisor btcSupervisor;
    private TransactionExecutionService service;

    @BeforeEach
    void setUp() {
        requestRepo = mock(ApprovalRequestRepository.class);
        historyRepo = mock(HistoryRepository.class);
        btcSupervisor = mock(WalletSupervisor.class);
        service = new TransactionExecutionService(
                Map.of(Coin.BTC, btcSupervisor),
                requestRepo, historyRepo);
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

    @Test
    void startAndStop() {
        service.start();
        service.stop();
        service.stop(); // double-stop is safe
    }

    @Test
    void executeRequest_sendCoin_success() {
        ApprovalRequestRow row = approvedRequest("req-1", "bitcoin", "send_coin", "0.5");
        when(requestRepo.findApprovedRequests()).thenReturn(List.of(row));
        when(requestRepo.findByState("EXECUTING")).thenReturn(List.of());
        when(requestRepo.compareAndSetState(eq("req-1"), eq("APPROVED"), eq("EXECUTING"), any(), any(), any()))
                .thenReturn(true);

        SendResult sendResult = new SendResult(Coin.BTC, "txid-abc", new BigDecimal("0.5"), new BigDecimal("0.0001"), null);
        when(btcSupervisor.execute(any())).thenReturn(sendResult);

        service.start();
        try { Thread.sleep(6500); } catch (InterruptedException ignored) {}
        service.stop();

        // Verify state transition APPROVED → EXECUTING
        verify(requestRepo, atLeastOnce()).compareAndSetState(eq("req-1"), eq("APPROVED"), eq("EXECUTING"), any(), any(), any());

        // Verify execution attempt recorded
        ArgumentCaptor<ExecutionAttemptDetail> attemptCaptor = ArgumentCaptor.forClass(ExecutionAttemptDetail.class);
        verify(historyRepo, atLeastOnce()).insertExecutionAttempt(attemptCaptor.capture());
        ExecutionAttemptDetail attempt = attemptCaptor.getAllValues().stream()
                .filter(a -> "success".equals(a.result()))
                .findFirst().orElse(null);
        assertNotNull(attempt);
        assertEquals("txid-abc", attempt.txid());

        // Verify final state COMPLETED
        ArgumentCaptor<ApprovalRequestRow> reqCaptor = ArgumentCaptor.forClass(ApprovalRequestRow.class);
        verify(requestRepo, atLeastOnce()).updateApprovalRequest(reqCaptor.capture());
        assertTrue(reqCaptor.getAllValues().stream().anyMatch(r -> "COMPLETED".equals(r.state())));
    }

    @Test
    void executeRequest_claimFails_skips() throws InterruptedException {
        ApprovalRequestRow row = approvedRequest("req-1", "bitcoin", "send_coin", "1.0");
        when(requestRepo.findApprovedRequests()).thenReturn(List.of(row));
        when(requestRepo.findByState("EXECUTING")).thenReturn(List.of());
        when(requestRepo.compareAndSetState(eq("req-1"), eq("APPROVED"), eq("EXECUTING"), any(), any(), any()))
                .thenReturn(false);

        service.start();
        Thread.sleep(6500);
        service.stop();

        verify(requestRepo, never()).updateApprovalRequest(any());
        verifyNoInteractions(btcSupervisor);
    }

    @Test
    void executeRequest_noSupervisor_fails() throws InterruptedException {
        ApprovalRequestRow row = approvedRequest("req-1", "monero", "send_coin", "1.0");
        when(requestRepo.findApprovedRequests()).thenReturn(List.of(row));
        when(requestRepo.findByState("EXECUTING")).thenReturn(List.of());
        when(requestRepo.compareAndSetState(eq("req-1"), eq("APPROVED"), eq("EXECUTING"), any(), any(), any()))
                .thenReturn(true);

        service.start();
        Thread.sleep(6500);
        service.stop();

        ArgumentCaptor<ApprovalRequestRow> captor = ArgumentCaptor.forClass(ApprovalRequestRow.class);
        verify(requestRepo, atLeastOnce()).updateApprovalRequest(captor.capture());
        assertTrue(captor.getAllValues().stream().anyMatch(r -> "FAILED".equals(r.state())));
    }

    @Test
    void executeRequest_walletConnectionException_fails() throws InterruptedException {
        ApprovalRequestRow row = approvedRequest("req-1", "bitcoin", "send_coin", "1.0");
        when(requestRepo.findApprovedRequests()).thenReturn(List.of(row));
        when(requestRepo.findByState("EXECUTING")).thenReturn(List.of());
        when(requestRepo.compareAndSetState(eq("req-1"), eq("APPROVED"), eq("EXECUTING"), any(), any(), any()))
                .thenReturn(true);
        when(btcSupervisor.execute(any())).thenThrow(new WalletConnectionException("offline"));

        service.start();
        Thread.sleep(6500);
        service.stop();

        ArgumentCaptor<ApprovalRequestRow> captor = ArgumentCaptor.forClass(ApprovalRequestRow.class);
        verify(requestRepo, atLeastOnce()).updateApprovalRequest(captor.capture());
        assertTrue(captor.getAllValues().stream().anyMatch(r -> "FAILED".equals(r.state())));

        ArgumentCaptor<ExecutionAttemptDetail> attemptCaptor = ArgumentCaptor.forClass(ExecutionAttemptDetail.class);
        verify(historyRepo, atLeastOnce()).insertExecutionAttempt(attemptCaptor.capture());
        assertTrue(attemptCaptor.getAllValues().stream().anyMatch(a -> "transient_error".equals(a.result())));
    }

    @Test
    void executeRequest_insufficientFunds_fails() throws InterruptedException {
        ApprovalRequestRow row = approvedRequest("req-1", "bitcoin", "send_coin", "10.0");
        when(requestRepo.findApprovedRequests()).thenReturn(List.of(row));
        when(requestRepo.findByState("EXECUTING")).thenReturn(List.of());
        when(requestRepo.compareAndSetState(eq("req-1"), eq("APPROVED"), eq("EXECUTING"), any(), any(), any()))
                .thenReturn(true);
        when(btcSupervisor.execute(any())).thenThrow(
                new WalletInsufficientFundsException(new BigDecimal("10.0"), new BigDecimal("1.0")));

        service.start();
        Thread.sleep(6500);
        service.stop();

        ArgumentCaptor<ExecutionAttemptDetail> captor = ArgumentCaptor.forClass(ExecutionAttemptDetail.class);
        verify(historyRepo, atLeastOnce()).insertExecutionAttempt(captor.capture());
        assertTrue(captor.getAllValues().stream().anyMatch(a -> "non_retryable_error".equals(a.result())));
    }

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

        service.start();
        Thread.sleep(6500);
        service.stop();

        ArgumentCaptor<ApprovalRequestRow> captor = ArgumentCaptor.forClass(ApprovalRequestRow.class);
        verify(requestRepo, atLeastOnce()).updateApprovalRequest(captor.capture());
        assertTrue(captor.getAllValues().stream().anyMatch(r ->
                "FAILED".equals(r.state()) && "execution_timeout".equals(r.stateReasonCode())));
    }

    @Test
    void executeRequest_walletSweep_success() throws InterruptedException {
        ApprovalRequestRow row = approvedRequest("req-1", "bitcoin", "wallet_sweep", null);
        when(requestRepo.findApprovedRequests()).thenReturn(List.of(row));
        when(requestRepo.findByState("EXECUTING")).thenReturn(List.of());
        when(requestRepo.compareAndSetState(eq("req-1"), eq("APPROVED"), eq("EXECUTING"), any(), any(), any()))
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

    @Test
    void poll_noApprovedRequests_noop() throws InterruptedException {
        when(requestRepo.findApprovedRequests()).thenReturn(List.of());
        when(requestRepo.findByState("EXECUTING")).thenReturn(List.of());

        service.start();
        Thread.sleep(6500);
        service.stop();

        verify(requestRepo, never()).compareAndSetState(any(), any(), any(), any(), any(), any());
    }
}
