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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ApprovalRequestApiTest extends WebIntegrationTestSupport {

    private static final String API_KEY = "test-api-key";
    private static final Map<String, String> AUTH = Map.of("X-API-Key", API_KEY);

    @TempDir
    static Path sharedTempDir;
    private static RunningServer server;

    @BeforeAll
    static void startServer() throws Exception {
        server = startServerWithRestApi(sharedTempDir, "approval-request-api-test");
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

    private static double epochNow() {
        return System.currentTimeMillis() / 1000.0;
    }

    private String requestJson(String id, String state, int approvalsGranted, int approvalsDenied) {
        double now = epochNow();
        return """
                {"id":"%s","coin":"bitcoin","toolName":"bitcoin_send","nonceUuid":"nonce-%s",\
                "payloadHashSha256":"sha256-%s","nonceComposite":"composite-%s","reason":"test request %s",\
                "requestedAt":%f,"expiresAt":%f,"state":"%s","minApprovalsRequired":1,\
                "approvalsGranted":%d,"approvalsDenied":%d,"createdAt":%f,"updatedAt":%f}
                """.formatted(id, id, id, id, id, now, now + 600, state, approvalsGranted, approvalsDenied, now, now);
    }

    @Test
    void createAndGetRequest() throws Exception {
        HttpResponse<String> createResp = postJson(server, "/api/v1/requests", requestJson("req-1", "PENDING", 0, 0), AUTH);
        assertEquals(201, createResp.statusCode());

        HttpResponse<String> getResp = get(server, "/api/v1/requests/req-1", AUTH);
        assertEquals(200, getResp.statusCode());
        JsonNode node = JSON.readTree(getResp.body());
        assertEquals("req-1", node.path("id").asText());
        assertEquals("bitcoin", node.path("coin").asText());
    }

    @Test
    void getAllRequests() throws Exception {
        // getAll filters out PENDING/QUEUED — insert non-pending requests via DB
        insertApprovalRequest(server.dbManager().dataSource(), "req-a", "nonce-a", "APPROVED");
        insertApprovalRequest(server.dbManager().dataSource(), "req-b", "nonce-b", "DENIED");

        HttpResponse<String> resp = get(server, "/api/v1/requests", AUTH);
        assertEquals(200, resp.statusCode());
        JsonNode node = JSON.readTree(resp.body());
        assertTrue(node.has("rows"));
        assertTrue(node.path("rows").size() >= 2);
    }

    @Test
    void createWithQueuedStateAllowed() throws Exception {
        HttpResponse<String> resp = postJson(server, "/api/v1/requests", requestJson("req-queued", "QUEUED", 0, 0), AUTH);
        assertEquals(201, resp.statusCode());
    }

    @Test
    void createWithApprovedStateRejected() throws Exception {
        HttpResponse<String> resp = postJson(server, "/api/v1/requests", requestJson("req-bad", "APPROVED", 0, 0), AUTH);
        assertEquals(400, resp.statusCode());
        assertTrue(resp.body().contains("Only PENDING or QUEUED allowed"));
    }

    @Test
    void createWithNonZeroApprovalsRejected() throws Exception {
        HttpResponse<String> resp = postJson(server, "/api/v1/requests", requestJson("req-bad2", "PENDING", 1, 0), AUTH);
        assertEquals(400, resp.statusCode());
        assertTrue(resp.body().contains("non-zero"));
    }

    @Test
    void createWithNonZeroDenialsRejected() throws Exception {
        HttpResponse<String> resp = postJson(server, "/api/v1/requests", requestJson("req-bad3", "PENDING", 0, 1), AUTH);
        assertEquals(400, resp.statusCode());
        assertTrue(resp.body().contains("non-zero"));
    }

    @Test
    void updateRequest() throws Exception {
        postJson(server, "/api/v1/requests", requestJson("req-upd", "PENDING", 0, 0), AUTH);

        double now = epochNow();
        String updateJson = """
                {"id":"req-upd","coin":"bitcoin","toolName":"bitcoin_send","nonceUuid":"nonce-req-upd",\
                "payloadHashSha256":"sha256-req-upd","nonceComposite":"composite-req-upd","reason":"updated reason",\
                "requestedAt":%f,"expiresAt":%f,"state":"PENDING","minApprovalsRequired":1,\
                "approvalsGranted":0,"approvalsDenied":0,"createdAt":%f,"updatedAt":%f}
                """.formatted(now, now + 600, now, now);

        HttpResponse<String> resp = putJson(server, "/api/v1/requests/req-upd", updateJson, AUTH);
        assertEquals(200, resp.statusCode());
    }

    @Test
    void updateWithMismatchedIdRejected() throws Exception {
        postJson(server, "/api/v1/requests", requestJson("req-mismatch", "PENDING", 0, 0), AUTH);

        HttpResponse<String> resp = putJson(server, "/api/v1/requests/req-mismatch", requestJson("different-id", "PENDING", 0, 0), AUTH);
        assertEquals(400, resp.statusCode());
        assertTrue(resp.body().contains("ID in path does not match"));
    }

    @Test
    void updateStateChangeRejected() throws Exception {
        postJson(server, "/api/v1/requests", requestJson("req-state", "PENDING", 0, 0), AUTH);

        HttpResponse<String> resp = putJson(server, "/api/v1/requests/req-state", requestJson("req-state", "APPROVED", 0, 0), AUTH);
        assertEquals(400, resp.statusCode());
        assertTrue(resp.body().contains("Cannot change request state"));
    }

    @Test
    void updateVoteCountChangeRejected() throws Exception {
        postJson(server, "/api/v1/requests", requestJson("req-vote", "PENDING", 0, 0), AUTH);

        HttpResponse<String> resp = putJson(server, "/api/v1/requests/req-vote", requestJson("req-vote", "PENDING", 5, 0), AUTH);
        assertEquals(400, resp.statusCode());
        assertTrue(resp.body().contains("approval/denial counts"));
    }

    @Test
    void updateNonExistentReturns404() throws Exception {
        HttpResponse<String> resp = putJson(server, "/api/v1/requests/nonexistent", requestJson("nonexistent", "PENDING", 0, 0), AUTH);
        assertEquals(404, resp.statusCode());
    }

    @Test
    void deleteRequest() throws Exception {
        postJson(server, "/api/v1/requests", requestJson("req-del", "PENDING", 0, 0), AUTH);

        HttpResponse<String> delResp = delete(server, "/api/v1/requests/req-del", AUTH);
        assertEquals(204, delResp.statusCode());

        HttpResponse<String> getResp = get(server, "/api/v1/requests/req-del", AUTH);
        assertEquals(404, getResp.statusCode());
    }

    @Test
    void deleteNonExistentReturns404() throws Exception {
        HttpResponse<String> resp = delete(server, "/api/v1/requests/no-such-req", AUTH);
        assertEquals(404, resp.statusCode());
    }

    @Test
    void getNonExistentReturns404() throws Exception {
        HttpResponse<String> resp = get(server, "/api/v1/requests/no-such-req", AUTH);
        assertEquals(404, resp.statusCode());
    }

    @Test
    void getFilterOptions() throws Exception {
        HttpResponse<String> resp = get(server, "/api/v1/requests/filter-options", AUTH);
        assertEquals(200, resp.statusCode());
    }

    @Test
    void getDependencies() throws Exception {
        postJson(server, "/api/v1/requests", requestJson("req-dep", "PENDING", 0, 0), AUTH);

        HttpResponse<String> resp = get(server, "/api/v1/requests/req-dep/dependencies", AUTH);
        assertEquals(200, resp.statusCode());
    }

    @Test
    void getDependenciesNonExistentReturnsEmptyDeps() throws Exception {
        HttpResponse<String> resp = get(server, "/api/v1/requests/nonexistent/dependencies", AUTH);
        // loadRequestDependencies always returns an entry for each requested ID, even if no data exists
        assertEquals(200, resp.statusCode());
    }

    @Test
    void paginationParameters() throws Exception {
        // getAll filters out PENDING/QUEUED — insert non-pending requests via DB
        for (int i = 0; i < 5; i++) {
            insertApprovalRequest(server.dbManager().dataSource(), "req-page-" + i, "nonce-page-" + i, "APPROVED");
        }

        HttpResponse<String> resp = get(server, "/api/v1/requests?page=1&pageSize=2", AUTH);
        assertEquals(200, resp.statusCode());
        JsonNode node = JSON.readTree(resp.body());
        assertTrue(node.has("rows"));
        assertEquals(2, node.path("rows").size());
    }
}
