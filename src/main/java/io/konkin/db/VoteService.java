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

import io.konkin.db.entity.ApprovalRequestRow;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * Transactional vote-casting service. Wraps the entire vote + tally + state-update
 * cycle in a single database transaction with row-level locking on the approval request.
 * This prevents the race condition where concurrent voters can cause incorrect approval
 * threshold evaluation.
 */
public class VoteService {

    private static final Logger log = LoggerFactory.getLogger(VoteService.class);

    private final Jdbi jdbi;

    public VoteService(DataSource dataSource) {
        this.jdbi = JdbiFactory.create(dataSource);
    }

    /**
     * Cast a vote on an approval request within a single transaction.
     * The approval_requests row is locked (SELECT ... FOR UPDATE) to prevent
     * concurrent vote-tally-update races.
     *
     * @param requestId   the approval request to vote on
     * @param channelId   the voting channel (e.g., "agent-arthur", "telegram", "web-ui")
     * @param decision    "approve" or "deny"
     * @param reason      optional reason text (nullable)
     * @param decidedBy   identifier of the voter (nullable)
     * @param actorType    actor type for state transition log (e.g., "agent", "telegram", "web_ui")
     * @param actorId      actor id for state transition log
     * @param vetoChannels channels with veto power for this coin (empty/null = any deny vote denies)
     * @return the result of the vote
     */
    public VoteResult castVote(
            String requestId,
            String channelId,
            String decision,
            String reason,
            String decidedBy,
            String actorType,
            String actorId,
            List<String> vetoChannels
    ) {
        return jdbi.inTransaction(h -> {
            // 1. Lock the approval request row
            ApprovalRequestRow row = selectForUpdate(h, requestId);
            if (row == null) {
                return VoteResult.error("request_not_found_or_resolved", "Request not found or already resolved");
            }

            // 2. Verify voteable state
            if (!isVoteableState(row.state())) {
                return VoteResult.error("request_not_found_or_resolved", "Request not found or already resolved");
            }

            // 3. Check expiry
            Instant now = Instant.now();
            if (row.expiresAt() != null && row.expiresAt().isBefore(now)) {
                return VoteResult.error("request_expired", "Request has expired and can no longer be voted on");
            }

            // 4. Check for duplicate vote from this channel
            long existingCount = h.createQuery(
                            "SELECT COUNT(*) FROM approval_votes WHERE request_id = :rid AND LOWER(channel_id) = LOWER(:cid)")
                    .bind("rid", requestId)
                    .bind("cid", channelId)
                    .mapTo(Long.class)
                    .one();
            if (existingCount > 0) {
                return VoteResult.error("already_voted", "This channel has already voted on this request");
            }

            // 5. Insert the vote
            h.createUpdate("""
                            INSERT INTO approval_votes (request_id, channel_id, decision, decision_reason, decided_by, decided_at)
                            VALUES (:requestId, :channelId, :decision, :reason, :decidedBy, :decidedAt)
                            """)
                    .bind("requestId", requestId)
                    .bind("channelId", channelId)
                    .bind("decision", decision)
                    .bind("reason", reason)
                    .bind("decidedBy", decidedBy)
                    .bind("decidedAt", Timestamp.from(now))
                    .execute();

            // 6. Tally all votes (within the same transaction, so we see our insert)
            long approvalsGranted = h.createQuery(
                            "SELECT COUNT(*) FROM approval_votes WHERE request_id = :rid AND LOWER(decision) = 'approve'")
                    .bind("rid", requestId)
                    .mapTo(Long.class)
                    .one();
            long approvalsDenied = h.createQuery(
                            "SELECT COUNT(*) FROM approval_votes WHERE request_id = :rid AND LOWER(decision) = 'deny'")
                    .bind("rid", requestId)
                    .mapTo(Long.class)
                    .one();

            // 7. Compute next state
            String previousState = row.state();
            String nextState = previousState;
            String reasonCode = row.stateReasonCode();
            String reasonText = row.stateReasonText();
            Instant resolvedAt = row.resolvedAt();

            boolean hasVetoDeny = false;
            if (approvalsDenied > 0) {
                if (vetoChannels == null || vetoChannels.isEmpty()) {
                    // No veto channels configured — any deny vote triggers denial
                    hasVetoDeny = true;
                } else {
                    // Only deny votes from veto channels trigger denial
                    long vetoDenyCount = h.createQuery("""
                                    SELECT COUNT(*) FROM approval_votes
                                    WHERE request_id = :rid AND LOWER(decision) = 'deny'
                                      AND LOWER(channel_id) IN (<vetoChannels>)
                                    """)
                            .bind("rid", requestId)
                            .bindList("vetoChannels", vetoChannels.stream().map(String::toLowerCase).toList())
                            .mapTo(Long.class)
                            .one();
                    hasVetoDeny = vetoDenyCount > 0;
                }
            }

            if (hasVetoDeny) {
                nextState = "DENIED";
                reasonCode = "vote_denied";
                reasonText = "Denied by " + actorType + " approval vote";
                resolvedAt = now;
            } else if (approvalsGranted >= Math.max(1, row.minApprovalsRequired())) {
                nextState = "APPROVED";
                reasonCode = "approval_threshold_met";
                reasonText = "Minimum approvals reached";
            } else if ("QUEUED".equalsIgnoreCase(previousState)) {
                nextState = "PENDING";
                reasonCode = "awaiting_more_votes";
                reasonText = "Awaiting additional approvals";
            }

            // 8. Update the approval request
            h.createUpdate("""
                            UPDATE approval_requests SET
                                state = :state, state_reason_code = :reasonCode, state_reason_text = :reasonText,
                                approvals_granted = :approvalsGranted, approvals_denied = :approvalsDenied,
                                updated_at = :updatedAt, resolved_at = :resolvedAt
                            WHERE id = :id
                            """)
                    .bind("state", nextState)
                    .bind("reasonCode", reasonCode)
                    .bind("reasonText", reasonText)
                    .bind("approvalsGranted", (int) approvalsGranted)
                    .bind("approvalsDenied", (int) approvalsDenied)
                    .bind("updatedAt", Timestamp.from(now))
                    .bind("resolvedAt", resolvedAt != null ? Timestamp.from(resolvedAt) : null)
                    .bind("id", requestId)
                    .execute();

            // 9. Insert state transition if state changed
            if (!previousState.equalsIgnoreCase(nextState)) {
                h.createUpdate("""
                                INSERT INTO approval_state_transitions (request_id, from_state, to_state, actor_type, actor_id, reason_code, created_at)
                                VALUES (:requestId, :fromState, :toState, :actorType, :actorId, :reasonCode, :createdAt)
                                """)
                        .bind("requestId", requestId)
                        .bind("fromState", previousState)
                        .bind("toState", nextState)
                        .bind("actorType", actorType)
                        .bind("actorId", actorId)
                        .bind("reasonCode", reasonCode)
                        .bind("createdAt", Timestamp.from(now))
                        .execute();
            }

            return new VoteResult(
                    true, null, null,
                    previousState, nextState,
                    (int) approvalsGranted, (int) approvalsDenied
            );
        });
    }

