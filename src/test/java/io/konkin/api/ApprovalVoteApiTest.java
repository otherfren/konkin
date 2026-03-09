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

class ApprovalVoteApiTest extends WebIntegrationTestSupport {

    private static final String API_KEY = "test-api-key";
    private static final Map<String, String> AUTH = Map.of("X-API-Key", API_KEY);

    @TempDir
    static Path sharedTempDir;
    private static RunningServer server;

    @BeforeAll
    static void startServer() throws Exception {
        server = startServerWithRestApi(sharedTempDir, "approval-vote-api-test");
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

    @Test
    void createVoteIsForbidden() throws Exception {
        String json = """
                {
                    "requestId": "req-1",
                    "channelId": "ch-1",
                    "decision": "approve",
                    "decisionReason": "test",
                    "decidedBy": "tester"
                }
                """;
        HttpResponse<String> resp = postJson(server, "/api/v1/votes", json, AUTH);
        assertEquals(403, resp.statusCode());
        assertTrue(resp.body().contains("not allowed"));
    }

    @Test
    void updateVoteIsForbidden() throws Exception {
        String json = """
                {
                    "id": 1,
                    "requestId": "req-1",
                    "channelId": "ch-1",
                    "decision": "deny",
                    "decisionReason": "test",
                    "decidedBy": "tester"
                }
                """;
        HttpResponse<String> resp = putJson(server, "/api/v1/votes/1", json, AUTH);
        assertEquals(403, resp.statusCode());
        assertTrue(resp.body().contains("not allowed"));
    }

    @Test
    void getAllVotes() throws Exception {
        HttpResponse<String> resp = get(server, "/api/v1/votes", AUTH);
        assertEquals(200, resp.statusCode());
        JsonNode arr = JSON.readTree(resp.body());
        assertTrue(arr.isArray());
    }

    @Test
    void getOneVoteNotFound() throws Exception {
        HttpResponse<String> resp = get(server, "/api/v1/votes/99999", AUTH);
        assertEquals(404, resp.statusCode());
    }

    @Test
    void deleteNonExistentVoteReturns404() throws Exception {
        HttpResponse<String> resp = delete(server, "/api/v1/votes/99999", AUTH);
        assertEquals(404, resp.statusCode());
    }

    @Test
    void getVotesForRequest() throws Exception {
        HttpResponse<String> resp = get(server, "/api/v1/requests/nonexistent/votes", AUTH);
        assertEquals(200, resp.statusCode());
        JsonNode arr = JSON.readTree(resp.body());
        assertTrue(arr.isArray());
        assertEquals(0, arr.size());
    }

    @Test
    void voteLifecycleViaDirectInsert() throws Exception {
        // Insert a request and channel first, then a vote via DB (lowercase decision), then test GET/DELETE via API
        insertApprovalRequest(server.dbManager().dataSource(), "req-vote-lc", "nonce-vote-lc", "PENDING");
        insertApprovalChannel(server.dbManager().dataSource(), "ch-vote-lc", "REST_API");
        insertApprovalVote(server.dbManager().dataSource(), "req-vote-lc", "ch-vote-lc", "approve");

        // Get all votes — should have the one we inserted
        HttpResponse<String> allResp = get(server, "/api/v1/votes", AUTH);
        assertEquals(200, allResp.statusCode());
        JsonNode arr = JSON.readTree(allResp.body());
        assertTrue(arr.size() >= 1);

        long voteId = arr.get(0).path("id").asLong();

        // Get single vote
        HttpResponse<String> oneResp = get(server, "/api/v1/votes/" + voteId, AUTH);
        assertEquals(200, oneResp.statusCode());
        JsonNode vote = JSON.readTree(oneResp.body());
        assertEquals("req-vote-lc", vote.path("requestId").asText());
        assertEquals("approve", vote.path("decision").asText());

        // Get votes for request
        HttpResponse<String> forReqResp = get(server, "/api/v1/requests/req-vote-lc/votes", AUTH);
        assertEquals(200, forReqResp.statusCode());
        JsonNode forReqArr = JSON.readTree(forReqResp.body());
        assertTrue(forReqArr.size() >= 1);

        // Delete the vote
        HttpResponse<String> delResp = delete(server, "/api/v1/votes/" + voteId, AUTH);
        assertEquals(204, delResp.statusCode());

        // Confirm deleted
        HttpResponse<String> afterDel = get(server, "/api/v1/votes/" + voteId, AUTH);
        assertEquals(404, afterDel.statusCode());
    }
}
