package io.konkin.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.konkin.db.entity.ApprovalRequestRow;
import io.konkin.db.entity.LogQueueFilterOptions;
import io.konkin.db.entity.PageResult;
import io.konkin.db.entity.RequestDependencies;
import io.konkin.db.entity.StateTransitionRow;
import org.flywaydb.core.Flyway;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthQueueStoreTest {

    private static HikariDataSource dataSource;
    private static Jdbi jdbi;
    private ApprovalRequestRepository requestRepo;
    private HistoryRepository historyRepo;
    private RequestDependencyLoader depLoader;

    @BeforeAll
    static void setUpDatabase() {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:h2:mem:authqueuetest_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        cfg.setUsername("sa");
        cfg.setPassword("");
        cfg.setMaximumPoolSize(2);
        dataSource = new HikariDataSource(cfg);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbi = JdbiFactory.create(dataSource);
    }

    @AfterAll
    static void tearDown() {
        if (dataSource != null) dataSource.close();
    }

    @BeforeEach
    void setUp() {
        requestRepo = new ApprovalRequestRepository(dataSource);
        historyRepo = new HistoryRepository(dataSource);
        depLoader = new RequestDependencyLoader(dataSource);
        jdbi.useHandle(h -> {
            h.execute("DELETE FROM approval_execution_attempts");
            h.execute("DELETE FROM approval_votes");
            h.execute("DELETE FROM approval_request_channels");
            h.execute("DELETE FROM approval_state_transitions");
            h.execute("DELETE FROM approval_coin_runtime");
            h.execute("DELETE FROM approval_requests");
            h.execute("DELETE FROM approval_channels");
        });
    }

    // --- countOpenRequests ---

    @Test
    void countOpenRequests_returnsZero_whenEmpty() {
        assertEquals(0, requestRepo.countOpenRequests());
    }

    @Test
    void countOpenRequests_countsOnlyOpenStates() {
        insertRequest("r-queued", "QUEUED");
        insertRequest("r-pending", "PENDING");
        insertRequest("r-approved", "APPROVED");
        insertRequest("r-executing", "EXECUTING");
        insertRequest("r-completed", "COMPLETED");
        insertRequest("r-failed", "FAILED");
        assertEquals(4, requestRepo.countOpenRequests());
    }

    // --- isLockdownActive ---

    @Test
    void isLockdownActive_returnsFalse_whenNoRuntimeRows() {
        assertFalse(requestRepo.isLockdownActive());
    }

    @Test
    void isLockdownActive_returnsFalse_whenLockdownInPast() {
        jdbi.useHandle(h -> h.createUpdate("""
                INSERT INTO approval_coin_runtime (coin, active_request_id, cooldown_until, lockdown_until)
                VALUES ('bitcoin', NULL, NULL, :t)
                """).bind("t", Instant.now().minusSeconds(60)).execute());
        assertFalse(requestRepo.isLockdownActive());
    }

    @Test
    void isLockdownActive_returnsTrue_whenLockdownInFuture() {
        jdbi.useHandle(h -> h.createUpdate("""
                INSERT INTO approval_coin_runtime (coin, active_request_id, cooldown_until, lockdown_until)
                VALUES ('bitcoin', NULL, NULL, :t)
                """).bind("t", Instant.now().plusSeconds(300)).execute());
        assertTrue(requestRepo.isLockdownActive());
    }

    // --- findApprovalRequestById ---

    @Test
    void findApprovalRequestById_returnsNull_forMissingId() {
        assertNull(requestRepo.findApprovalRequestById("no-such-id"));
    }

    @Test
    void findApprovalRequestById_returnsNull_forBlankId() {
        assertNull(requestRepo.findApprovalRequestById("  "));
        assertNull(requestRepo.findApprovalRequestById(null));
    }

    @Test
    void findApprovalRequestById_returnsRow_whenFound() {
        insertRequest("req-1", "PENDING");
        ApprovalRequestRow row = requestRepo.findApprovalRequestById("req-1");
        assertNotNull(row);
        assertEquals("req-1", row.id());
        assertEquals("PENDING", row.state());
        assertEquals("bitcoin", row.coin());
    }

    // --- pagePendingApprovalRequests ---

    @Test
    void pagePendingApprovalRequests_returnsOnlyPendingAndQueued() {
        insertRequest("p1", "PENDING");
        insertRequest("p2", "QUEUED");
        insertRequest("p3", "COMPLETED");
        insertRequest("p4", "FAILED");

        PageResult<ApprovalRequestRow> result = requestRepo.pagePendingApprovalRequests("id", "asc", 1, 25);
        assertEquals(2, result.totalRows());
        List<String> ids = result.rows().stream().map(ApprovalRequestRow::id).toList();
        assertTrue(ids.containsAll(List.of("p1", "p2")));
        assertFalse(ids.contains("p3"));
    }

    @Test
    void pagePendingApprovalRequests_returnsEmpty_whenNoPendingRows() {
        insertRequest("r1", "COMPLETED");
        PageResult<ApprovalRequestRow> result = requestRepo.pagePendingApprovalRequests("id", "asc", 1, 25);
        assertEquals(0, result.totalRows());
        assertTrue(result.rows().isEmpty());
    }

    // --- pageNonPendingApprovalRequests ---

    @Test
    void pageNonPendingApprovalRequests_excludesPendingAndQueued() {
        insertRequest("t1", "COMPLETED");
        insertRequest("t2", "FAILED");
        insertRequest("t3", "PENDING");
        insertRequest("t4", "QUEUED");

        PageResult<ApprovalRequestRow> result = requestRepo.pageNonPendingApprovalRequests("id", "asc", 1, 25);
        assertEquals(2, result.totalRows());
        List<String> ids = result.rows().stream().map(ApprovalRequestRow::id).toList();
        assertTrue(ids.containsAll(List.of("t1", "t2")));
    }

    @Test
    void pageNonPendingApprovalRequests_filtersByCoin() {
        insertRequestWithCoin("c1", "COMPLETED", "bitcoin");
        insertRequestWithCoin("c2", "COMPLETED", "monero");

        PageResult<ApprovalRequestRow> result = requestRepo.pageNonPendingApprovalRequests(
                "id", "asc", 1, 25, "bitcoin", "", "", "");
        assertEquals(1, result.totalRows());
        assertEquals("bitcoin", result.rows().get(0).coin());
    }

    @Test
    void pageNonPendingApprovalRequests_filtersByTool() {
        insertRequestWithTool("t1", "COMPLETED", "wallet_send");
        insertRequestWithTool("t2", "COMPLETED", "wallet_sign");

        PageResult<ApprovalRequestRow> result = requestRepo.pageNonPendingApprovalRequests(
                "id", "asc", 1, 25, "", "wallet_send", "", "");
        assertEquals(1, result.totalRows());
        assertEquals("wallet_send", result.rows().get(0).toolName());
    }

    @Test
    void pageNonPendingApprovalRequests_noFiltersDelegatesToSimpleQuery() {
        insertRequest("d1", "COMPLETED");
        insertRequest("d2", "FAILED");

        PageResult<ApprovalRequestRow> result = requestRepo.pageNonPendingApprovalRequests(
                "id", "asc", 1, 25, "", "", "", "");
        assertEquals(2, result.totalRows());
    }

    // --- loadNonPendingFilterOptions ---

    @Test
    void loadNonPendingFilterOptions_returnsEmpty_whenNoTerminalRows() {
        insertRequest("q1", "QUEUED");
        LogQueueFilterOptions opts = requestRepo.loadNonPendingFilterOptions();
        assertTrue(opts.coins().isEmpty());
        assertTrue(opts.tools().isEmpty());
        assertTrue(opts.states().isEmpty());
    }

    @Test
    void loadNonPendingFilterOptions_returnsDistinctSortedValues() {
        insertRequestWithCoinAndTool("f1", "COMPLETED", "monero", "wallet_send");
        insertRequestWithCoinAndTool("f2", "FAILED", "bitcoin", "wallet_sign");
        insertRequestWithCoinAndTool("f3", "COMPLETED", "bitcoin", "wallet_send");

        LogQueueFilterOptions opts = requestRepo.loadNonPendingFilterOptions();
        assertEquals(List.of("bitcoin", "monero"), opts.coins());
        assertEquals(List.of("wallet_send", "wallet_sign"), opts.tools());
        assertTrue(opts.states().containsAll(List.of("COMPLETED", "FAILED")));
    }

    // --- pageStateTransitions ---

    @Test
    void pageStateTransitions_returnsEmpty_whenNoTransitions() {
        PageResult<StateTransitionRow> result = historyRepo.pageStateTransitions("created_at", "desc", 1, 25);
        assertEquals(0, result.totalRows());
        assertTrue(result.rows().isEmpty());
    }

    @Test
    void pageStateTransitions_returnsPaginatedRows() {
        insertRequest("tr1", "COMPLETED");
        insertTransition("tr1", null, "QUEUED");
        insertTransition("tr1", "QUEUED", "COMPLETED");

        PageResult<StateTransitionRow> result = historyRepo.pageStateTransitions("created_at", "asc", 1, 25);
        assertEquals(2, result.totalRows());
    }

    // --- loadRequestDependencies ---

    @Test
    void loadRequestDependencies_returnsEmptyMap_forEmptyList() {
        Map<String, RequestDependencies> result = depLoader.loadRequestDependencies(List.of());
        assertTrue(result.isEmpty());
    }

    @Test
    void loadRequestDependencies_returnsEmptyMap_forNullList() {
        Map<String, RequestDependencies> result = depLoader.loadRequestDependencies(null);
        assertTrue(result.isEmpty());
    }

    @Test
    void loadRequestDependencies_returnsDependenciesForGivenIds() {
        insertRequest("dep-1", "COMPLETED");
        insertRequest("dep-2", "FAILED");
        insertTransition("dep-1", null, "QUEUED");
        insertChannel("ch-1", "telegram");
        insertRequestChannel("dep-1", "ch-1");
        insertVote("dep-1", "ch-1", "approve");

        Map<String, RequestDependencies> result = depLoader.loadRequestDependencies(List.of("dep-1", "dep-2"));
        assertEquals(2, result.size());

        RequestDependencies dep1 = result.get("dep-1");
        assertNotNull(dep1);
        assertEquals(1, dep1.transitions().size());
        assertEquals(1, dep1.channels().size());
        assertEquals(1, dep1.votes().size());
        assertTrue(dep1.executionAttempts().isEmpty());

        RequestDependencies dep2 = result.get("dep-2");
        assertNotNull(dep2);
        assertTrue(dep2.transitions().isEmpty());
    }

    // --- pagination helpers ---

    @Test
    void pagePendingApprovalRequests_clampsBadPageToOne() {
        insertRequest("pg1", "PENDING");
        PageResult<ApprovalRequestRow> result = requestRepo.pagePendingApprovalRequests("id", "asc", -5, 25);
        assertEquals(1, result.page());
    }

    @Test
    void pagePendingApprovalRequests_clampsBadPageSizeToDefault() {
        insertRequest("ps1", "PENDING");
        PageResult<ApprovalRequestRow> result = requestRepo.pagePendingApprovalRequests("id", "asc", 1, -1);
        assertEquals(25, result.pageSize());
    }

    @Test
    void pagePendingApprovalRequests_capsPageSizeAtMax() {
        PageResult<ApprovalRequestRow> result = requestRepo.pagePendingApprovalRequests("id", "asc", 1, 9999);
        assertEquals(200, result.pageSize());
    }

    @Test
    void pagePendingApprovalRequests_unknownSortByFallsBackToDefault() {
        PageResult<ApprovalRequestRow> result = requestRepo.pagePendingApprovalRequests("nonexistent_col", "asc", 1, 25);
        assertEquals("requested_at", result.sortBy());
    }

    @Test
    void pagePendingApprovalRequests_invalidSortDirDefaultsToDesc() {
        PageResult<ApprovalRequestRow> result = requestRepo.pagePendingApprovalRequests("id", "invalid", 1, 25);
        assertEquals("desc", result.sortDir());
    }

    // --- DB helpers ---

    private void insertRequest(String id, String state) {
        insertRequestWithCoinAndTool(id, state, "bitcoin", "wallet_send");
    }

    private void insertRequestWithCoin(String id, String state, String coin) {
        insertRequestWithCoinAndTool(id, state, coin, "wallet_send");
    }

    private void insertRequestWithTool(String id, String state, String tool) {
        insertRequestWithCoinAndTool(id, state, "bitcoin", tool);
    }

    private void insertRequestWithCoinAndTool(String id, String state, String coin, String tool) {
        Instant now = Instant.now();
        jdbi.useHandle(h -> h.createUpdate("""
                INSERT INTO approval_requests (
                    id, coin, tool_name, nonce_uuid, payload_hash_sha256, nonce_composite,
                    requested_at, expires_at, state, min_approvals_required
                ) VALUES (:id, :coin, :tool, :nonceUuid, :sha256, :nonce, :requestedAt, :expiresAt, :state, 1)
                """)
                .bind("id", id)
                .bind("coin", coin)
                .bind("tool", tool)
                .bind("nonceUuid", "nonce-" + id)
                .bind("sha256", "sha256-" + id)
                .bind("nonce", coin + "|nonce-" + id)
                .bind("requestedAt", now)
                .bind("expiresAt", now.plusSeconds(600))
                .bind("state", state)
                .execute());
    }

    private void insertTransition(String requestId, String fromState, String toState) {
        jdbi.useHandle(h -> h.createUpdate("""
                INSERT INTO approval_state_transitions
                    (request_id, from_state, to_state, actor_type, actor_id, created_at)
                VALUES (:requestId, :from, :to, 'system', 'test', :now)
                """)
                .bind("requestId", requestId)
                .bind("from", fromState)
                .bind("to", toState)
                .bind("now", Instant.now())
                .execute());
    }

    private void insertChannel(String channelId, String channelType) {
        jdbi.useHandle(h -> h.createUpdate("""
                INSERT INTO approval_channels (id, channel_type, display_name, enabled, config_fingerprint)
                VALUES (:id, :type, :name, true, 'fp')
                """)
                .bind("id", channelId)
                .bind("type", channelType)
                .bind("name", channelId)
                .execute());
    }

    private void insertRequestChannel(String requestId, String channelId) {
        jdbi.useHandle(h -> h.createUpdate("""
                INSERT INTO approval_request_channels
                    (request_id, channel_id, delivery_state, first_sent_at, last_attempt_at, attempt_count, created_at)
                VALUES (:requestId, :channelId, 'sent', :now, :now, 1, :now)
                """)
                .bind("requestId", requestId)
                .bind("channelId", channelId)
                .bind("now", Instant.now())
                .execute());
    }

    private void insertVote(String requestId, String channelId, String decision) {
        jdbi.useHandle(h -> h.createUpdate("""
                INSERT INTO approval_votes (request_id, channel_id, decision, decision_reason, decided_by, decided_at)
                VALUES (:requestId, :channelId, :decision, 'test', 'tester', :now)
                """)
                .bind("requestId", requestId)
                .bind("channelId", channelId)
                .bind("decision", decision)
                .bind("now", Instant.now())
                .execute());
    }
}
