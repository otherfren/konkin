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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-side data access for auth queue status and landing/audit pages.
 */
public class AuthQueueStore {

    private static final Logger log = LoggerFactory.getLogger(AuthQueueStore.class);

    private static final String COUNT_PENDING_SQL = """
            SELECT COUNT(*)
            FROM approval_requests
            WHERE state IN ('QUEUED', 'PENDING', 'APPROVED', 'EXECUTING')
            """;

    private static final String LOCKDOWN_ACTIVE_SQL = """
            SELECT COUNT(*)
            FROM approval_coin_runtime
            WHERE lockdown_until IS NOT NULL
              AND lockdown_until > CURRENT_TIMESTAMP
            """;

    private static final int DEFAULT_PAGE_SIZE = 25;
    private static final int MAX_PAGE_SIZE = 200;

    private static final Map<String, String> REQUEST_SORT_COLUMNS = Map.of(
            "id", "id",
            "requested_at", "requested_at",
            "expires_at", "expires_at",
            "updated_at", "updated_at",
            "coin", "coin",
            "tool_name", "tool_name",
            "state", "state",
            "approvals_granted", "approvals_granted",
            "approvals_denied", "approvals_denied"
    );

    private static final Map<String, String> TRANSITION_SORT_COLUMNS = Map.of(
            "created_at", "created_at",
            "request_id", "request_id",
            "from_state", "from_state",
            "to_state", "to_state",
            "actor_type", "actor_type",
            "actor_id", "actor_id",
            "reason_code", "reason_code"
    );

    private final DataSource dataSource;

