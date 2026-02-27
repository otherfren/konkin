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
        String orderBy = REQUEST_SORT_COLUMNS.getOrDefault(sortBy, "requested_at");
        String direction = normalizeSortDirection(sortDir);
        int safePageSize = normalizePageSize(pageSize);
        long totalRows = queryCount("SELECT COUNT(*) FROM approval_requests");
        int totalPages = totalRows == 0 ? 0 : (int) Math.ceil((double) totalRows / safePageSize);
        int safePage = normalizePage(page, totalPages);
        int offset = (safePage - 1) * safePageSize;

        String sql = """
                SELECT id, coin, tool_name, request_session_id, nonce_composite, requested_at, expires_at,
                       state, min_approvals_required, approvals_granted, approvals_denied, updated_at
                FROM approval_requests
                ORDER BY %s %s
                LIMIT ? OFFSET ?
                """.formatted(orderBy, direction);

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
                            rs.getString("nonce_composite"),
                            toInstant(rs.getTimestamp("requested_at")),
                            toInstant(rs.getTimestamp("expires_at")),
                            rs.getString("state"),
                            rs.getInt("min_approvals_required"),
                            rs.getInt("approvals_granted"),
                            rs.getInt("approvals_denied"),
                            toInstant(rs.getTimestamp("updated_at"))
                    ));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to page approval requests", e);
            throw new IllegalStateException("Failed to page approval_requests", e);
        }

        return new PageResult<>(List.copyOf(rows), safePage, safePageSize, totalRows, totalPages, orderBy, direction.toLowerCase());
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
            String nonceComposite,
            Instant requestedAt,
            Instant expiresAt,
            String state,
            int minApprovalsRequired,
            int approvalsGranted,
            int approvalsDenied,
            Instant updatedAt
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
