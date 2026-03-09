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

class ApprovalChannelApiTest extends WebIntegrationTestSupport {

    private static final String API_KEY = "test-api-key";
    private static final Map<String, String> AUTH = Map.of("X-API-Key", API_KEY);

    @TempDir
    static Path sharedTempDir;
    private static RunningServer server;

    @BeforeAll
    static void startServer() throws Exception {
        server = startServerWithRestApi(sharedTempDir, "approval-channel-api-test");
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

    private String channelJson(String id) {
        double now = System.currentTimeMillis() / 1000.0;
        return """
                {"id":"%s","channelType":"REST_API","displayName":"Test Channel %s",\
                "enabled":true,"configFingerprint":"fingerprint-%s","createdAt":%f}
                """.formatted(id, id, id, now);
    }

    @Test
    void createAndGetChannel() throws Exception {
        HttpResponse<String> createResp = postJson(server, "/api/v1/channels", channelJson("ch-1"), AUTH);
        assertEquals(201, createResp.statusCode());

        HttpResponse<String> getResp = get(server, "/api/v1/channels/ch-1", AUTH);
        assertEquals(200, getResp.statusCode());
        JsonNode node = JSON.readTree(getResp.body());
        assertEquals("ch-1", node.path("id").asText());
        assertEquals("REST_API", node.path("channelType").asText());
    }

    @Test
    void getAllChannels() throws Exception {
        postJson(server, "/api/v1/channels", channelJson("ch-a"), AUTH);
        postJson(server, "/api/v1/channels", channelJson("ch-b"), AUTH);

        HttpResponse<String> resp = get(server, "/api/v1/channels", AUTH);
        assertEquals(200, resp.statusCode());
        JsonNode arr = JSON.readTree(resp.body());
        assertTrue(arr.isArray());
        assertTrue(arr.size() >= 2);
    }

    @Test
    void updateChannel() throws Exception {
        postJson(server, "/api/v1/channels", channelJson("ch-upd"), AUTH);

        double now = System.currentTimeMillis() / 1000.0;
        String updateJson = """
                {"id":"ch-upd","channelType":"REST_API","displayName":"Updated Name",\
                "enabled":false,"configFingerprint":"new-fingerprint","createdAt":%f}
                """.formatted(now);
        HttpResponse<String> resp = putJson(server, "/api/v1/channels/ch-upd", updateJson, AUTH);
        assertEquals(200, resp.statusCode());

        HttpResponse<String> getResp = get(server, "/api/v1/channels/ch-upd", AUTH);
        JsonNode node = JSON.readTree(getResp.body());
        assertEquals("Updated Name", node.path("displayName").asText());
    }

    @Test
    void updateWithMismatchedIdRejected() throws Exception {
        postJson(server, "/api/v1/channels", channelJson("ch-mis"), AUTH);

        HttpResponse<String> resp = putJson(server, "/api/v1/channels/ch-mis", channelJson("different-id"), AUTH);
        assertEquals(400, resp.statusCode());
        assertTrue(resp.body().contains("ID in path does not match"));
    }

    @Test
    void deleteChannel() throws Exception {
        postJson(server, "/api/v1/channels", channelJson("ch-del"), AUTH);

        HttpResponse<String> delResp = delete(server, "/api/v1/channels/ch-del", AUTH);
        assertEquals(204, delResp.statusCode());

        HttpResponse<String> getResp = get(server, "/api/v1/channels/ch-del", AUTH);
        assertEquals(404, getResp.statusCode());
    }

    @Test
    void deleteNonExistentReturns404() throws Exception {
        HttpResponse<String> resp = delete(server, "/api/v1/channels/no-such-ch", AUTH);
        assertEquals(404, resp.statusCode());
    }

    @Test
    void getNonExistentReturns404() throws Exception {
        HttpResponse<String> resp = get(server, "/api/v1/channels/no-such-ch", AUTH);
        assertEquals(404, resp.statusCode());
    }
}