    public AuthQueueStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public int countOpenRequests() {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(COUNT_PENDING_SQL);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return rs.getInt(1);
            }
            return 0;
        } catch (SQLException e) {
            log.error("Failed to count open approval requests", e);
            throw new IllegalStateException("Failed to query approval_requests", e);
        }
    }

    public boolean isLockdownActive() {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(LOCKDOWN_ACTIVE_SQL);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return rs.getInt(1) > 0;
            }
            return false;
        } catch (SQLException e) {
            log.error("Failed to evaluate approval lockdown status", e);
            throw new IllegalStateException("Failed to query approval_coin_runtime", e);
        }
    }

    public PageResult<ApprovalRequestRow> pageApprovalRequests(String sortBy, String sortDir, int page, int pageSize) {
        return pageApprovalRequestsWithStateFilter(sortBy, sortDir, page, pageSize, null);
    }

    public PageResult<ApprovalRequestRow> pagePendingApprovalRequests(String sortBy, String sortDir, int page, int pageSize) {
        return pageApprovalRequestsWithStateFilter(sortBy, sortDir, page, pageSize, "state = 'PENDING'");
    }

    public PageResult<ApprovalRequestRow> pageNonPendingApprovalRequests(String sortBy, String sortDir, int page, int pageSize) {
        return pageApprovalRequestsWithStateFilter(sortBy, sortDir, page, pageSize, "state <> 'PENDING'");
    }

    private PageResult<ApprovalRequestRow> pageApprovalRequestsWithStateFilter(
            String sortBy,
            String sortDir,
            int page,
            int pageSize,
            String stateFilterSql
    ) {
        String orderBy = REQUEST_SORT_COLUMNS.getOrDefault(sortBy, "requested_at");
        String direction = normalizeSortDirection(sortDir);
        int safePageSize = normalizePageSize(pageSize);

        String whereClause = stateFilterSql == null ? "" : " WHERE " + stateFilterSql;
        long totalRows = queryCount("SELECT COUNT(*) FROM approval_requests" + whereClause);
        int totalPages = totalRows == 0 ? 0 : (int) Math.ceil((double) totalRows / safePageSize);
        int safePage = normalizePage(page, totalPages);
        int offset = (safePage - 1) * safePageSize;

        String sql = """
                SELECT id, coin, tool_name, request_session_id, nonce_uuid, payload_hash_sha256, nonce_composite,
                       to_address, amount_native, fee_policy, fee_cap_native, memo,
                       requested_at, expires_at, state, state_reason_code, state_reason_text,
                       min_approvals_required, approvals_granted, approvals_denied, policy_action_at_creation,
                       created_at, updated_at, resolved_at
                FROM approval_requests
                %s
                ORDER BY %s %s
                LIMIT ? OFFSET ?
                """.formatted(whereClause, orderBy, direction);

        List<ApprovalRequestRow> rows = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, safePageSize);
            ps.setInt(2, Math.max(offset, 0));

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new ApprovalRequestRow(
                            rs.getString("id"),
                            rs.getString("coin"),
                            rs.getString("tool_name"),
                            rs.getString("request_session_id"),
                            rs.getString("nonce_uuid"),
                            rs.getString("payload_hash_sha256"),
                            rs.getString("nonce_composite"),
                            rs.getString("to_address"),
                            rs.getString("amount_native"),
                            rs.getString("fee_policy"),
                            rs.getString("fee_cap_native"),
                            rs.getString("memo"),
                            toInstant(rs.getTimestamp("requested_at")),
                            toInstant(rs.getTimestamp("expires_at")),
                            rs.getString("state"),
                            rs.getString("state_reason_code"),
                            rs.getString("state_reason_text"),
                            rs.getInt("min_approvals_required"),
                            rs.getInt("approvals_granted"),
                            rs.getInt("approvals_denied"),
                            rs.getString("policy_action_at_creation"),
                            toInstant(rs.getTimestamp("created_at")),
                            toInstant(rs.getTimestamp("updated_at")),
                            toInstant(rs.getTimestamp("resolved_at"))
                    ));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to page approval requests", e);
            throw new IllegalStateException("Failed to page approval_requests", e);
        }

        return new PageResult<>(List.copyOf(rows), safePage, safePageSize, totalRows, totalPages, orderBy, direction.toLowerCase());
    }

    public ApprovalRequestRow findApprovalRequestById(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return null;
        }

        String sql = """
                SELECT id, coin, tool_name, request_session_id, nonce_uuid, payload_hash_sha256, nonce_composite,
                       to_address, amount_native, fee_policy, fee_cap_native, memo,
                       requested_at, expires_at, state, state_reason_code, state_reason_text,
                       min_approvals_required, approvals_granted, approvals_denied, policy_action_at_creation,
                       created_at, updated_at, resolved_at
                FROM approval_requests
                WHERE id = ?
                LIMIT 1
                """;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, requestId.trim());

            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }

                return new ApprovalRequestRow(
                        rs.getString("id"),
                        rs.getString("coin"),
                        rs.getString("tool_name"),
                        rs.getString("request_session_id"),
                        rs.getString("nonce_uuid"),
                        rs.getString("payload_hash_sha256"),
                        rs.getString("nonce_composite"),
                        rs.getString("to_address"),
                        rs.getString("amount_native"),
                        rs.getString("fee_policy"),
                        rs.getString("fee_cap_native"),
                        rs.getString("memo"),
                        toInstant(rs.getTimestamp("requested_at")),
                        toInstant(rs.getTimestamp("expires_at")),
                        rs.getString("state"),
                        rs.getString("state_reason_code"),
                        rs.getString("state_reason_text"),
                        rs.getInt("min_approvals_required"),
                        rs.getInt("approvals_granted"),
                        rs.getInt("approvals_denied"),
                        rs.getString("policy_action_at_creation"),
                        toInstant(rs.getTimestamp("created_at")),
                        toInstant(rs.getTimestamp("updated_at")),
                        toInstant(rs.getTimestamp("resolved_at"))
                );
            }
        } catch (SQLException e) {
            log.error("Failed to load approval request by id={}", requestId, e);
            throw new IllegalStateException("Failed to query approval_requests by id", e);
        }
    }

    public PageResult<StateTransitionRow> pageStateTransitions(String sortBy, String sortDir, int page, int pageSize) {
        String orderBy = TRANSITION_SORT_COLUMNS.getOrDefault(sortBy, "created_at");
        String direction = normalizeSortDirection(sortDir);
        int safePageSize = normalizePageSize(pageSize);
        long totalRows = queryCount("SELECT COUNT(*) FROM approval_state_transitions");
        int totalPages = totalRows == 0 ? 0 : (int) Math.ceil((double) totalRows / safePageSize);
        int safePage = normalizePage(page, totalPages);
        int offset = (safePage - 1) * safePageSize;

        String sql = """
                SELECT id, request_id, from_state, to_state, actor_type, actor_id, reason_code, created_at
                FROM approval_state_transitions
                ORDER BY %s %s
                LIMIT ? OFFSET ?
                """.formatted(orderBy, direction);

        List<StateTransitionRow> rows = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, safePageSize);
            ps.setInt(2, Math.max(offset, 0));

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new StateTransitionRow(
                            rs.getLong("id"),
                            rs.getString("request_id"),
                            rs.getString("from_state"),
                            rs.getString("to_state"),
                            rs.getString("actor_type"),
                            rs.getString("actor_id"),
                            rs.getString("reason_code"),
                            toInstant(rs.getTimestamp("created_at"))
                    ));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to page approval state transitions", e);
            throw new IllegalStateException("Failed to page approval_state_transitions", e);
        }

        return new PageResult<>(List.copyOf(rows), safePage, safePageSize, totalRows, totalPages, orderBy, direction.toLowerCase());
    }

    public Map<String, RequestDependencies> loadRequestDependencies(List<String> requestIds) {
        if (requestIds == null || requestIds.isEmpty()) {
            return Map.of();
        }

        Map<String, List<StateTransitionDetail>> transitions = loadTransitionDetails(requestIds);
        Map<String, List<RequestChannelDetail>> channels = loadRequestChannelDetails(requestIds);
        Map<String, List<VoteDetail>> votes = loadVoteDetails(requestIds);
        Map<String, List<ExecutionAttemptDetail>> executionAttempts = loadExecutionAttemptDetails(requestIds);

        Map<String, RequestDependencies> byRequestId = new LinkedHashMap<>();
        for (String requestId : requestIds) {
            byRequestId.put(requestId, new RequestDependencies(
                    List.copyOf(transitions.getOrDefault(requestId, List.of())),
                    List.copyOf(channels.getOrDefault(requestId, List.of())),
                    List.copyOf(votes.getOrDefault(requestId, List.of())),
                    List.copyOf(executionAttempts.getOrDefault(requestId, List.of()))
            ));
        }

        return Map.copyOf(byRequestId);
    }

    private Map<String, List<StateTransitionDetail>> loadTransitionDetails(List<String> requestIds) {
        String sql = """
                SELECT id, request_id, from_state, to_state, actor_type, actor_id, reason_code, reason_text, metadata_json, created_at
                FROM approval_state_transitions
                WHERE request_id IN (%s)
                ORDER BY request_id ASC, created_at ASC, id ASC
                """.formatted(inClausePlaceholders(requestIds.size()));

        Map<String, List<StateTransitionDetail>> byRequestId = new LinkedHashMap<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            bindRequestIds(ps, requestIds);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String requestId = rs.getString("request_id");
                    StateTransitionDetail detail = new StateTransitionDetail(
                            rs.getLong("id"),
                            requestId,
                            rs.getString("from_state"),
                            rs.getString("to_state"),
                            rs.getString("actor_type"),
                            rs.getString("actor_id"),
                            rs.getString("reason_code"),
                            rs.getString("reason_text"),
                            rs.getString("metadata_json"),
                            toInstant(rs.getTimestamp("created_at"))
                    );
                    append(byRequestId, requestId, detail);
                }
            }
        } catch (SQLException e) {
            log.error("Failed to load transition details for {} request(s)", requestIds.size(), e);
            throw new IllegalStateException("Failed to query approval_state_transitions details", e);
        }

        return byRequestId;
    }

    private Map<String, List<RequestChannelDetail>> loadRequestChannelDetails(List<String> requestIds) {
        String sql = """
                SELECT id, request_id, channel_id, delivery_state, first_sent_at, last_attempt_at, attempt_count, last_error, created_at
                FROM approval_request_channels
                WHERE request_id IN (%s)
                ORDER BY request_id ASC, created_at ASC, id ASC
                """.formatted(inClausePlaceholders(requestIds.size()));

        Map<String, List<RequestChannelDetail>> byRequestId = new LinkedHashMap<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            bindRequestIds(ps, requestIds);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String requestId = rs.getString("request_id");
                    RequestChannelDetail detail = new RequestChannelDetail(
                            rs.getLong("id"),
                            requestId,
                            rs.getString("channel_id"),
                            rs.getString("delivery_state"),
                            toInstant(rs.getTimestamp("first_sent_at")),
                            toInstant(rs.getTimestamp("last_attempt_at")),
                            rs.getInt("attempt_count"),
                            rs.getString("last_error"),
                            toInstant(rs.getTimestamp("created_at"))
                    );
                    append(byRequestId, requestId, detail);
                }
            }
        } catch (SQLException e) {
            log.error("Failed to load request channel details for {} request(s)", requestIds.size(), e);
            throw new IllegalStateException("Failed to query approval_request_channels details", e);
        }

        return byRequestId;
    }

    private Map<String, List<VoteDetail>> loadVoteDetails(List<String> requestIds) {
        String sql = """
                SELECT id, request_id, channel_id, decision, decision_reason, decided_by, decided_at
                FROM approval_votes
                WHERE request_id IN (%s)
                ORDER BY request_id ASC, decided_at ASC, id ASC
                """.formatted(inClausePlaceholders(requestIds.size()));

        Map<String, List<VoteDetail>> byRequestId = new LinkedHashMap<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            bindRequestIds(ps, requestIds);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String requestId = rs.getString("request_id");
                    VoteDetail detail = new VoteDetail(
                            rs.getLong("id"),
                            requestId,
                            rs.getString("channel_id"),
                            rs.getString("decision"),
                            rs.getString("decision_reason"),
                            rs.getString("decided_by"),
                            toInstant(rs.getTimestamp("decided_at"))
                    );
                    append(byRequestId, requestId, detail);
                }
            }
        } catch (SQLException e) {
            log.error("Failed to load vote details for {} request(s)", requestIds.size(), e);
            throw new IllegalStateException("Failed to query approval_votes details", e);
        }

        return byRequestId;
    }

    private Map<String, List<ExecutionAttemptDetail>> loadExecutionAttemptDetails(List<String> requestIds) {
        String sql = """
                SELECT id, request_id, attempt_no, started_at, finished_at, result, error_class, error_message, txid, daemon_fee_native
                FROM approval_execution_attempts
                WHERE request_id IN (%s)
                ORDER BY request_id ASC, attempt_no ASC, id ASC
                """.formatted(inClausePlaceholders(requestIds.size()));

        Map<String, List<ExecutionAttemptDetail>> byRequestId = new LinkedHashMap<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            bindRequestIds(ps, requestIds);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String requestId = rs.getString("request_id");
                    ExecutionAttemptDetail detail = new ExecutionAttemptDetail(
                            rs.getLong("id"),
                            requestId,
                            rs.getInt("attempt_no"),
                            toInstant(rs.getTimestamp("started_at")),
                            toInstant(rs.getTimestamp("finished_at")),
                            rs.getString("result"),
                            rs.getString("error_class"),
                            rs.getString("error_message"),
                            rs.getString("txid"),
                            rs.getString("daemon_fee_native")
                    );
                    append(byRequestId, requestId, detail);
                }
            }
        } catch (SQLException e) {
            log.error("Failed to load execution attempt details for {} request(s)", requestIds.size(), e);
            throw new IllegalStateException("Failed to query approval_execution_attempts details", e);
        }

        return byRequestId;
    }

    private static void bindRequestIds(PreparedStatement ps, List<String> requestIds) throws SQLException {
        for (int i = 0; i < requestIds.size(); i++) {
            ps.setString(i + 1, requestIds.get(i));
        }
    }

    private static String inClausePlaceholders(int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("count must be >= 1");
        }

        StringBuilder sb = new StringBuilder(count * 3);
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append('?');
        }
        return sb.toString();
    }

    private static <T> void append(Map<String, List<T>> map, String requestId, T item) {
        map.computeIfAbsent(requestId, ignored -> new ArrayList<>()).add(item);
    }

    private long queryCount(String sql) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return rs.getLong(1);
            }
            return 0L;
        } catch (SQLException e) {
            log.error("Failed to query count using sql={}", sql, e);
            throw new IllegalStateException("Failed count query", e);
        }
    }

    private static int normalizePageSize(int pageSize) {
        if (pageSize <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }

    private static int normalizePage(int page, int totalPages) {
        if (totalPages <= 0) {
            return 1;
        }
        if (page <= 0) {
            return 1;
        }
        return Math.min(page, totalPages);
    }

    private static String normalizeSortDirection(String sortDir) {
        return "asc".equalsIgnoreCase(sortDir) ? "ASC" : "DESC";
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    public record ApprovalRequestRow(
            String id,
            String coin,
            String toolName,
            String requestSessionId,
            String nonceUuid,
            String payloadHashSha256,
            String nonceComposite,
            String toAddress,
            String amountNative,
            String feePolicy,
            String feeCapNative,
            String memo,
            Instant requestedAt,
            Instant expiresAt,
            String state,
            String stateReasonCode,
            String stateReasonText,
            int minApprovalsRequired,
            int approvalsGranted,
            int approvalsDenied,
            String policyActionAtCreation,
            Instant createdAt,
            Instant updatedAt,
            Instant resolvedAt
    ) {
    }

    public record StateTransitionRow(
            long id,
            String requestId,
            String fromState,
            String toState,
            String actorType,
            String actorId,
            String reasonCode,
            Instant createdAt
    ) {
    }

    public record StateTransitionDetail(
            long id,
            String requestId,
            String fromState,
            String toState,
            String actorType,
            String actorId,
            String reasonCode,
            String reasonText,
            String metadataJson,
            Instant createdAt
    ) {
    }

    public record RequestChannelDetail(
            long id,
            String requestId,
            String channelId,
            String deliveryState,
            Instant firstSentAt,
            Instant lastAttemptAt,
            int attemptCount,
            String lastError,
            Instant createdAt
    ) {
    }

    public record VoteDetail(
            long id,
            String requestId,
            String channelId,
            String decision,
            String decisionReason,
            String decidedBy,
            Instant decidedAt
    ) {
    }

    public record ExecutionAttemptDetail(
            long id,
            String requestId,
            int attemptNo,
            Instant startedAt,
            Instant finishedAt,
            String result,
            String errorClass,
            String errorMessage,
            String txid,
            String daemonFeeNative
    ) {
    }

    public record RequestDependencies(
            List<StateTransitionDetail> transitions,
            List<RequestChannelDetail> channels,
            List<VoteDetail> votes,
            List<ExecutionAttemptDetail> executionAttempts
    ) {
    }

    public record PageResult<T>(
            List<T> rows,
            int page,
            int pageSize,
            long totalRows,
            int totalPages,
            String sortBy,
            String sortDir
    ) {
    }
}
