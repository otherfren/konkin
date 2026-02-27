package io.konkin.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Random;

/**
 * Seeds the auth queue model tables with synthetic activity for local debug mode.
 */
public class DebugDataSeeder {

    private static final Logger log = LoggerFactory.getLogger(DebugDataSeeder.class);

    private static final List<String> COINS = List.of("bitcoin", "monero", "ethereum", "litecoin");
    private static final List<String> TOOL_NAMES = List.of("wallet_send", "wallet_sign", "wallet_sweep", "wallet_balance");
    private static final List<String> TERMINAL_STATES = List.of("COMPLETED", "FAILED", "DENIED", "CANCELLED", "TIMED_OUT", "REJECTED", "EXPIRED");
    private static final List<String> ALL_STATES = List.of("QUEUED", "PENDING", "APPROVED", "EXECUTING", "COMPLETED", "FAILED", "DENIED", "CANCELLED", "TIMED_OUT", "REJECTED", "EXPIRED");

    private final DataSource dataSource;
    private final Random random;

    public DebugDataSeeder(DataSource dataSource) {
        this.dataSource = dataSource;
        this.random = new Random(42L);
    }

    public void seedIfEnabled(boolean debugEnabled, boolean seedFakeData) {
        if (!debugEnabled || !seedFakeData) {
            return;
        }

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            int existing = countRequests(conn);
            if (existing > 0) {
                log.info("Debug seeding skipped because approval_requests already contains {} row(s)", existing);
                conn.rollback();
                return;
            }

            seedChannels(conn);
            seedRequestsWithActivity(conn, 240);
            seedCoinRuntime(conn);
            conn.commit();
            log.info("Debug seeding complete: inserted synthetic approval queue activity");
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to seed debug auth queue data", e);
        }
    }

    private int countRequests(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM approval_requests");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private void seedChannels(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                """
                MERGE INTO approval_channels (id, channel_type, display_name, enabled, config_fingerprint, created_at)
                KEY (id)
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            insertChannel(ps, "dbg-telegram-a", "telegram", "Telegram Team A");
            insertChannel(ps, "dbg-telegram-b", "telegram", "Telegram Team B");
            insertChannel(ps, "dbg-mcp-agent-1", "mcp_agent", "MCP Agent 1");
            insertChannel(ps, "dbg-email-ops", "email", "Ops Email");
        }
    }

    private void insertChannel(PreparedStatement ps, String id, String type, String name) throws SQLException {
        ps.setString(1, id);
        ps.setString(2, type);
        ps.setString(3, name);
        ps.setBoolean(4, true);
        ps.setString(5, "dbg-fingerprint-" + id);
        ps.setTimestamp(6, Timestamp.from(Instant.now().minus(2, ChronoUnit.DAYS)));
        ps.executeUpdate();
    }

    private void seedRequestsWithActivity(Connection conn, int totalRequests) throws SQLException {
        Instant base = Instant.now().minus(36, ChronoUnit.HOURS);

        try (PreparedStatement requestPs = conn.prepareStatement(
                """
                INSERT INTO approval_requests (
                    id, coin, tool_name, request_session_id, nonce_uuid, payload_hash_sha256, nonce_composite,
                    to_address, amount_native, fee_policy, fee_cap_native, memo,
                    requested_at, expires_at, state, state_reason_code, state_reason_text,
                    min_approvals_required, approvals_granted, approvals_denied, policy_action_at_creation,
                    created_at, updated_at, resolved_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """);
             PreparedStatement transitionPs = conn.prepareStatement(
                     """
                     INSERT INTO approval_state_transitions (
                         request_id, from_state, to_state, actor_type, actor_id, reason_code, reason_text, metadata_json, created_at
                     ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                     """);
             PreparedStatement requestChannelPs = conn.prepareStatement(
                     """
                     INSERT INTO approval_request_channels (
                         request_id, channel_id, delivery_state, first_sent_at, last_attempt_at, attempt_count, last_error, created_at
                     ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                     """);
             PreparedStatement votePs = conn.prepareStatement(
                     """
                     INSERT INTO approval_votes (
                         request_id, channel_id, decision, decision_reason, decided_by, decided_at
                     ) VALUES (?, ?, ?, ?, ?, ?)
                     """);
             PreparedStatement executionPs = conn.prepareStatement(
                     """
                     INSERT INTO approval_execution_attempts (
                         request_id, attempt_no, started_at, finished_at, result, error_class, error_message, txid, daemon_fee_native
                     ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                     """)) {

            for (int i = 0; i < totalRequests; i++) {
                String requestId = "dbg-req-" + String.format("%04d", i + 1);
                String coin = COINS.get(i % COINS.size());
                String tool = TOOL_NAMES.get(i % TOOL_NAMES.size());
                String state = ALL_STATES.get(i % ALL_STATES.size());
                String nonceComposite = coin + "|dbg-nonce-" + (10_000 + i) + "|dbg-hash-" + i;

                Instant requestedAt = base.plus(i * 9L, ChronoUnit.MINUTES);
                Instant expiresAt = requestedAt.plus(30 + random.nextInt(90), ChronoUnit.MINUTES);
                Instant updatedAt = requestedAt.plus(2 + random.nextInt(25), ChronoUnit.MINUTES);
                Instant resolvedAt = TERMINAL_STATES.contains(state)
                        ? updatedAt.plus(1 + random.nextInt(30), ChronoUnit.MINUTES)
                        : null;

                int approvalsGranted = switch (state) {
                    case "APPROVED", "EXECUTING", "COMPLETED" -> 2;
                    default -> random.nextInt(2);
                };
                int approvalsDenied = switch (state) {
                    case "DENIED", "REJECTED" -> 2;
                    default -> random.nextInt(2);
                };

                requestPs.setString(1, requestId);
                requestPs.setString(2, coin);
                requestPs.setString(3, tool);
                requestPs.setString(4, "dbg-session-" + ((i % 12) + 1));
                requestPs.setString(5, "dbg-nonce-uuid-" + i);
                requestPs.setString(6, "dbg-sha256-" + i);
                requestPs.setString(7, nonceComposite);
                requestPs.setString(8, "addr-" + coin + "-" + (1000 + i));
                requestPs.setString(9, String.format("%.8f", 0.01 + (i % 19) * 0.0025));
                requestPs.setString(10, i % 3 == 0 ? "dynamic" : "fixed");
                requestPs.setString(11, String.format("%.8f", 0.001 + (i % 5) * 0.0002));
                requestPs.setString(12, "debug request #" + (i + 1));
                requestPs.setTimestamp(13, Timestamp.from(requestedAt));
                requestPs.setTimestamp(14, Timestamp.from(expiresAt));
                requestPs.setString(15, state);
                requestPs.setString(16, "dbg_reason_" + state.toLowerCase());
                requestPs.setString(17, "Synthetic transition to " + state);
                requestPs.setInt(18, 2);
                requestPs.setInt(19, approvalsGranted);
                requestPs.setInt(20, approvalsDenied);
                requestPs.setString(21, "manual");
                requestPs.setTimestamp(22, Timestamp.from(requestedAt));
                requestPs.setTimestamp(23, Timestamp.from(updatedAt));
                if (resolvedAt == null) {
                    requestPs.setTimestamp(24, null);
                } else {
                    requestPs.setTimestamp(24, Timestamp.from(resolvedAt));
                }
                requestPs.executeUpdate();

                insertTransition(transitionPs, requestId, null, "QUEUED", "system", "queue", requestedAt);
                if (!"QUEUED".equals(state)) {
                    insertTransition(transitionPs, requestId, "QUEUED", state, "approver", "debug-seeder", updatedAt);
                }

                insertRequestChannel(requestChannelPs, requestId, "dbg-telegram-a", requestedAt, state);
                insertRequestChannel(requestChannelPs, requestId, "dbg-mcp-agent-1", requestedAt.plus(10, ChronoUnit.SECONDS), state);
                insertRequestChannel(requestChannelPs, requestId, "dbg-email-ops", requestedAt.plus(20, ChronoUnit.SECONDS), state);

                if (approvalsGranted > 0) {
                    insertVote(votePs, requestId, "dbg-telegram-a", "approve", requestedAt.plus(3, ChronoUnit.MINUTES));
                }
                if (approvalsGranted > 1) {
                    insertVote(votePs, requestId, "dbg-mcp-agent-1", "approve", requestedAt.plus(5, ChronoUnit.MINUTES));
                }
                if (approvalsDenied > 0) {
                    insertVote(votePs, requestId, "dbg-email-ops", "deny", requestedAt.plus(4, ChronoUnit.MINUTES));
                }

                if ("COMPLETED".equals(state) || "FAILED".equals(state) || "EXECUTING".equals(state)) {
                    insertExecution(executionPs, requestId, 1, requestedAt.plus(6, ChronoUnit.MINUTES), state);
                }
            }
        }
    }

    private void insertTransition(
            PreparedStatement ps,
            String requestId,
            String fromState,
            String toState,
            String actorType,
            String actorId,
            Instant at
    ) throws SQLException {
        ps.setString(1, requestId);
        ps.setString(2, fromState);
        ps.setString(3, toState);
        ps.setString(4, actorType);
        ps.setString(5, actorId);
        ps.setString(6, "debug");
        ps.setString(7, "debug seeder transition");
        ps.setString(8, "{\"seeded\":true,\"to\":\"" + toState + "\"}");
        ps.setTimestamp(9, Timestamp.from(at));
        ps.executeUpdate();
    }

    private void insertRequestChannel(
            PreparedStatement ps,
            String requestId,
            String channelId,
            Instant firstSentAt,
            String state
    ) throws SQLException {
        String deliveryState = ("QUEUED".equals(state) || "PENDING".equals(state)) ? "queued" : "acked";
        ps.setString(1, requestId);
        ps.setString(2, channelId);
        ps.setString(3, deliveryState);
        ps.setTimestamp(4, Timestamp.from(firstSentAt));
        ps.setTimestamp(5, Timestamp.from(firstSentAt.plus(90, ChronoUnit.SECONDS)));
        ps.setInt(6, deliveryState.equals("queued") ? 0 : 1);
        ps.setString(7, null);
        ps.setTimestamp(8, Timestamp.from(firstSentAt));
        ps.executeUpdate();
    }

    private void insertVote(PreparedStatement ps, String requestId, String channelId, String decision, Instant decidedAt)
            throws SQLException {
        ps.setString(1, requestId);
        ps.setString(2, channelId);
        ps.setString(3, decision);
        ps.setString(4, "debug decision");
        ps.setString(5, "dbg-actor-" + channelId);
        ps.setTimestamp(6, Timestamp.from(decidedAt));
        ps.executeUpdate();
    }

    private void insertExecution(PreparedStatement ps, String requestId, int attemptNo, Instant startedAt, String state)
            throws SQLException {
        ps.setString(1, requestId);
        ps.setInt(2, attemptNo);
        ps.setTimestamp(3, Timestamp.from(startedAt));
        if ("EXECUTING".equals(state)) {
            ps.setTimestamp(4, null);
            ps.setString(5, "transient_error");
            ps.setString(6, "java.net.SocketTimeoutException");
            ps.setString(7, "debug transient error while still executing");
            ps.setString(8, null);
        } else if ("FAILED".equals(state)) {
            ps.setTimestamp(4, Timestamp.from(startedAt.plus(2, ChronoUnit.MINUTES)));
            ps.setString(5, "non_retryable_error");
            ps.setString(6, "java.lang.IllegalStateException");
            ps.setString(7, "debug non-retryable failure");
            ps.setString(8, null);
        } else {
            ps.setTimestamp(4, Timestamp.from(startedAt.plus(45, ChronoUnit.SECONDS)));
            ps.setString(5, "success");
            ps.setString(6, null);
            ps.setString(7, null);
            ps.setString(8, "dbg-tx-" + requestId);
        }
        ps.setString(9, String.format("%.8f", 0.0008 + random.nextDouble() * 0.0009));
        ps.executeUpdate();
    }

    private void seedCoinRuntime(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                """
                MERGE INTO approval_coin_runtime (coin, active_request_id, cooldown_until, lockdown_until, updated_at)
                KEY (coin)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            Instant now = Instant.now();
            upsertRuntime(ps, "bitcoin", "dbg-req-0007", now.plus(8, ChronoUnit.MINUTES), null, now);
            upsertRuntime(ps, "monero", null, now.plus(3, ChronoUnit.MINUTES), now.plus(22, ChronoUnit.MINUTES), now);
            upsertRuntime(ps, "ethereum", "dbg-req-0012", null, null, now);
            upsertRuntime(ps, "litecoin", null, null, null, now);
        }
    }

    private void upsertRuntime(
            PreparedStatement ps,
            String coin,
            String activeRequestId,
            Instant cooldownUntil,
            Instant lockdownUntil,
            Instant updatedAt
    ) throws SQLException {
        ps.setString(1, coin);
        ps.setString(2, activeRequestId);
        ps.setTimestamp(3, cooldownUntil == null ? null : Timestamp.from(cooldownUntil));
        ps.setTimestamp(4, lockdownUntil == null ? null : Timestamp.from(lockdownUntil));
        ps.setTimestamp(5, Timestamp.from(updatedAt));
        ps.executeUpdate();
    }
}
