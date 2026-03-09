package io.konkin.db;

import io.konkin.TestDatabaseManager;
import io.konkin.db.entity.ApprovalRequestRow;
import io.konkin.db.entity.ExecutionAttemptDetail;
import io.konkin.db.entity.PageResult;
import io.konkin.db.entity.StateTransitionRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HistoryRepositoryTest {

    private static final DataSource dataSource = TestDatabaseManager.dataSource("history_repo_test");
    private HistoryRepository repo;

    @BeforeEach
    void setUp() {
        TestDatabaseManager.truncateAll(dataSource);
        repo = new HistoryRepository(dataSource);
        seedRequests();
    }

    private void seedRequests() {
        Instant now = Instant.now();
        ApprovalRequestRepository requestRepo = new ApprovalRequestRepository(dataSource);
        for (int i = 0; i < 6; i++) {
            requestRepo.insertApprovalRequest(new ApprovalRequestRow(
                    "req-" + i, "bitcoin", "send_coin", "sess-" + i, "nonce-" + i, "hash-" + i, "comp-" + i,
                    "addr-" + i, "1.0", null, null, null, "reason",
                    now, now.plusSeconds(3600), "QUEUED", null, null,
                    1, 0, 0, "require_approval", now, now, null));
        }
    }

    // --- StateTransition ---

    @Test
    void insertAndListStateTransitions() {
        Instant now = Instant.now();
        repo.insertStateTransition(new StateTransitionRow(
                0L, "req-0", "QUEUED", "PENDING", "system", "vote-service", "vote_received", now));

        List<StateTransitionRow> all = repo.listAllStateTransitions();
        assertEquals(1, all.size());
        assertEquals("req-0", all.getFirst().requestId());
        assertEquals("QUEUED", all.getFirst().fromState());
        assertEquals("PENDING", all.getFirst().toState());
        assertEquals("system", all.getFirst().actorType());
    }

    @Test
    void findStateTransitionById() {
        Instant now = Instant.now();
        repo.insertStateTransition(new StateTransitionRow(
                0L, "req-0", "PENDING", "APPROVED", "user", "admin", "approved", now));

        StateTransitionRow inserted = repo.listAllStateTransitions().getFirst();
        StateTransitionRow found = repo.findStateTransitionById(inserted.id());
        assertNotNull(found);
        assertEquals("APPROVED", found.toState());
    }

    @Test
    void findStateTransitionById_nonExistent_returnsNull() {
        assertNull(repo.findStateTransitionById(999L));
    }

    @Test
    void deleteStateTransition() {
        Instant now = Instant.now();
        repo.insertStateTransition(new StateTransitionRow(
                0L, "req-0", "QUEUED", "PENDING", "system", "test", "reason", now));

        StateTransitionRow inserted = repo.listAllStateTransitions().getFirst();
        assertTrue(repo.deleteStateTransition(inserted.id()));
        assertNull(repo.findStateTransitionById(inserted.id()));
    }

    @Test
    void deleteStateTransition_nonExistent_returnsFalse() {
        assertFalse(repo.deleteStateTransition(999L));
    }

    @Test
    void pageStateTransitions_defaultSort() {
        Instant now = Instant.now();
        for (int i = 0; i < 5; i++) {
            repo.insertStateTransition(new StateTransitionRow(
                    0L, "req-" + i, "QUEUED", "PENDING", "system", "test", "code", now.plusSeconds(i)));
        }

        PageResult<StateTransitionRow> page = repo.pageStateTransitions("created_at", "desc", 1, 3);
        assertEquals(3, page.rows().size());
        assertEquals(1, page.page());
        assertEquals(3, page.pageSize());
        assertEquals(5, page.totalRows());
        assertEquals(2, page.totalPages());
    }

    @Test
    void pageStateTransitions_invalidSortColumn_fallsBackToDefault() {
        Instant now = Instant.now();
        repo.insertStateTransition(new StateTransitionRow(
                0L, "req-0", "QUEUED", "PENDING", "system", "test", "code", now));

        PageResult<StateTransitionRow> page = repo.pageStateTransitions("invalid_column", "asc", 1, 10);
        assertEquals("created_at", page.sortBy());
        assertEquals(1, page.rows().size());
    }

    @Test
    void pageStateTransitions_emptyTable() {
        PageResult<StateTransitionRow> page = repo.pageStateTransitions("created_at", "desc", 1, 10);
        assertTrue(page.rows().isEmpty());
        assertEquals(0, page.totalRows());
        assertEquals(0, page.totalPages());
    }

    // --- ExecutionAttempt ---

    @Test
    void insertAndListExecutionAttempts() {
        Instant now = Instant.now();
        repo.insertExecutionAttempt(new ExecutionAttemptDetail(
                0L, "req-0", 1, now, now.plusSeconds(2), "success", null, null, "txid123", "0.0001"));

        List<ExecutionAttemptDetail> all = repo.listAllExecutionAttempts();
        assertEquals(1, all.size());
        assertEquals("req-0", all.getFirst().requestId());
        assertEquals(1, all.getFirst().attemptNo());
        assertEquals("success", all.getFirst().result());
        assertEquals("txid123", all.getFirst().txid());
        assertEquals("0.0001", all.getFirst().daemonFeeNative());
    }

    @Test
    void insertExecutionAttempt_failure() {
        Instant now = Instant.now();
        repo.insertExecutionAttempt(new ExecutionAttemptDetail(
                0L, "req-0", 1, now, now.plusSeconds(1),
                "non_retryable_error", "WalletConnectionException", "Wallet offline", null, null));

        ExecutionAttemptDetail found = repo.listAllExecutionAttempts().getFirst();
        assertEquals("non_retryable_error", found.result());
        assertEquals("WalletConnectionException", found.errorClass());
        assertEquals("Wallet offline", found.errorMessage());
        assertNull(found.txid());
    }

    @Test
    void findExecutionAttemptById() {
        Instant now = Instant.now();
        repo.insertExecutionAttempt(new ExecutionAttemptDetail(
                0L, "req-0", 1, now, now, "success", null, null, "tx1", "0.001"));

        ExecutionAttemptDetail inserted = repo.listAllExecutionAttempts().getFirst();
        ExecutionAttemptDetail found = repo.findExecutionAttemptById(inserted.id());
        assertNotNull(found);
        assertEquals("tx1", found.txid());
    }

    @Test
    void findExecutionAttemptById_nonExistent_returnsNull() {
        assertNull(repo.findExecutionAttemptById(999L));
    }

    @Test
    void deleteExecutionAttempt() {
        Instant now = Instant.now();
        repo.insertExecutionAttempt(new ExecutionAttemptDetail(
                0L, "req-0", 1, now, now, "success", null, null, "tx1", "0.001"));

        ExecutionAttemptDetail inserted = repo.listAllExecutionAttempts().getFirst();
        assertTrue(repo.deleteExecutionAttempt(inserted.id()));
        assertNull(repo.findExecutionAttemptById(inserted.id()));
    }

    @Test
    void deleteExecutionAttempt_nonExistent_returnsFalse() {
        assertFalse(repo.deleteExecutionAttempt(999L));
    }
}
