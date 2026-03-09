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

class RequestChannelApiTest extends WebIntegrationTestSupport {

    private static final String API_KEY = "test-api-key";
    private static final Map<String, String> AUTH = Map.of("X-API-Key", API_KEY);

    @TempDir
    static Path sharedTempDir;
    private static RunningServer server;

    @BeforeAll
    static void startServer() throws Exception {
        server = startServerWithRestApi(sharedTempDir, "request-channel-api-test");
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

    private String requestChannelJson(long id, String requestId, String channelId, String deliveryState) {
        double now = System.currentTimeMillis() / 1000.0;
        return """
                {"id":%d,"requestId":"%s","channelId":"%s","deliveryState":"%s","attemptCount":0,"createdAt":%f}
                """.formatted(id, requestId, channelId, deliveryState, now);
    }

    @Test
    void createAndGetRequestChannel() throws Exception {
        insertApprovalRequest(server.dbManager().dataSource(), "req-rc", "nonce-rc", "PENDING");
        insertApprovalChannel(server.dbManager().dataSource(), "ch-rc", "REST_API");

        HttpResponse<String> createResp = postJson(server, "/api/v1/request-channels", requestChannelJson(0, "req-rc", "ch-rc", "queued"), AUTH);
        assertEquals(201, createResp.statusCode());

        HttpResponse<String> allResp = get(server, "/api/v1/request-channels", AUTH);
        assertEquals(200, allResp.statusCode());
        JsonNode arr = JSON.readTree(allResp.body());
        assertTrue(arr.isArray());
        assertTrue(arr.size() >= 1);

        long rcId = arr.get(0).path("id").asLong();

        HttpResponse<String> oneResp = get(server, "/api/v1/request-channels/" + rcId, AUTH);
        assertEquals(200, oneResp.statusCode());
        JsonNode node = JSON.readTree(oneResp.body());
        assertEquals("req-rc", node.path("requestId").asText());
    }

    @Test
    void updateRequestChannel() throws Exception {
        insertApprovalRequest(server.dbManager().dataSource(), "req-rc-upd", "nonce-rc-upd", "PENDING");
        insertApprovalChannel(server.dbManager().dataSource(), "ch-rc-upd", "REST_API");

        postJson(server, "/api/v1/request-channels", requestChannelJson(0, "req-rc-upd", "ch-rc-upd", "queued"), AUTH);

        HttpResponse<String> allResp = get(server, "/api/v1/request-channels", AUTH);
        JsonNode arr = JSON.readTree(allResp.body());
        long rcId = arr.get(0).path("id").asLong();

        HttpResponse<String> resp = putJson(server, "/api/v1/request-channels/" + rcId,
                requestChannelJson(rcId, "req-rc-upd", "ch-rc-upd", "sent"), AUTH);
        assertEquals(200, resp.statusCode());
    }

    @Test
    void updateWithMismatchedIdRejected() throws Exception {
        HttpResponse<String> resp = putJson(server, "/api/v1/request-channels/1",
                requestChannelJson(999, "req-1", "ch-1", "queued"), AUTH);
        assertEquals(400, resp.statusCode());
        assertTrue(resp.body().contains("ID in path does not match"));
    }

    @Test
    void deleteNonExistentReturns404() throws Exception {
        HttpResponse<String> resp = delete(server, "/api/v1/request-channels/99999", AUTH);
        assertEquals(404, resp.statusCode());
    }

    @Test
    void getOneNonExistentReturns404() throws Exception {
        HttpResponse<String> resp = get(server, "/api/v1/request-channels/99999", AUTH);
        assertEquals(404, resp.statusCode());
    }

    @Test
    void deleteRequestChannel() throws Exception {
        insertApprovalRequest(server.dbManager().dataSource(), "req-rc-del", "nonce-rc-del", "PENDING");
        insertApprovalChannel(server.dbManager().dataSource(), "ch-rc-del", "REST_API");

        postJson(server, "/api/v1/request-channels", requestChannelJson(0, "req-rc-del", "ch-rc-del", "queued"), AUTH);

        HttpResponse<String> allResp = get(server, "/api/v1/request-channels", AUTH);
        JsonNode arr = JSON.readTree(allResp.body());
        assertTrue(arr.size() >= 1, "Expected at least one request-channel row");
        long rcId = arr.get(0).path("id").asLong();

        HttpResponse<String> delResp = delete(server, "/api/v1/request-channels/" + rcId, AUTH);
        assertEquals(204, delResp.statusCode());

        HttpResponse<String> getResp = get(server, "/api/v1/request-channels/" + rcId, AUTH);
        assertEquals(404, getResp.statusCode());
    }
}
