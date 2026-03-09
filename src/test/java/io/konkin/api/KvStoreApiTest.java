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

class KvStoreApiTest extends WebIntegrationTestSupport {

    private static final String API_KEY = "test-api-key";
    private static final Map<String, String> AUTH = Map.of("X-API-Key", API_KEY);

    @TempDir
    static Path sharedTempDir;
    private static RunningServer server;

    @BeforeAll
    static void startServer() throws Exception {
        server = startServerWithRestApi(sharedTempDir, "kv-store-api-test");
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
    void putAndGetKey() throws Exception {
        HttpResponse<String> putResp = putBody(server, "/api/v1/kv/my-key", "my-value", AUTH);
        assertEquals(204, putResp.statusCode());

        HttpResponse<String> getResp = get(server, "/api/v1/kv/my-key", AUTH);
        assertEquals(200, getResp.statusCode());
        assertTrue(getResp.body().contains("my-value"));
    }

    @Test
    void getAll() throws Exception {
        putBody(server, "/api/v1/kv/key-a", "val-a", AUTH);
        putBody(server, "/api/v1/kv/key-b", "val-b", AUTH);

        HttpResponse<String> resp = get(server, "/api/v1/kv", AUTH);
        assertEquals(200, resp.statusCode());
        JsonNode obj = JSON.readTree(resp.body());
        assertTrue(obj.isObject());
        assertTrue(obj.size() >= 2);
    }

    @Test
    void deleteKey() throws Exception {
        putBody(server, "/api/v1/kv/del-key", "del-value", AUTH);

        HttpResponse<String> delResp = delete(server, "/api/v1/kv/del-key", AUTH);
        assertEquals(204, delResp.statusCode());

        HttpResponse<String> getResp = get(server, "/api/v1/kv/del-key", AUTH);
        assertEquals(404, getResp.statusCode());
    }

    @Test
    void deleteNonExistentKeyReturns404() throws Exception {
        HttpResponse<String> resp = delete(server, "/api/v1/kv/no-such-key", AUTH);
        assertEquals(404, resp.statusCode());
    }

    @Test
    void getNonExistentKeyReturns404() throws Exception {
        HttpResponse<String> resp = get(server, "/api/v1/kv/no-such-key", AUTH);
        assertEquals(404, resp.statusCode());
    }

    @Test
    void putOverwritesExistingKey() throws Exception {
        putBody(server, "/api/v1/kv/overwrite", "old", AUTH);
        putBody(server, "/api/v1/kv/overwrite", "new", AUTH);

        HttpResponse<String> resp = get(server, "/api/v1/kv/overwrite", AUTH);
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("new"));
    }

    @Test
    void unauthorizedWithoutApiKey() throws Exception {
        HttpResponse<String> resp = get(server, "/api/v1/kv", Map.of());
        assertEquals(401, resp.statusCode());
    }

    @Test
    void unauthorizedWithWrongApiKey() throws Exception {
        HttpResponse<String> resp = get(server, "/api/v1/kv", Map.of("X-API-Key", "wrong-key"));
        assertEquals(401, resp.statusCode());
    }

    @Test
    void healthEndpointDoesNotRequireAuth() throws Exception {
        HttpResponse<String> resp = get(server, "/api/v1/health", Map.of());
        assertEquals(200, resp.statusCode());
    }
}
