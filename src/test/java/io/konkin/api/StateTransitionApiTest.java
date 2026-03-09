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
import io.konkin.db.JdbiFactory;
import io.konkin.web.WebIntegrationTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StateTransitionApiTest extends WebIntegrationTestSupport {

    private static final String API_KEY = "test-api-key";
    private static final Map<String, String> AUTH = Map.of("X-API-Key", API_KEY);

    @TempDir
    static Path sharedTempDir;
    private static RunningServer server;

    @BeforeAll
    static void startServer() throws Exception {
        server = startServerWithRestApi(sharedTempDir, "state-transition-api-test");
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

    private void insertStateTransition(DataSource ds, String requestId, String fromState, String toState) {
        insertApprovalRequest(ds, requestId, "nonce-" + requestId, fromState);
        JdbiFactory.create(ds).useHandle(h ->
                h.createUpdate("""
                        INSERT INTO approval_state_transitions (request_id, from_state, to_state, actor_type, actor_id, reason_code)
                        VALUES (:reqId, :from, :to, 'SYSTEM', 'test', 'TEST')
                        """)
                        .bind("reqId", requestId)
                        .bind("from", fromState)
                        .bind("to", toState)
                        .execute()
        );
    }

    @Test
    void createStateTransitionIsForbidden() throws Exception {
        String json = """
                {
                    "requestId": "req-1",
                    "fromState": "PENDING",
                    "toState": "APPROVED",
                    "actorType": "SYSTEM",
                    "actorId": "test"
                }
                """;
        HttpResponse<String> resp = postJson(server, "/api/v1/state-transitions", json, AUTH);
        assertEquals(403, resp.statusCode());
        assertTrue(resp.body().contains("not allowed"));
    }

    @Test
    void getAllStateTransitions() throws Exception {
        HttpResponse<String> resp = get(server, "/api/v1/state-transitions", AUTH);
        assertEquals(200, resp.statusCode());
        JsonNode arr = JSON.readTree(resp.body());
        assertTrue(arr.isArray());
    }

    @Test
    void getOneNotFound() throws Exception {
        HttpResponse<String> resp = get(server, "/api/v1/state-transitions/99999", AUTH);
        assertEquals(404, resp.statusCode());
    }

    @Test
    void deleteNonExistentReturns404() throws Exception {
        HttpResponse<String> resp = delete(server, "/api/v1/state-transitions/99999", AUTH);
        assertEquals(404, resp.statusCode());
    }

    @Test
    void stateTransitionLifecycleViaDirectInsert() throws Exception {
        DataSource ds = server.dbManager().dataSource();
        insertStateTransition(ds, "req-st-lc", "PENDING", "APPROVED");

        HttpResponse<String> allResp = get(server, "/api/v1/state-transitions", AUTH);
        assertEquals(200, allResp.statusCode());
        JsonNode arr = JSON.readTree(allResp.body());
        assertTrue(arr.size() >= 1);

        long transitionId = arr.get(0).path("id").asLong();

        HttpResponse<String> oneResp = get(server, "/api/v1/state-transitions/" + transitionId, AUTH);
        assertEquals(200, oneResp.statusCode());
        JsonNode node = JSON.readTree(oneResp.body());
        assertEquals("req-st-lc", node.path("requestId").asText());
        assertEquals("PENDING", node.path("fromState").asText());
        assertEquals("APPROVED", node.path("toState").asText());

        HttpResponse<String> delResp = delete(server, "/api/v1/state-transitions/" + transitionId, AUTH);
        assertEquals(204, delResp.statusCode());

        HttpResponse<String> afterDel = get(server, "/api/v1/state-transitions/" + transitionId, AUTH);
        assertEquals(404, afterDel.statusCode());
    }
}
