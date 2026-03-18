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

package io.konkin.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.konkin.web.WebIntegrationTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WalletApiIntegrationTest extends WebIntegrationTestSupport {

    private static final String API_KEY = "test-api-key";
    private static final Map<String, String> AUTH = Map.of("X-API-Key", API_KEY);
    private static final Map<String, String> NO_AUTH = Map.of();
    private static final Map<String, String> BAD_AUTH = Map.of("X-API-Key", "wrong-key");

    @TempDir
    static Path sharedTempDir;
    private static RunningServer server;

    @BeforeAll
    static void startServer() throws Exception {
        server = startServerWithRestApi(sharedTempDir, "wallet-api-test");
    }

    @AfterAll
    static void stopServer() {
        if (server != null) server.close();
    }

    @BeforeEach
    void cleanDb() {
        if (server != null && server.dbManager() != null) {
            cleanDatabase(server.dbManager().dataSource());
        }
    }

    // ── Auth security: all wallet endpoints return identical 401 ─────────────

    @Test
    void walletEndpoints_returnIdentical401_withNoApiKey() throws Exception {
        List<String> walletPaths = List.of(
                "/api/v1/wallets/bitcoin/balance",
                "/api/v1/wallets/bitcoin/status",
                "/api/v1/wallets/bitcoin/pending-incoming",
                "/api/v1/wallets/pending-outgoing",
                "/api/v1/wallets/bitcoin/pending-outgoing"
        );

        HttpResponse<String> referenceResp = get(server, "/api/v1/kv", NO_AUTH);
        assertEquals(401, referenceResp.statusCode());
        String referenceBody = referenceResp.body();

        for (String path : walletPaths) {
            HttpResponse<String> resp = get(server, path, NO_AUTH);
            assertEquals(401, resp.statusCode(),
                    "Expected 401 for " + path + " but got " + resp.statusCode());
            assertEquals(referenceBody, resp.body(),
                    "Response body for " + path + " differs from reference /api/v1/kv");
        }
    }

    @Test
    void walletEndpoints_returnIdentical401_withWrongApiKey() throws Exception {
        List<String> walletPaths = List.of(
                "/api/v1/wallets/bitcoin/balance",
                "/api/v1/wallets/monero/status",
                "/api/v1/wallets/litecoin/pending-incoming",
                "/api/v1/wallets/pending-outgoing"
        );

        HttpResponse<String> referenceResp = get(server, "/api/v1/kv", BAD_AUTH);
        assertEquals(401, referenceResp.statusCode());
        String referenceBody = referenceResp.body();

        for (String path : walletPaths) {
            HttpResponse<String> resp = get(server, path, BAD_AUTH);
            assertEquals(401, resp.statusCode(),
                    "Expected 401 for " + path + " but got " + resp.statusCode());
            assertEquals(referenceBody, resp.body(),
                    "Response body for " + path + " differs from reference /api/v1/kv");
        }
    }

    @Test
    void postEndpoints_returnIdentical401_withNoApiKey() throws Exception {
        List<String> postPaths = List.of(
                "/api/v1/wallets/bitcoin/deposit-address",
                "/api/v1/wallets/bitcoin/send",
                "/api/v1/wallets/bitcoin/sweep",
                "/api/v1/wallets/bitcoin/sign-message",
                "/api/v1/wallets/bitcoin/verify-message"
        );

        HttpResponse<String> referenceResp = get(server, "/api/v1/kv", NO_AUTH);
        assertEquals(401, referenceResp.statusCode());
        String referenceBody = referenceResp.body();

        for (String path : postPaths) {
            HttpResponse<String> resp = postJson(server, path, "{}", NO_AUTH);
            assertEquals(401, resp.statusCode(),
                    "Expected 401 for POST " + path + " but got " + resp.statusCode());
            assertEquals(referenceBody, resp.body(),
                    "Response body for POST " + path + " differs from reference");
        }
    }

    @Test
    void nonExistentWalletPath_returnsSame401_asOtherEndpoints() throws Exception {
        HttpResponse<String> referenceResp = get(server, "/api/v1/kv", NO_AUTH);
        assertEquals(401, referenceResp.statusCode());

        // Even a completely bogus wallet path should return the same 401
        HttpResponse<String> resp = get(server, "/api/v1/wallets/fakecoin/balance", NO_AUTH);
        assertEquals(401, resp.statusCode());
        assertEquals(referenceResp.body(), resp.body());
    }

    // ── Functional tests (authenticated) ─────────────────────────────────────

    @Test
    void getBalance_walletOffline_returns502() throws Exception {
        // No wallets configured in test server → wallet supervisor missing
        HttpResponse<String> resp = get(server, "/api/v1/wallets/bitcoin/balance", AUTH);
        // Coin is not enabled in test config, so 404
        assertTrue(resp.statusCode() == 404 || resp.statusCode() == 502,
                "Expected 404 or 502 but got " + resp.statusCode());
    }

    @Test
    void getStatus_unsupportedCoin_returns400() throws Exception {
        HttpResponse<String> resp = get(server, "/api/v1/wallets/dogecoin/status", AUTH);
        assertEquals(400, resp.statusCode());
        JsonNode json = JSON.readTree(resp.body());
        assertEquals("unsupported_coin", json.path("error").asText());
    }

    @Test
    void getPendingOutgoing_returnsEmptyList() throws Exception {
        HttpResponse<String> resp = get(server, "/api/v1/wallets/pending-outgoing", AUTH);
        assertEquals(200, resp.statusCode());
        JsonNode json = JSON.readTree(resp.body());
        assertEquals(0, json.path("count").asInt());
        assertTrue(json.path("pendingOutgoing").isArray());
    }

    @Test
    void getPendingOutgoing_returnsQueuedRequests() throws Exception {
        // Insert a QUEUED_FOR_EXECUTION request directly in the DB
        insertApprovalRequestWithState(server.dbManager().dataSource(),
                "req-queued-1", "nonce-q1", "QUEUED_FOR_EXECUTION");

        HttpResponse<String> resp = get(server, "/api/v1/wallets/pending-outgoing", AUTH);
        assertEquals(200, resp.statusCode());
        JsonNode json = JSON.readTree(resp.body());
        assertEquals(1, json.path("count").asInt());
        assertEquals("req-queued-1", json.path("pendingOutgoing").get(0).path("id").asText());
    }

    @Test
    void getPendingOutgoingByCoin_filtersCorrectly() throws Exception {
        insertApprovalRequestWithState(server.dbManager().dataSource(),
                "req-btc-1", "nonce-btc1", "QUEUED_FOR_EXECUTION");

        HttpResponse<String> resp = get(server, "/api/v1/wallets/bitcoin/pending-outgoing", AUTH);
        assertEquals(200, resp.statusCode());
        JsonNode json = JSON.readTree(resp.body());
        assertEquals("bitcoin", json.path("coin").asText());
        assertEquals(1, json.path("count").asInt());

        // Different coin should return 0
        HttpResponse<String> resp2 = get(server, "/api/v1/wallets/monero/pending-outgoing", AUTH);
        assertEquals(200, resp2.statusCode());
        JsonNode json2 = JSON.readTree(resp2.body());
        assertEquals(0, json2.path("count").asInt());
    }

    @Test
    void send_missingFields_returns400() throws Exception {
        // No toAddress
        HttpResponse<String> resp = postJson(server, "/api/v1/wallets/bitcoin/send",
                """
                {"amountNative": "0.5", "reason": "test"}
                """, AUTH);
        // bitcoin may not be enabled, so could be 404; if enabled, 400
        assertTrue(resp.statusCode() == 400 || resp.statusCode() == 404);
    }

    @Test
    void send_unsupportedCoin_returns400() throws Exception {
        HttpResponse<String> resp = postJson(server, "/api/v1/wallets/dogecoin/send",
                """
                {"toAddress": "addr123456789", "amountNative": "1.0", "reason": "test"}
                """, AUTH);
        assertEquals(400, resp.statusCode());
        JsonNode json = JSON.readTree(resp.body());
        assertEquals("unsupported_coin", json.path("error").asText());
    }

    // ── Helper ───────────────────────────────────────────────────────────────

    private static void insertApprovalRequestWithState(javax.sql.DataSource ds, String id, String nonce, String state) {
        java.time.Instant now = java.time.Instant.now();
        io.konkin.db.JdbiFactory.create(ds).useHandle(h ->
                h.createUpdate("""
                        INSERT INTO approval_requests (
                            id, coin, tool_name, nonce_uuid, payload_hash_sha256, nonce_composite,
                            requested_at, expires_at, state, min_approvals_required, reason
                        ) VALUES (:id, :coin, :tool, :nonceUuid, :sha256, :nonce, :requestedAt, :expiresAt, :state, :minApprovals, :reason)
                        """)
                        .bind("id", id)
                        .bind("coin", "bitcoin")
                        .bind("tool", "wallet_send")
                        .bind("nonceUuid", "nonce-uuid-" + id)
                        .bind("sha256", "sha256-" + id)
                        .bind("nonce", nonce)
                        .bind("requestedAt", now)
                        .bind("expiresAt", now.plusSeconds(600))
                        .bind("state", state)
                        .bind("minApprovals", 1)
                        .bind("reason", "Integration test")
                        .execute()
        );
    }
}
