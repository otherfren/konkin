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

class CoinRuntimeApiTest extends WebIntegrationTestSupport {

    private static final String API_KEY = "test-api-key";
    private static final Map<String, String> AUTH = Map.of("X-API-Key", API_KEY);

    @TempDir
    static Path sharedTempDir;
    private static RunningServer server;

    @BeforeAll
    static void startServer() throws Exception {
        server = startServerWithRestApi(sharedTempDir, "coin-runtime-api-test");
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

    private String coinRuntimeJson(String coin, String activeRequestId) {
        double now = System.currentTimeMillis() / 1000.0;
        return """
                {"coin":"%s","activeRequestId":%s,"cooldownUntil":null,"lockdownUntil":null,"updatedAt":%f}
                """.formatted(coin, activeRequestId == null ? "null" : "\"" + activeRequestId + "\"", now);
    }

    @Test
    void createAndGetCoinRuntime() throws Exception {
        HttpResponse<String> createResp = postJson(server, "/api/v1/coin-runtimes", coinRuntimeJson("bitcoin", null), AUTH);
        assertEquals(201, createResp.statusCode());

        HttpResponse<String> getResp = get(server, "/api/v1/coin-runtimes/bitcoin", AUTH);
        assertEquals(200, getResp.statusCode());
        JsonNode node = JSON.readTree(getResp.body());
        assertEquals("bitcoin", node.path("coin").asText());
    }

    @Test
    void getAllCoinRuntimes() throws Exception {
        postJson(server, "/api/v1/coin-runtimes", coinRuntimeJson("bitcoin", null), AUTH);
        postJson(server, "/api/v1/coin-runtimes", coinRuntimeJson("monero", null), AUTH);

        HttpResponse<String> resp = get(server, "/api/v1/coin-runtimes", AUTH);
        assertEquals(200, resp.statusCode());
        JsonNode arr = JSON.readTree(resp.body());
        assertTrue(arr.isArray());
        assertTrue(arr.size() >= 2);
    }

    @Test
    void updateCoinRuntime() throws Exception {
        postJson(server, "/api/v1/coin-runtimes", coinRuntimeJson("bitcoin", null), AUTH);

        // Create a request so the FK constraint is satisfied
        insertApprovalRequest(server.dbManager().dataSource(), "req-123", "nonce-123", "PENDING");

        HttpResponse<String> resp = putJson(server, "/api/v1/coin-runtimes/bitcoin", coinRuntimeJson("bitcoin", "req-123"), AUTH);
        assertEquals(200, resp.statusCode());

        HttpResponse<String> getResp = get(server, "/api/v1/coin-runtimes/bitcoin", AUTH);
        JsonNode node = JSON.readTree(getResp.body());
        assertEquals("req-123", node.path("activeRequestId").asText());
    }

    @Test
    void updateWithMismatchedCoinRejected() throws Exception {
        postJson(server, "/api/v1/coin-runtimes", coinRuntimeJson("bitcoin", null), AUTH);

        HttpResponse<String> resp = putJson(server, "/api/v1/coin-runtimes/bitcoin", coinRuntimeJson("monero", null), AUTH);
        assertEquals(400, resp.statusCode());
        assertTrue(resp.body().contains("Coin in path does not match"));
    }

    @Test
    void deleteCoinRuntime() throws Exception {
        postJson(server, "/api/v1/coin-runtimes", coinRuntimeJson("bitcoin", null), AUTH);

        HttpResponse<String> delResp = delete(server, "/api/v1/coin-runtimes/bitcoin", AUTH);
        assertEquals(204, delResp.statusCode());

        HttpResponse<String> getResp = get(server, "/api/v1/coin-runtimes/bitcoin", AUTH);
        assertEquals(404, getResp.statusCode());
    }

    @Test
    void deleteNonExistentReturns404() throws Exception {
        HttpResponse<String> resp = delete(server, "/api/v1/coin-runtimes/no-such-coin", AUTH);
        assertEquals(404, resp.statusCode());
    }

    @Test
    void getNonExistentReturns404() throws Exception {
        HttpResponse<String> resp = get(server, "/api/v1/coin-runtimes/no-such-coin", AUTH);
        assertEquals(404, resp.statusCode());
    }
}
