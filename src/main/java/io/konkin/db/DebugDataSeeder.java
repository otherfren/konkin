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

import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.PreparedBatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
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

    private final Jdbi jdbi;
    private final Random random;

    public DebugDataSeeder(DataSource dataSource) {
        this.jdbi = JdbiFactory.create(dataSource);
        this.random = new Random(42L);
    }

    public void seedIfEnabled(boolean debugEnabled, boolean seedFakeData) {
        if (!debugEnabled || !seedFakeData) {
            return;
        }

        jdbi.useTransaction(handle -> {
            long existing = handle.createQuery("SELECT COUNT(*) FROM approval_requests")
                    .mapTo(Long.class).one();
            if (existing > 0) {
                log.info("Debug seeding skipped because approval_requests already contains {} row(s)", existing);
                return;
            }

            seedChannels(handle);
            seedRequestsWithActivity(handle, 240);
            seedCoinRuntime(handle);
            log.info("Debug seeding complete: inserted synthetic approval queue activity");
        });
    }

    private void seedChannels(Handle handle) {
        PreparedBatch batch = handle.prepareBatch("""
                MERGE INTO approval_channels (id, channel_type, display_name, enabled, config_fingerprint, created_at)
                KEY (id)
                VALUES (:id, :type, :name, :enabled, :fingerprint, :createdAt)
                """);
        Instant twoDA = Instant.now().minus(2, ChronoUnit.DAYS);
        addChannelToBatch(batch, "dbg-telegram-a", "telegram", "Telegram Team A", twoDA);
        addChannelToBatch(batch, "dbg-telegram-b", "telegram", "Telegram Team B", twoDA);
        addChannelToBatch(batch, "dbg-mcp-agent-1", "mcp_agent", "MCP Agent 1", twoDA);
        addChannelToBatch(batch, "dbg-email-ops", "email", "Ops Email", twoDA);
        batch.execute();
    }

    private static void addChannelToBatch(PreparedBatch batch, String id, String type, String name, Instant createdAt) {
        batch.bind("id", id)
                .bind("type", type)
                .bind("name", name)
                .bind("enabled", true)
                .bind("fingerprint", "dbg-fingerprint-" + id)
                .bind("createdAt", ts(createdAt))
                .add();
    }

    private void seedRequestsWithActivity(Handle handle, int totalRequests) {
        Instant base = Instant.now().minus(36, ChronoUnit.HOURS);

        PreparedBatch requestBatch = handle.prepareBatch("""
                INSERT INTO approval_requests (
                    id, coin, tool_name, request_session_id, nonce_uuid, payload_hash_sha256, nonce_composite,
                    to_address, amount_native, fee_policy, fee_cap_native, memo,
                    requested_at, expires_at, state, state_reason_code, state_reason_text,
                    min_approvals_required, approvals_granted, approvals_denied, policy_action_at_creation,
                    created_at, updated_at, resolved_at
                ) VALUES (
                    :id, :coin, :tool, :sessionId, :nonceUuid, :sha256, :nonce,
                    :toAddress, :amount, :feePolicy, :feeCap, :memo,
                    :requestedAt, :expiresAt, :state, :reasonCode, :reasonText,
                    :minApprovals, :approvalsGranted, :approvalsDenied, :policyAction,
                    :createdAt, :updatedAt, :resolvedAt
                )
                """);

        PreparedBatch transitionBatch = handle.prepareBatch("""
                INSERT INTO approval_state_transitions (
                    request_id, from_state, to_state, actor_type, actor_id, reason_code, reason_text, metadata_json, created_at
                ) VALUES (:requestId, :fromState, :toState, :actorType, :actorId, :reasonCode, :reasonText, :metadata, :createdAt)
                """);

        PreparedBatch channelBatch = handle.prepareBatch("""
                INSERT INTO approval_request_channels (
                    request_id, channel_id, delivery_state, first_sent_at, last_attempt_at, attempt_count, last_error, created_at
                ) VALUES (:requestId, :channelId, :deliveryState, :firstSentAt, :lastAttemptAt, :attemptCount, :lastError, :createdAt)
                """);

        PreparedBatch voteBatch = handle.prepareBatch("""
                INSERT INTO approval_votes (
                    request_id, channel_id, decision, decision_reason, decided_by, decided_at
                ) VALUES (:requestId, :channelId, :decision, :reason, :decidedBy, :decidedAt)
                """);

        PreparedBatch executionBatch = handle.prepareBatch("""
                INSERT INTO approval_execution_attempts (
                    request_id, attempt_no, started_at, finished_at, result, error_class, error_message, txid, daemon_fee_native
                ) VALUES (:requestId, :attemptNo, :startedAt, :finishedAt, :result, :errorClass, :errorMessage, :txid, :daemonFee)
                """);

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

            requestBatch
                    .bind("id", requestId)
                    .bind("coin", coin)
                    .bind("tool", tool)
                    .bind("sessionId", "dbg-session-" + ((i % 12) + 1))
                    .bind("nonceUuid", "dbg-nonce-uuid-" + i)
                    .bind("sha256", "dbg-sha256-" + i)
                    .bind("nonce", nonceComposite)
                    .bind("toAddress", "addr-" + coin + "-" + (1000 + i))
                    .bind("amount", String.format("%.8f", 0.01 + (i % 19) * 0.0025))
                    .bind("feePolicy", i % 3 == 0 ? "dynamic" : "fixed")
                    .bind("feeCap", String.format("%.8f", 0.001 + (i % 5) * 0.0002))
                    .bind("memo", "debug request #" + (i + 1))
                    .bind("requestedAt", ts(requestedAt))
                    .bind("expiresAt", ts(expiresAt))
                    .bind("state", state)
                    .bind("reasonCode", "dbg_reason_" + state.toLowerCase())
                    .bind("reasonText", "Synthetic transition to " + state)
                    .bind("minApprovals", 2)
                    .bind("approvalsGranted", approvalsGranted)
                    .bind("approvalsDenied", approvalsDenied)
                    .bind("policyAction", "manual")
                    .bind("createdAt", ts(requestedAt))
                    .bind("updatedAt", ts(updatedAt))
                    .bind("resolvedAt", ts(resolvedAt))
                    .add();

            addTransitionToBatch(transitionBatch, requestId, null, "QUEUED", "system", "queue", requestedAt);
            if (!"QUEUED".equals(state)) {
                addTransitionToBatch(transitionBatch, requestId, "QUEUED", state, "approver", "debug-seeder", updatedAt);
            }

            addRequestChannelToBatch(channelBatch, requestId, "dbg-telegram-a", requestedAt, state);
            addRequestChannelToBatch(channelBatch, requestId, "dbg-mcp-agent-1", requestedAt.plus(10, ChronoUnit.SECONDS), state);
            addRequestChannelToBatch(channelBatch, requestId, "dbg-email-ops", requestedAt.plus(20, ChronoUnit.SECONDS), state);

            if (approvalsGranted > 0) {
                addVoteToBatch(voteBatch, requestId, "dbg-telegram-a", "approve", requestedAt.plus(3, ChronoUnit.MINUTES));
            }
            if (approvalsGranted > 1) {
                addVoteToBatch(voteBatch, requestId, "dbg-mcp-agent-1", "approve", requestedAt.plus(5, ChronoUnit.MINUTES));
            }
            if (approvalsDenied > 0) {
                addVoteToBatch(voteBatch, requestId, "dbg-email-ops", "deny", requestedAt.plus(4, ChronoUnit.MINUTES));
            }

            if ("COMPLETED".equals(state) || "FAILED".equals(state) || "EXECUTING".equals(state)) {
                addExecutionToBatch(executionBatch, requestId, 1, requestedAt.plus(6, ChronoUnit.MINUTES), state);
            }
        }

        requestBatch.execute();
        transitionBatch.execute();
        channelBatch.execute();
        voteBatch.execute();
        executionBatch.execute();
    }

    private static void addTransitionToBatch(
            PreparedBatch batch, String requestId, String fromState, String toState,
            String actorType, String actorId, Instant at) {
        batch.bind("requestId", requestId)
                .bind("fromState", fromState)
                .bind("toState", toState)
                .bind("actorType", actorType)
                .bind("actorId", actorId)
                .bind("reasonCode", "debug")
                .bind("reasonText", "debug seeder transition")
                .bind("metadata", "{\"seeded\":true,\"to\":\"" + toState + "\"}")
                .bind("createdAt", ts(at))
                .add();
    }

    private static void addRequestChannelToBatch(
            PreparedBatch batch, String requestId, String channelId, Instant firstSentAt, String state) {
        String deliveryState = ("QUEUED".equals(state) || "PENDING".equals(state)) ? "queued" : "acked";
        batch.bind("requestId", requestId)
                .bind("channelId", channelId)
                .bind("deliveryState", deliveryState)
                .bind("firstSentAt", ts(firstSentAt))
                .bind("lastAttemptAt", ts(firstSentAt.plus(90, ChronoUnit.SECONDS)))
                .bind("attemptCount", deliveryState.equals("queued") ? 0 : 1)
                .bind("lastError", (String) null)
                .bind("createdAt", ts(firstSentAt))
                .add();
    }

    private static void addVoteToBatch(
            PreparedBatch batch, String requestId, String channelId, String decision, Instant decidedAt) {
        batch.bind("requestId", requestId)
                .bind("channelId", channelId)
                .bind("decision", decision)
                .bind("reason", "debug decision")
                .bind("decidedBy", "dbg-actor-" + channelId)
                .bind("decidedAt", ts(decidedAt))
                .add();
    }

    private void addExecutionToBatch(
            PreparedBatch batch, String requestId, int attemptNo, Instant startedAt, String state) {
        batch.bind("requestId", requestId)
                .bind("attemptNo", attemptNo)
                .bind("startedAt", ts(startedAt));

        switch (state) {
            case "EXECUTING" -> batch
                    .bind("finishedAt", (Timestamp) null)
                    .bind("result", "transient_error")
                    .bind("errorClass", "java.net.SocketTimeoutException")
                    .bind("errorMessage", "debug transient error while still executing")
                    .bind("txid", (String) null);
            case "FAILED" -> batch
                    .bind("finishedAt", ts(startedAt.plus(2, ChronoUnit.MINUTES)))
                    .bind("result", "non_retryable_error")
                    .bind("errorClass", "java.lang.IllegalStateException")
                    .bind("errorMessage", "debug non-retryable failure")
                    .bind("txid", (String) null);
            default -> batch
                    .bind("finishedAt", ts(startedAt.plus(45, ChronoUnit.SECONDS)))
                    .bind("result", "success")
                    .bind("errorClass", (String) null)
                    .bind("errorMessage", (String) null)
                    .bind("txid", "dbg-tx-" + requestId);
        }

        batch.bind("daemonFee", String.format("%.8f", 0.0008 + random.nextDouble() * 0.0009))
                .add();
    }

    private void seedCoinRuntime(Handle handle) {
        PreparedBatch batch = handle.prepareBatch("""
                MERGE INTO approval_coin_runtime (coin, active_request_id, cooldown_until, lockdown_until, updated_at)
                KEY (coin)
                VALUES (:coin, :activeReqId, :cooldownUntil, :lockdownUntil, :updatedAt)
                """);
        Instant now = Instant.now();
        addRuntimeToBatch(batch, "bitcoin", "dbg-req-0007", now.plus(8, ChronoUnit.MINUTES), null, now);
        addRuntimeToBatch(batch, "monero", null, now.plus(3, ChronoUnit.MINUTES), now.plus(22, ChronoUnit.MINUTES), now);
        addRuntimeToBatch(batch, "ethereum", "dbg-req-0012", null, null, now);
        addRuntimeToBatch(batch, "litecoin", null, null, null, now);
        batch.execute();
    }

    private static void addRuntimeToBatch(
            PreparedBatch batch, String coin, String activeRequestId,
            Instant cooldownUntil, Instant lockdownUntil, Instant updatedAt) {
        batch.bind("coin", coin)
                .bind("activeReqId", activeRequestId)
                .bind("cooldownUntil", ts(cooldownUntil))
                .bind("lockdownUntil", ts(lockdownUntil))
                .bind("updatedAt", ts(updatedAt))
                .add();
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}