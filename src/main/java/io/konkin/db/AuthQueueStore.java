package io.konkin.db;

import io.konkin.db.entity.ApprovalChannelRow;
import io.konkin.db.entity.ApprovalCoinRuntimeRow;
import io.konkin.db.entity.ApprovalRequestChannelRow;
import io.konkin.db.entity.ApprovalRequestRow;
import io.konkin.db.entity.ExecutionAttemptDetail;
import io.konkin.db.entity.LogQueueFilterOptions;
import io.konkin.db.entity.PageResult;
import io.konkin.db.entity.RequestChannelDetail;
import io.konkin.db.entity.RequestDependencies;
import io.konkin.db.entity.StateTransitionDetail;
import io.konkin.db.entity.StateTransitionRow;
import io.konkin.db.entity.VoteDetail;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.mapper.RowMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

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
    private static final String NON_QUEUE_WHERE_SQL = "state NOT IN ('PENDING', 'QUEUED')";

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

    // RowMappers ---------------------------------------------------------------------------------

    private static final RowMapper<ApprovalCoinRuntimeRow> APPROVAL_COIN_RUNTIME_MAPPER = (rs, ctx) ->
            new ApprovalCoinRuntimeRow(
                    rs.getString("coin"),
                    rs.getString("active_request_id"),
                    toInstant(rs.getTimestamp("cooldown_until")),
                    toInstant(rs.getTimestamp("lockdown_until")),
                    toInstant(rs.getTimestamp("updated_at"))
            );

    private static final RowMapper<ApprovalRequestChannelRow> APPROVAL_REQUEST_CHANNEL_MAPPER = (rs, ctx) ->
            new ApprovalRequestChannelRow(
                    rs.getLong("id"),
                    rs.getString("request_id"),
                    rs.getString("channel_id"),
                    rs.getString("delivery_state"),
                    toInstant(rs.getTimestamp("first_sent_at")),
                    toInstant(rs.getTimestamp("last_attempt_at")),
                    rs.getInt("attempt_count"),
                    rs.getString("last_error"),
                    toInstant(rs.getTimestamp("created_at"))
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

    private static final RowMapper<ApprovalChannelRow> APPROVAL_CHANNEL_MAPPER = (rs, ctx) ->
            new ApprovalChannelRow(
                    rs.getString("id"),
                    rs.getString("channel_type"),
                    rs.getString("display_name"),
                    rs.getBoolean("enabled"),
                    rs.getString("config_fingerprint"),
                    toInstant(rs.getTimestamp("created_at"))
            );

    private static final RowMapper<StateTransitionRow> STATE_TRANSITION_ROW_MAPPER = (rs, ctx) ->
            new StateTransitionRow(
                    rs.getLong("id"),
                    rs.getString("request_id"),
                    rs.getString("from_state"),
                    rs.getString("to_state"),
                    rs.getString("actor_type"),
                    rs.getString("actor_id"),
                    rs.getString("reason_code"),
                    toInstant(rs.getTimestamp("created_at"))
            );

    private static final RowMapper<StateTransitionDetail> STATE_TRANSITION_DETAIL_MAPPER = (rs, ctx) ->
            new StateTransitionDetail(
                    rs.getLong("id"),
                    rs.getString("request_id"),
                    rs.getString("from_state"),
                    rs.getString("to_state"),
                    rs.getString("actor_type"),
                    rs.getString("actor_id"),
                    rs.getString("reason_code"),
                    rs.getString("reason_text"),
                    rs.getString("metadata_json"),
                    toInstant(rs.getTimestamp("created_at"))
            );

    private static final RowMapper<RequestChannelDetail> REQUEST_CHANNEL_DETAIL_MAPPER = (rs, ctx) ->
            new RequestChannelDetail(
                    rs.getLong("id"),
                    rs.getString("request_id"),
                    rs.getString("channel_id"),
                    rs.getString("delivery_state"),
                    toInstant(rs.getTimestamp("first_sent_at")),
                    toInstant(rs.getTimestamp("last_attempt_at")),
                    rs.getInt("attempt_count"),
                    rs.getString("last_error"),
                    toInstant(rs.getTimestamp("created_at"))
            );

    private static final RowMapper<VoteDetail> VOTE_DETAIL_MAPPER = (rs, ctx) ->
            new VoteDetail(
                    rs.getLong("id"),
                    rs.getString("request_id"),
                    rs.getString("channel_id"),
                    rs.getString("decision"),
                    rs.getString("decision_reason"),
                    rs.getString("decided_by"),
                    toInstant(rs.getTimestamp("decided_at"))
            );

    private static final RowMapper<ExecutionAttemptDetail> EXECUTION_ATTEMPT_DETAIL_MAPPER = (rs, ctx) ->
            new ExecutionAttemptDetail(
                    rs.getLong("id"),
                    rs.getString("request_id"),
                    rs.getInt("attempt_no"),
                    toInstant(rs.getTimestamp("started_at")),
                    toInstant(rs.getTimestamp("finished_at")),
                    rs.getString("result"),
                    rs.getString("error_class"),
                    rs.getString("error_message"),
                    rs.getString("txid"),
                    rs.getString("daemon_fee_native")
            );

    // --------------------------------------------------------------------------------------------

    private final Jdbi jdbi;

    public AuthQueueStore(DataSource dataSource) {
        this.jdbi = JdbiFactory.create(dataSource);
    }

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

    public void insertChannel(ApprovalChannelRow row) {
        jdbi.useHandle(h -> h.createUpdate("""
                        INSERT INTO approval_channels (id, channel_type, display_name, enabled, config_fingerprint, created_at)
                        VALUES (:id, :channelType, :displayName, :enabled, :configFingerprint, :createdAt)
                        """)
                .bindMethods(row)
                .execute());
    }

    public void updateChannel(ApprovalChannelRow row) {
        jdbi.useHandle(h -> h.createUpdate("""
                        UPDATE approval_channels SET
                            channel_type = :channelType, display_name = :displayName,
                            enabled = :enabled, config_fingerprint = :configFingerprint
                        WHERE id = :id
                        """)
                .bindMethods(row)
                .execute());
    }

    public boolean deleteChannel(String id) {
        return jdbi.withHandle(h -> h.createUpdate("DELETE FROM approval_channels WHERE id = :id")
                .bind("id", id)
                .execute() > 0);
    }

    public List<VoteDetail> listAllVotes() {
        return jdbi.withHandle(h -> h.createQuery("SELECT * FROM approval_votes ORDER BY decided_at DESC")
                .map(VOTE_DETAIL_MAPPER)
                .list());
    }

    public VoteDetail findVoteById(long id) {
        return jdbi.withHandle(h -> h.createQuery("SELECT * FROM approval_votes WHERE id = :id")
                .bind("id", id)
                .map(VOTE_DETAIL_MAPPER)
                .findOne()
                .orElse(null));
    }

    public void insertVote(VoteDetail row) {
        jdbi.useHandle(h -> h.createUpdate("""
                        INSERT INTO approval_votes (request_id, channel_id, decision, decision_reason, decided_by, decided_at)
                        VALUES (:requestId, :channelId, :decision, :decisionReason, :decidedBy, :decidedAt)
                        """)
                .bindMethods(row)
                .execute());
    }

    public void updateVote(VoteDetail row) {
        jdbi.useHandle(h -> h.createUpdate("""
                        UPDATE approval_votes SET
                            request_id = :requestId, channel_id = :channelId, decision = :decision,
                            decision_reason = :decisionReason, decided_by = :decidedBy, decided_at = :decidedAt
                        WHERE id = :id
                        """)
                .bindMethods(row)
                .execute());
    }

    public boolean deleteVote(long id) {
        return jdbi.withHandle(h -> h.createUpdate("DELETE FROM approval_votes WHERE id = :id")
                .bind("id", id)
                .execute() > 0);
    }

    public List<StateTransitionRow> listAllStateTransitions() {
        return jdbi.withHandle(h -> h.createQuery("SELECT * FROM approval_state_transitions ORDER BY created_at DESC")
                .map(STATE_TRANSITION_ROW_MAPPER)
                .list());
    }

    public StateTransitionRow findStateTransitionById(long id) {
        return jdbi.withHandle(h -> h.createQuery("SELECT * FROM approval_state_transitions WHERE id = :id")
                .bind("id", id)
                .map(STATE_TRANSITION_ROW_MAPPER)
                .findOne()
                .orElse(null));
    }

    public void insertStateTransition(StateTransitionRow row) {
        jdbi.useHandle(h -> h.createUpdate("""
                        INSERT INTO approval_state_transitions (request_id, from_state, to_state, actor_type, actor_id, reason_code, created_at)
                        VALUES (:requestId, :fromState, :toState, :actorType, :actorId, :reasonCode, :createdAt)
                        """)
                .bindMethods(row)
                .execute());
    }

    public boolean deleteStateTransition(long id) {
        return jdbi.withHandle(h -> h.createUpdate("DELETE FROM approval_state_transitions WHERE id = :id")
                .bind("id", id)
                .execute() > 0);
    }

    public List<ApprovalRequestChannelRow> listAllRequestChannels() {
        return jdbi.withHandle(h -> h.createQuery("SELECT * FROM approval_request_channels ORDER BY created_at DESC")
                .map(APPROVAL_REQUEST_CHANNEL_MAPPER)
                .list());
    }

    public ApprovalRequestChannelRow findRequestChannelById(long id) {
        return jdbi.withHandle(h -> h.createQuery("SELECT * FROM approval_request_channels WHERE id = :id")
                .bind("id", id)
                .map(APPROVAL_REQUEST_CHANNEL_MAPPER)
                .findOne()
                .orElse(null));
    }

    public void insertRequestChannel(ApprovalRequestChannelRow row) {
        jdbi.useHandle(h -> h.createUpdate("""
                        INSERT INTO approval_request_channels (request_id, channel_id, delivery_state, first_sent_at, last_attempt_at, attempt_count, last_error, created_at)
                        VALUES (:requestId, :channelId, :deliveryState, :firstSentAt, :lastAttemptAt, :attemptCount, :lastError, :createdAt)
                        """)
                .bindMethods(row)
                .execute());
    }

    public void updateRequestChannel(ApprovalRequestChannelRow row) {
        jdbi.useHandle(h -> h.createUpdate("""
                        UPDATE approval_request_channels SET
                            request_id = :requestId, channel_id = :channelId, delivery_state = :deliveryState,
                            first_sent_at = :firstSentAt, last_attempt_at = :lastAttemptAt,
                            attempt_count = :attemptCount, last_error = :lastError
                        WHERE id = :id
                        """)
                .bindMethods(row)
                .execute());
    }

    public boolean deleteRequestChannel(long id) {
        return jdbi.withHandle(h -> h.createUpdate("DELETE FROM approval_request_channels WHERE id = :id")
                .bind("id", id)
                .execute() > 0);
    }

    public List<ExecutionAttemptDetail> listAllExecutionAttempts() {
        return jdbi.withHandle(h -> h.createQuery("SELECT * FROM approval_execution_attempts ORDER BY started_at DESC")
                .map(EXECUTION_ATTEMPT_DETAIL_MAPPER)
                .list());
    }

    public ExecutionAttemptDetail findExecutionAttemptById(long id) {
        return jdbi.withHandle(h -> h.createQuery("SELECT * FROM approval_execution_attempts WHERE id = :id")
                .bind("id", id)
                .map(EXECUTION_ATTEMPT_DETAIL_MAPPER)
                .findOne()
                .orElse(null));
    }

    public void insertExecutionAttempt(ExecutionAttemptDetail row) {
        jdbi.useHandle(h -> h.createUpdate("""
                        INSERT INTO approval_execution_attempts (request_id, attempt_no, started_at, finished_at, result, error_class, error_message, txid, daemon_fee_native)
                        VALUES (:requestId, :attemptNo, :startedAt, :finishedAt, :result, :errorClass, :errorMessage, :txid, :daemonFeeNative)
                        """)
                .bindMethods(row)
                .execute());
    }

    public boolean deleteExecutionAttempt(long id) {
        return jdbi.withHandle(h -> h.createUpdate("DELETE FROM approval_execution_attempts WHERE id = :id")
                .bind("id", id)
                .execute() > 0);
    }

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

    private PageResult<ApprovalRequestRow> pageApprovalRequestsWithStateFilter(
            String sortBy, String sortDir, int page, int pageSize, String stateFilterSql) {
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
        long totalRows = queryCount("SELECT COUNT(*) FROM approval_requests " + whereSql, filterParams);
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

    public List<ApprovalChannelRow> listChannels() {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM approval_channels ORDER BY id ASC")
                        .map(APPROVAL_CHANNEL_MAPPER)
                        .list()
        );
    }

    public ApprovalChannelRow findChannelById(String channelId) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM approval_channels WHERE id = :id")
                        .bind("id", channelId)
                        .map(APPROVAL_CHANNEL_MAPPER)
                        .findOne()
                        .orElse(null)
        );
    }

    public List<VoteDetail> listVotesForRequest(String requestId) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM approval_votes WHERE request_id = :requestId ORDER BY decided_at ASC")
                        .bind("requestId", requestId)
                        .map(VOTE_DETAIL_MAPPER)
                        .list()
        );
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
                LIMIT :limit OFFSET :offset
                """.formatted(orderBy, direction);

        List<StateTransitionRow> rows = jdbi.withHandle(h ->
                h.createQuery(sql)
                        .bind("limit", safePageSize)
                        .bind("offset", Math.max(offset, 0))
                        .map(STATE_TRANSITION_ROW_MAPPER)
                        .list()
        );

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
                WHERE request_id IN (<ids>)
                ORDER BY request_id ASC, created_at ASC, id ASC
                """;

        Map<String, List<StateTransitionDetail>> byRequestId = new LinkedHashMap<>();
        jdbi.useHandle(h ->
                h.createQuery(sql)
                        .bindList("ids", requestIds)
                        .map(STATE_TRANSITION_DETAIL_MAPPER)
                        .forEach(detail -> append(byRequestId, detail.requestId(), detail))
        );
        return byRequestId;
    }

    private Map<String, List<RequestChannelDetail>> loadRequestChannelDetails(List<String> requestIds) {
        String sql = """
                SELECT id, request_id, channel_id, delivery_state, first_sent_at, last_attempt_at, attempt_count, last_error, created_at
                FROM approval_request_channels
                WHERE request_id IN (<ids>)
                ORDER BY request_id ASC, created_at ASC, id ASC
                """;

        Map<String, List<RequestChannelDetail>> byRequestId = new LinkedHashMap<>();
        jdbi.useHandle(h ->
                h.createQuery(sql)
                        .bindList("ids", requestIds)
                        .map(REQUEST_CHANNEL_DETAIL_MAPPER)
                        .forEach(detail -> append(byRequestId, detail.requestId(), detail))
        );
        return byRequestId;
    }

    private Map<String, List<VoteDetail>> loadVoteDetails(List<String> requestIds) {
        String sql = """
                SELECT id, request_id, channel_id, decision, decision_reason, decided_by, decided_at
                FROM approval_votes
                WHERE request_id IN (<ids>)
                ORDER BY request_id ASC, decided_at ASC, id ASC
                """;

        Map<String, List<VoteDetail>> byRequestId = new LinkedHashMap<>();
        jdbi.useHandle(h ->
                h.createQuery(sql)
                        .bindList("ids", requestIds)
                        .map(VOTE_DETAIL_MAPPER)
                        .forEach(detail -> append(byRequestId, detail.requestId(), detail))
        );
        return byRequestId;
    }

    private Map<String, List<ExecutionAttemptDetail>> loadExecutionAttemptDetails(List<String> requestIds) {
        String sql = """
                SELECT id, request_id, attempt_no, started_at, finished_at, result, error_class, error_message, txid, daemon_fee_native
                FROM approval_execution_attempts
                WHERE request_id IN (<ids>)
                ORDER BY request_id ASC, attempt_no ASC, id ASC
                """;

        Map<String, List<ExecutionAttemptDetail>> byRequestId = new LinkedHashMap<>();
        jdbi.useHandle(h ->
                h.createQuery(sql)
                        .bindList("ids", requestIds)
                        .map(EXECUTION_ATTEMPT_DETAIL_MAPPER)
                        .forEach(detail -> append(byRequestId, detail.requestId(), detail))
        );
        return byRequestId;
    }

    private static <T> void append(Map<String, List<T>> map, String key, T item) {
        map.computeIfAbsent(key, ignored -> new ArrayList<>()).add(item);
    }

    private long queryCount(String sql) {
        return queryCount(sql, List.of());
    }

    private long queryCount(String sql, List<String> params) {
        return jdbi.withHandle(h -> {
            var query = h.createQuery(sql);
            for (int i = 0; i < params.size(); i++) {
                query.bind(i, params.get(i));
            }
            return query.mapTo(Long.class).one();
        });
    }

    private static int normalizePageSize(int pageSize) {
        if (pageSize <= 0) return DEFAULT_PAGE_SIZE;
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }

    private static int normalizePage(int page, int totalPages) {
        if (totalPages <= 0) return 1;
        if (page <= 0) return 1;
        return Math.min(page, totalPages);
    }

    private static String normalizeSortDirection(String sortDir) {
        return "asc".equalsIgnoreCase(sortDir) ? "ASC" : "DESC";
    }

    private static String normalizeExactFilter(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
