/*
 * Copyright 2026 Peter Geschel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.konkin.db;

import io.konkin.db.entity.ExecutionAttemptDetail;
import io.konkin.db.entity.RequestChannelDetail;
import io.konkin.db.entity.RequestDependencies;
import io.konkin.db.entity.StateTransitionDetail;
import io.konkin.db.entity.VoteDetail;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.mapper.RowMapper;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.konkin.db.SqlUtils.append;
import static io.konkin.db.SqlUtils.toInstant;

public class RequestDependencyLoader {

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

    private final Jdbi jdbi;

    public RequestDependencyLoader(DataSource dataSource) {
        this.jdbi = JdbiFactory.create(dataSource);
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
}