    private static ApprovalRequestRow selectForUpdate(Handle h, String requestId) {
        return h.createQuery("""
                        SELECT id, coin, tool_name, request_session_id, nonce_uuid, payload_hash_sha256, nonce_composite,
                               to_address, amount_native, fee_policy, fee_cap_native, memo, reason,
                               requested_at, expires_at, state, state_reason_code, state_reason_text,
                               min_approvals_required, approvals_granted, approvals_denied, policy_action_at_creation,
                               created_at, updated_at, resolved_at
                        FROM approval_requests
                        WHERE id = :id
                        FOR UPDATE
                        """)
                .bind("id", requestId)
                .map((rs, ctx) -> new ApprovalRequestRow(
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
                        rs.getString("reason"),
                        SqlUtils.toInstant(rs.getTimestamp("requested_at")),
                        SqlUtils.toInstant(rs.getTimestamp("expires_at")),
                        rs.getString("state"),
                        rs.getString("state_reason_code"),
                        rs.getString("state_reason_text"),
                        rs.getInt("min_approvals_required"),
                        rs.getInt("approvals_granted"),
                        rs.getInt("approvals_denied"),
                        rs.getString("policy_action_at_creation"),
                        SqlUtils.toInstant(rs.getTimestamp("created_at")),
                        SqlUtils.toInstant(rs.getTimestamp("updated_at")),
                        SqlUtils.toInstant(rs.getTimestamp("resolved_at"))
                ))
                .findOne()
                .orElse(null);
    }

    private static boolean isVoteableState(String state) {
        if (state == null) return false;
        String normalized = state.trim().toUpperCase();
        return "QUEUED".equals(normalized) || "PENDING".equals(normalized);
    }

    public record VoteResult(
            boolean success,
            String error,
            String errorMessage,
            String previousState,
            String nextState,
            int approvalsGranted,
            int approvalsDenied
    ) {
        public static VoteResult error(String error, String message) {
            return new VoteResult(false, error, message, null, null, 0, 0);
        }
    }
}
