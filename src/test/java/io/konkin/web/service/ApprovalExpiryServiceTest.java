package io.konkin.web.service;

import io.konkin.db.ApprovalRequestRepository;
import io.konkin.db.HistoryRepository;
import io.konkin.db.entity.ApprovalRequestRow;
import io.konkin.db.entity.StateTransitionRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApprovalExpiryServiceTest {

    private ApprovalRequestRepository requestRepo;
    private HistoryRepository historyRepo;
    private ApprovalExpiryService service;

    @BeforeEach
    void setUp() {
        requestRepo = mock(ApprovalRequestRepository.class);
        historyRepo = mock(HistoryRepository.class);
        service = new ApprovalExpiryService(requestRepo, historyRepo);
    }

    @Test
    void startAndStop() {
        service.start();
        service.stop();
        // Verify no exceptions on double-stop
        service.stop();
    }

    @Test
    void stop_withoutStart_noException() {
        service.stop();
    }

    @Test
    void sweep_noExpiredRequests() throws InterruptedException {
        when(requestRepo.findExpiredPendingRequests()).thenReturn(List.of());

        service.start();
        Thread.sleep(6500); // Wait for first sweep at 5s
        service.stop();

        verify(requestRepo, atLeastOnce()).findExpiredPendingRequests();
        verify(requestRepo, never()).updateApprovalRequest(any());
    }

    @Test
    void sweep_expiresQueuedRequest() throws InterruptedException {
        Instant past = Instant.now().minusSeconds(300);
        ApprovalRequestRow expiredRow = new ApprovalRequestRow(
                "req-1", "bitcoin", "send_coin", "session-1",
                "nonce-1", "hash-1", "composite-1",
                "addr-1", "1.0", "normal", null, null, "test",
                past, past, "QUEUED", null, null,
                2, 0, 0, "require_approval",
                past, past, null);

        when(requestRepo.findExpiredPendingRequests()).thenReturn(List.of(expiredRow));

        service.start();
        Thread.sleep(6500); // Wait for first sweep at 5s
        service.stop();

        ArgumentCaptor<ApprovalRequestRow> reqCaptor = ArgumentCaptor.forClass(ApprovalRequestRow.class);
        verify(requestRepo, atLeastOnce()).updateApprovalRequest(reqCaptor.capture());
        ApprovalRequestRow updated = reqCaptor.getValue();
        assertEquals("EXPIRED", updated.state());
        assertEquals("request_expired", updated.stateReasonCode());

        ArgumentCaptor<StateTransitionRow> transCaptor = ArgumentCaptor.forClass(StateTransitionRow.class);
        verify(historyRepo, atLeastOnce()).insertStateTransition(transCaptor.capture());
        StateTransitionRow transition = transCaptor.getValue();
        assertEquals("QUEUED", transition.fromState());
        assertEquals("EXPIRED", transition.toState());
        assertEquals("system", transition.actorType());
        assertEquals("expiry-sweeper", transition.actorId());
    }

    @Test
    void sweep_handlesExceptionGracefully() throws InterruptedException {
        when(requestRepo.findExpiredPendingRequests()).thenThrow(new RuntimeException("DB error"));

        service.start();
        Thread.sleep(6500);
        service.stop();
        // Should not propagate exception — service continues running
        verify(requestRepo, atLeastOnce()).findExpiredPendingRequests();
    }
}
