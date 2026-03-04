package io.konkin.db;

import io.konkin.db.entity.ApprovalCoinRuntimeRow;
import io.konkin.db.entity.ApprovalRequestRow;
import io.konkin.db.entity.LogQueueFilterOptions;
import io.konkin.db.entity.PageResult;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.mapper.RowMapper;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static io.konkin.db.SqlUtils.*;

public class ApprovalRequestRepository {

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

    private static final String NON_QUEUE_WHERE_SQL = "state NOT IN ('PENDING', 'QUEUED')";

    private static final java.util.Map<String, String> REQUEST_SORT_COLUMNS = java.util.Map.of(
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

    private static final RowMapper<ApprovalRequestRow> APPROVAL_REQUEST_MAPPER = (rs, ctx) ->
            new ApprovalRequestRow(
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

    private static final RowMapper<ApprovalCoinRuntimeRow> APPROVAL_COIN_RUNTIME_MAPPER = (rs, ctx) ->
            new ApprovalCoinRuntimeRow(
                    rs.getString("coin"),
                    rs.getString("active_request_id"),
                    toInstant(rs.getTimestamp("cooldown_until")),
                    toInstant(rs.getTimestamp("lockdown_until")),
                    toInstant(rs.getTimestamp("updated_at"))
            );

    private final Jdbi jdbi;

    public ApprovalRequestRepository(DataSource dataSource) {
        this.jdbi = JdbiFactory.create(dataSource);
    }

    // --- ApprovalRequest CRUD ---

    public void insertApprovalRequest(ApprovalRequestRow row) {
        jdbi.useHandle(h -> h.createUpdate("""
                        INSERT INTO approval_requests (
                            id, coin, tool_name, request_session_id, nonce_uuid, payload_hash_sha256, nonce_composite,
                            to_address, amount_native, fee_policy, fee_cap_native, memo,
                            requested_at, expires_at, state, state_reason_code, state_reason_text,
                            min_approvals_required, approvals_granted, approvals_denied, policy_action_at_creation,
                            created_at, updated_at, resolved_at
                        ) VALUES (
                            :id, :coin, :toolName, :requestSessionId, :nonceUuid, :payloadHashSha256, :nonceComposite,
                            :toAddress, :amountNative, :feePolicy, :feeCapNative, :memo,
                            :requestedAt, :expiresAt, :state, :stateReasonCode, :stateReasonText,
                            :minApprovalsRequired, :approvalsGranted, :approvalsDenied, :policyActionAtCreation,
                            :createdAt, :updatedAt, :resolvedAt
                        )
                        """)
                .bindMethods(row)
                .execute());
    }

    public void updateApprovalRequest(ApprovalRequestRow row) {
        jdbi.useHandle(h -> h.createUpdate("""
                        UPDATE approval_requests SET
                            coin = :coin, tool_name = :toolName, request_session_id = :requestSessionId,
                            nonce_uuid = :nonceUuid, payload_hash_sha256 = :payloadHashSha256, nonce_composite = :nonceComposite,
                            to_address = :toAddress, amount_native = :amountNative, fee_policy = :feePolicy, fee_cap_native = :feeCapNative,
                            memo = :memo, requested_at = :requestedAt, expires_at = :expiresAt, state = :state,
                            state_reason_code = :stateReasonCode, state_reason_text = :stateReasonText,
                            min_approvals_required = :minApprovalsRequired, approvals_granted = :approvalsGranted,
                            approvals_denied = :approvalsDenied, policy_action_at_creation = :policyActionAtCreation,
                            updated_at = :updatedAt, resolved_at = :resolvedAt
                        WHERE id = :id
                        """)
                .bindMethods(row)
                .execute());
    }

    public boolean deleteApprovalRequest(String id) {
        return jdbi.withHandle(h -> h.createUpdate("DELETE FROM approval_requests WHERE id = :id")
                .bind("id", id)
                .execute() > 0);
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
                WHERE id = :id
                LIMIT 1
                """;

        return jdbi.withHandle(h ->
                h.createQuery(sql)
                        .bind("id", requestId.trim())
                        .map(APPROVAL_REQUEST_MAPPER)
                        .findOne()
                        .orElse(null)
        );
    }

    // --- Paging ---

    public PageResult<ApprovalRequestRow> pageApprovalRequests(String sortBy, String sortDir, int page, int pageSize) {
        return pageApprovalRequestsWithStateFilter(sortBy, sortDir, page, pageSize, null);
    }

    public PageResult<ApprovalRequestRow> pagePendingApprovalRequests(String sortBy, String sortDir, int page, int pageSize) {
        return pageApprovalRequestsWithStateFilter(sortBy, sortDir, page, pageSize, "state IN ('PENDING', 'QUEUED')");
    }

    public PageResult<ApprovalRequestRow> pageNonPendingApprovalRequests(String sortBy, String sortDir, int page, int pageSize) {
        return pageApprovalRequestsWithStateFilter(sortBy, sortDir, page, pageSize, NON_QUEUE_WHERE_SQL);
    }

    public PageResult<ApprovalRequestRow> pageNonPendingApprovalRequests(
            String sortBy,
            String sortDir,
            int page,
            int pageSize,
            String coinFilter,
            String toolFilter,
            String stateFilter,
            String textFilter
    ) {
        String normalizedCoin = normalizeExactFilter(coinFilter);
        String normalizedTool = normalizeExactFilter(toolFilter);
        String normalizedState = normalizeExactFilter(stateFilter);
        String normalizedText = textFilter == null ? "" : textFilter.trim().toLowerCase();

        if (normalizedCoin.isEmpty() && normalizedTool.isEmpty() && normalizedState.isEmpty() && normalizedText.isEmpty()) {
            return pageApprovalRequestsWithStateFilter(sortBy, sortDir, page, pageSize, NON_QUEUE_WHERE_SQL);
        }

        return pageApprovalRequestsWithFilter(sortBy, sortDir, page, pageSize, normalizedCoin, normalizedTool, normalizedState, normalizedText);
    }

    public PageResult<ApprovalRequestRow> pageApprovalRequestsWithFilter(
            String sortBy, String sortDir, int page, int pageSize,
            String coin, String tool, String state, String text) {
        String normalizedCoin = normalizeExactFilter(coin);
        String normalizedTool = normalizeExactFilter(tool);
        String normalizedState = normalizeExactFilter(state);
        String normalizedText = normalizeExactFilter(text);

        String orderBy = REQUEST_SORT_COLUMNS.getOrDefault(sortBy, "updated_at");
        String direction = normalizeSortDirection(sortDir);
        int safePageSize = normalizePageSize(pageSize);

        StringBuilder whereClause = new StringBuilder("WHERE ").append(NON_QUEUE_WHERE_SQL);
        List<String> filterParams = new ArrayList<>();

        if (!normalizedCoin.isEmpty()) {
            whereClause.append(" AND LOWER(coin) = ?");
            filterParams.add(normalizedCoin);
        }
        if (!normalizedTool.isEmpty()) {
            whereClause.append(" AND LOWER(tool_name) = ?");
            filterParams.add(normalizedTool);
        }
        if (!normalizedState.isEmpty()) {
            whereClause.append(" AND LOWER(state) = ?");
            filterParams.add(normalizedState);
        }
        if (!normalizedText.isEmpty()) {
            whereClause.append("""
                      AND (
                          LOWER(id) LIKE ?
                          OR EXISTS (
                              SELECT 1
                              FROM approval_votes v
                              WHERE v.request_id = approval_requests.id
                                AND LOWER(COALESCE(v.decided_by, '')) LIKE ?
                          )
                      )
                    """);
            String likeValue = "%" + normalizedText + "%";
            filterParams.add(likeValue);
            filterParams.add(likeValue);
        }

        String whereSql = whereClause.toString();
        long totalRows = queryCount(jdbi, "SELECT COUNT(*) FROM approval_requests " + whereSql, filterParams);
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
                """.formatted(whereSql, orderBy, direction);

        List<ApprovalRequestRow> rows = jdbi.withHandle(h -> {
            var query = h.createQuery(sql);
            for (int i = 0; i < filterParams.size(); i++) {
                query.bind(i, filterParams.get(i));
            }
            query.bind(filterParams.size(), safePageSize);
            query.bind(filterParams.size() + 1, Math.max(offset, 0));
            return query.map(APPROVAL_REQUEST_MAPPER).list();
        });

        return new PageResult<>(List.copyOf(rows), safePage, safePageSize, totalRows, totalPages, orderBy, direction.toLowerCase());
    }

    public LogQueueFilterOptions loadNonPendingFilterOptions() {
        String sql = """
                SELECT coin, tool_name, state
                FROM approval_requests
                WHERE state NOT IN ('PENDING', 'QUEUED')
                """;

        Set<String> coins = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        Set<String> tools = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        Set<String> states = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

        jdbi.useHandle(h ->
                h.createQuery(sql)
                        .map((rs, ctx) -> new String[]{rs.getString("coin"), rs.getString("tool_name"), rs.getString("state")})
                        .forEach(row -> {
                            if (row[0] != null && !row[0].isBlank()) coins.add(row[0].trim());
                            if (row[1] != null && !row[1].isBlank()) tools.add(row[1].trim());
                            if (row[2] != null && !row[2].isBlank()) states.add(row[2].trim().toUpperCase());
                        })
        );

        return new LogQueueFilterOptions(List.copyOf(coins), List.copyOf(tools), List.copyOf(states));
    }

    // --- Expiry ---

    public List<ApprovalRequestRow> findExpiredPendingRequests() {
        String sql = """
                SELECT id, coin, tool_name, request_session_id, nonce_uuid, payload_hash_sha256, nonce_composite,
                       to_address, amount_native, fee_policy, fee_cap_native, memo,
                       requested_at, expires_at, state, state_reason_code, state_reason_text,
                       min_approvals_required, approvals_granted, approvals_denied, policy_action_at_creation,
                       created_at, updated_at, resolved_at
                FROM approval_requests
                WHERE state IN ('QUEUED', 'PENDING')
                  AND expires_at IS NOT NULL
                  AND expires_at < CURRENT_TIMESTAMP
                """;

        return jdbi.withHandle(h ->
                h.createQuery(sql)
                        .map(APPROVAL_REQUEST_MAPPER)
                        .list()
        );
    }

    public List<ApprovalRequestRow> findApprovedRequests() {
        String sql = """
                SELECT id, coin, tool_name, request_session_id, nonce_uuid, payload_hash_sha256, nonce_composite,
                       to_address, amount_native, fee_policy, fee_cap_native, memo,
                       requested_at, expires_at, state, state_reason_code, state_reason_text,
                       min_approvals_required, approvals_granted, approvals_denied, policy_action_at_creation,
                       created_at, updated_at, resolved_at
                FROM approval_requests
                WHERE state = 'APPROVED'
                ORDER BY requested_at ASC
                """;

        return jdbi.withHandle(h ->
                h.createQuery(sql)
                        .map(APPROVAL_REQUEST_MAPPER)
                        .list()
        );
    }

    public List<ApprovalRequestRow> findVotableRequests() {
        String sql = """
                SELECT id, coin, tool_name, request_session_id, nonce_uuid, payload_hash_sha256, nonce_composite,
                       to_address, amount_native, fee_policy, fee_cap_native, memo,
                       requested_at, expires_at, state, state_reason_code, state_reason_text,
                       min_approvals_required, approvals_granted, approvals_denied, policy_action_at_creation,
                       created_at, updated_at, resolved_at
                FROM approval_requests
                WHERE state IN ('QUEUED', 'PENDING')
                ORDER BY requested_at ASC
                """;

        return jdbi.withHandle(h ->
                h.createQuery(sql)
                        .map(APPROVAL_REQUEST_MAPPER)
                        .list()
        );
    }

    // --- Counts ---

    public int countOpenRequests() {
        return jdbi.withHandle(h ->
                h.createQuery(COUNT_PENDING_SQL)
                        .mapTo(Long.class)
                        .one()
                        .intValue()
        );
    }

    public boolean isLockdownActive() {
        return jdbi.withHandle(h ->
                h.createQuery(LOCKDOWN_ACTIVE_SQL)
                        .mapTo(Long.class)
                        .one() > 0
        );
    }

    // --- CoinRuntime ---

    public List<ApprovalCoinRuntimeRow> listAllCoinRuntimes() {
        return jdbi.withHandle(h -> h.createQuery("SELECT * FROM approval_coin_runtime ORDER BY coin ASC")
                .map(APPROVAL_COIN_RUNTIME_MAPPER)
                .list());
    }

    public ApprovalCoinRuntimeRow findCoinRuntimeByCoin(String coin) {
        return jdbi.withHandle(h -> h.createQuery("SELECT * FROM approval_coin_runtime WHERE coin = :coin")
                .bind("coin", coin)
                .map(APPROVAL_COIN_RUNTIME_MAPPER)
                .findOne()
                .orElse(null));
    }

    public void insertCoinRuntime(ApprovalCoinRuntimeRow row) {
        jdbi.useHandle(h -> h.createUpdate("""
                        INSERT INTO approval_coin_runtime (coin, active_request_id, cooldown_until, lockdown_until, updated_at)
                        VALUES (:coin, :activeRequestId, :cooldownUntil, :lockdownUntil, :updatedAt)
                        """)
                .bindMethods(row)
                .execute());
    }

    public void updateCoinRuntime(ApprovalCoinRuntimeRow row) {
        jdbi.useHandle(h -> h.createUpdate("""
                        UPDATE approval_coin_runtime SET
                            active_request_id = :activeRequestId, cooldown_until = :cooldownUntil,
                            lockdown_until = :lockdownUntil, updated_at = :updatedAt
                        WHERE coin = :coin
                        """)
                .bindMethods(row)
                .execute());
    }

    public boolean deleteCoinRuntime(String coin) {
        return jdbi.withHandle(h -> h.createUpdate("DELETE FROM approval_coin_runtime WHERE coin = :coin")
                .bind("coin", coin)
                .execute() > 0);
    }

    // --- Private ---

    private PageResult<ApprovalRequestRow> pageApprovalRequestsWithStateFilter(
            String sortBy, String sortDir, int page, int pageSize, String stateFilterSql) {
        String orderBy = REQUEST_SORT_COLUMNS.getOrDefault(sortBy, "requested_at");
        String direction = normalizeSortDirection(sortDir);
        int safePageSize = normalizePageSize(pageSize);

        String whereClause = stateFilterSql == null ? "" : " WHERE " + stateFilterSql;
        long totalRows = queryCount(jdbi, "SELECT COUNT(*) FROM approval_requests" + whereClause);
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
                LIMIT :limit OFFSET :offset
                """.formatted(whereClause, orderBy, direction);

        List<ApprovalRequestRow> rows = jdbi.withHandle(h ->
                h.createQuery(sql)
                        .bind("limit", safePageSize)
                        .bind("offset", Math.max(offset, 0))
                        .map(APPROVAL_REQUEST_MAPPER)
                        .list()
        );

        return new PageResult<>(List.copyOf(rows), safePage, safePageSize, totalRows, totalPages, orderBy, direction.toLowerCase());
    }
}
