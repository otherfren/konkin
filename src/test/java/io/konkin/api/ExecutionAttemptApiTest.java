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

class ExecutionAttemptApiTest extends WebIntegrationTestSupport {

    private static final String API_KEY = "test-api-key";
    private static final Map<String, String> AUTH = Map.of("X-API-Key", API_KEY);

    @TempDir
    static Path sharedTempDir;
    private static RunningServer server;

    @BeforeAll
    static void startServer() throws Exception {
        server = startServerWithRestApi(sharedTempDir, "execution-attempt-api-test");
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

    private void insertExecutionAttempt(DataSource ds, String requestId, int attemptNo, String result) {
        insertApprovalRequest(ds, requestId, "nonce-" + requestId, "APPROVED");
        Instant now = Instant.now();
        JdbiFactory.create(ds).useHandle(h ->
                h.createUpdate("""
                        INSERT INTO approval_execution_attempts (request_id, attempt_no, started_at, finished_at, result)
                        VALUES (:reqId, :attemptNo, :startedAt, :finishedAt, :result)
                        """)
                        .bind("reqId", requestId)
                        .bind("attemptNo", attemptNo)
                        .bind("startedAt", now)
                        .bind("finishedAt", now.plusSeconds(5))
                        .bind("result", result)
                        .execute()
        );
    }

    @Test
    void createExecutionAttemptIsForbidden() throws Exception {
        String json = """
                {
                    "requestId": "req-1",
                    "attemptNo": 1,
                    "result": "success"
                }
                """;
        HttpResponse<String> resp = postJson(server, "/api/v1/execution-attempts", json, AUTH);
        assertEquals(403, resp.statusCode());
        assertTrue(resp.body().contains("not allowed"));
    }

    @Test
    void getAllExecutionAttempts() throws Exception {
        HttpResponse<String> resp = get(server, "/api/v1/execution-attempts", AUTH);
        assertEquals(200, resp.statusCode());
        JsonNode arr = JSON.readTree(resp.body());
        assertTrue(arr.isArray());
    }

    @Test
    void getOneNotFound() throws Exception {
        HttpResponse<String> resp = get(server, "/api/v1/execution-attempts/99999", AUTH);
        assertEquals(404, resp.statusCode());
    }

    @Test
    void deleteNonExistentReturns404() throws Exception {
        HttpResponse<String> resp = delete(server, "/api/v1/execution-attempts/99999", AUTH);
        assertEquals(404, resp.statusCode());
    }

    @Test
    void executionAttemptLifecycleViaDirectInsert() throws Exception {
        DataSource ds = server.dbManager().dataSource();
        insertExecutionAttempt(ds, "req-ea-lc", 1, "success");

        HttpResponse<String> allResp = get(server, "/api/v1/execution-attempts", AUTH);
        assertEquals(200, allResp.statusCode());
        JsonNode arr = JSON.readTree(allResp.body());
        assertTrue(arr.size() >= 1);

        long attemptId = arr.get(0).path("id").asLong();

        HttpResponse<String> oneResp = get(server, "/api/v1/execution-attempts/" + attemptId, AUTH);
        assertEquals(200, oneResp.statusCode());
        JsonNode node = JSON.readTree(oneResp.body());
        assertEquals("req-ea-lc", node.path("requestId").asText());
        assertEquals("success", node.path("result").asText());

        HttpResponse<String> delResp = delete(server, "/api/v1/execution-attempts/" + attemptId, AUTH);
        assertEquals(204, delResp.statusCode());

        HttpResponse<String> afterDel = get(server, "/api/v1/execution-attempts/" + attemptId, AUTH);
        assertEquals(404, afterDel.statusCode());
    }
}
