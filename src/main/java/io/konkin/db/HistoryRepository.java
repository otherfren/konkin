package io.konkin.db;

import io.konkin.db.entity.ExecutionAttemptDetail;
import io.konkin.db.entity.PageResult;
import io.konkin.db.entity.StateTransitionRow;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.mapper.RowMapper;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static io.konkin.db.SqlUtils.*;

public class HistoryRepository {

    private static final Map<String, String> TRANSITION_SORT_COLUMNS = Map.of(
            "created_at", "created_at",
            "request_id", "request_id",
            "from_state", "from_state",
            "to_state", "to_state",
            "actor_type", "actor_type",
            "actor_id", "actor_id",
            "reason_code", "reason_code"
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

    private final Jdbi jdbi;

    public HistoryRepository(DataSource dataSource) {
        this.jdbi = JdbiFactory.create(dataSource);
    }

    // --- StateTransition ---

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

    public PageResult<StateTransitionRow> pageStateTransitions(String sortBy, String sortDir, int page, int pageSize) {
        String orderBy = TRANSITION_SORT_COLUMNS.getOrDefault(sortBy, "created_at");
        String direction = normalizeSortDirection(sortDir);
        int safePageSize = normalizePageSize(pageSize);
        long totalRows = queryCount(jdbi, "SELECT COUNT(*) FROM approval_state_transitions");
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

    // --- ExecutionAttempt ---

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
}
