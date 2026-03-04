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

import io.konkin.db.entity.ApprovalChannelRow;
import io.konkin.db.entity.ApprovalRequestChannelRow;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.mapper.RowMapper;

import javax.sql.DataSource;
import java.util.List;

import static io.konkin.db.SqlUtils.toInstant;

public class ChannelRepository {

    private static final RowMapper<ApprovalChannelRow> APPROVAL_CHANNEL_MAPPER = (rs, ctx) ->
            new ApprovalChannelRow(
                    rs.getString("id"),
                    rs.getString("channel_type"),
                    rs.getString("display_name"),
                    rs.getBoolean("enabled"),
                    rs.getString("config_fingerprint"),
                    toInstant(rs.getTimestamp("created_at"))
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

    private final Jdbi jdbi;

    public ChannelRepository(DataSource dataSource) {
        this.jdbi = JdbiFactory.create(dataSource);
    }

    // --- ApprovalChannel ---

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

    // --- ApprovalRequestChannel ---

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
}