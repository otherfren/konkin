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

package io.konkin.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.konkin.TestConfigBuilder;
import io.konkin.TestDatabaseManager;
import io.konkin.config.KonkinConfig;
import io.konkin.db.DatabaseManager;
import io.konkin.db.JdbiFactory;
import io.konkin.web.KonkinWebServer;
import io.konkin.web.WebIntegrationTestSupport;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceRequest;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentEndpointIntegrationTest extends WebIntegrationTestSupport {

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    @TempDir
    static Path sharedTempDir;
    private static AgentRunningServer sharedSecondaryServer;

    @BeforeAll
    static void startSharedServer() throws Exception {
        sharedSecondaryServer = startSecondaryAgentServer(sharedTempDir, "agent-alpha");
    }

    @AfterAll
    static void stopSharedServer() {
        if (sharedSecondaryServer != null) sharedSecondaryServer.close();
    }

    @BeforeEach
    void cleanDb() {
        if (sharedSecondaryServer != null && sharedSecondaryServer.dbManager() != null) {
            cleanDatabase(sharedSecondaryServer.dbManager().dataSource());
        }
    }

    // --- OAuth Tests (HTTP-level, handled by auth filter) ---

    @Test
    void oauthTokenWithCorrectCredentialsReturnsAccessToken() throws Exception {
        AgentRunningServer server = sharedSecondaryServer;
        HttpResponse<String> response = postForm(server.agentBaseUri(), "/oauth/token", oauthFormBody(server), Map.of());

        assertEquals(200, response.statusCode());
        JsonNode json = JSON.readTree(response.body());
        assertFalse(json.path("access_token").asText().isBlank());
        assertEquals("Bearer", json.path("token_type").asText());
        assertEquals(0, json.path("expires_in").asInt());
    }

    @Test
    void oauthTokenWithWrongCredentialsReturnsUnauthorized() throws Exception {
        AgentRunningServer server = sharedSecondaryServer;
        String body = "grant_type=client_credentials&client_id="
                + urlEncode(server.clientId())
                + "&client_secret="
                + urlEncode("wrong-secret");

        HttpResponse<String> response = postForm(server.agentBaseUri(), "/oauth/token", body, Map.of());

        assertEquals(401, response.statusCode());
    }

    @Test
    void oauthTokenAfterTooManyFailedAttemptsReturnsRateLimited() throws Exception {
        try (AgentRunningServer server = startSecondaryAgentServer("agent-alpha")) {
            String invalidBody = "grant_type=client_credentials&client_id="
                    + urlEncode(server.clientId())
                    + "&client_secret="
                    + urlEncode("wrong-secret");

            for (int i = 0; i < 5; i++) {
                HttpResponse<String> failed = postForm(server.agentBaseUri(), "/oauth/token", invalidBody, Map.of());
                assertEquals(401, failed.statusCode());
            }

            HttpResponse<String> rateLimited = postForm(server.agentBaseUri(), "/oauth/token", oauthFormBody(server), Map.of());
            assertEquals(429, rateLimited.statusCode());
            JsonNode json = JSON.readTree(rateLimited.body());
            assertEquals("rate_limited", json.path("error").asText());
            assertEquals("too_many_failed_attempts", json.path("error_description").asText());
        }
    }

    @Test
    void unauthenticatedMcpRequestReturnsUnauthorized() throws Exception {
        // GET to /sse without bearer token should return 401
        HttpResponse<String> response = get(sharedSecondaryServer.agentBaseUri(), "/sse", Map.of());
        assertEquals(401, response.statusCode());
    }

    // --- MCP Health Resource Tests ---

    @Test
    void healthResourceReturnsAgentInfo() throws Exception {
        AgentRunningServer server = sharedSecondaryServer;
        String token = issueAccessToken(server);
        try (McpSyncClient client = createMcpClient(server.agentBaseUri(), token)) {
            client.initialize();

            ReadResourceResult result = client.readResource(new ReadResourceRequest("konkin://health"));
            String text = ((TextResourceContents) result.contents().getFirst()).text();
            JsonNode json = JSON_MAPPER.readTree(text);
            assertEquals("healthy", json.path("status").asText());
            assertEquals("agent-alpha", json.path("agent").asText());
            assertEquals("auth", json.path("type").asText());
        }
    }

    // --- Auth Agent Vote Tool Tests ---

    @Test
    void voteWithValidBearerTokenReturnsAcceptedAndPersistsVote() throws Exception {
        AgentRunningServer server = sharedSecondaryServer;
        DataSource dataSource = server.dbManager().dataSource();
        insertAgentApprovalRequest(dataSource, "req-123", "bitcoin", "QUEUED", 1, "bc1qvoteaccept", "0.10000000");

        String token = issueAccessToken(server);
        try (McpSyncClient client = createMcpClient(server.agentBaseUri(), token)) {
            client.initialize();

            CallToolResult result = client.callTool(new CallToolRequest("vote_on_approval",
                    Map.of("requestId", "req-123", "decision", "approve", "reason", "within policy")));

            assertFalse(result.isError());
            JsonNode json = parseToolResult(result);
            assertEquals("accepted", json.path("status").asText());
            assertEquals("req-123", json.path("requestId").asText());
            assertEquals("approve", json.path("decision").asText());

            assertEquals(1, countVotesForRequest(dataSource, "req-123"));
            assertEquals("APPROVED", loadRequestState(dataSource, "req-123"));
        }
    }

    @Test
    void voteForResolvedRequestReturnsError() throws Exception {
        AgentRunningServer server = sharedSecondaryServer;
        DataSource dataSource = server.dbManager().dataSource();
        insertAgentApprovalRequest(dataSource, "req-resolved", "bitcoin", "COMPLETED", 1, "bc1qresolved", "0.01000000");

        String token = issueAccessToken(server);
        try (McpSyncClient client = createMcpClient(server.agentBaseUri(), token)) {
            client.initialize();

            CallToolResult result = client.callTool(new CallToolRequest("vote_on_approval",
                    Map.of("requestId", "req-resolved", "decision", "approve")));

            assertTrue(result.isError());
            JsonNode json = parseToolResult(result);
            assertEquals("request_not_found_or_resolved", json.path("error").asText());
        }
    }

    @Test
    void voteForUnassignedCoinReturnsError() throws Exception {
        AgentRunningServer server = sharedSecondaryServer;
        DataSource dataSource = server.dbManager().dataSource();
        insertAgentApprovalRequest(dataSource, "req-unassigned", "monero", "PENDING", 1, "44Affq5kSiGBoZ...", "0.02000000");

        String token = issueAccessToken(server);
        try (McpSyncClient client = createMcpClient(server.agentBaseUri(), token)) {
            client.initialize();

            CallToolResult result = client.callTool(new CallToolRequest("vote_on_approval",
                    Map.of("requestId", "req-unassigned", "decision", "deny", "reason", "not assigned")));

            assertTrue(result.isError());
            JsonNode json = parseToolResult(result);
            assertEquals("agent_not_assigned_to_coin", json.path("error").asText());
        }
    }

    @Test
    void voteAfterAlreadyVotingReturnsError() throws Exception {
        AgentRunningServer server = sharedSecondaryServer;
        DataSource dataSource = server.dbManager().dataSource();
        insertAgentApprovalRequest(dataSource, "req-conflict", "bitcoin", "QUEUED", 2, "bc1qconflict", "0.03000000");

        String token = issueAccessToken(server);
        try (McpSyncClient client = createMcpClient(server.agentBaseUri(), token)) {
            client.initialize();

            CallToolResult firstVote = client.callTool(new CallToolRequest("vote_on_approval",
                    Map.of("requestId", "req-conflict", "decision", "approve", "reason", "first")));
            assertFalse(firstVote.isError());

            CallToolResult secondVote = client.callTool(new CallToolRequest("vote_on_approval",
                    Map.of("requestId", "req-conflict", "decision", "approve", "reason", "second")));
            assertTrue(secondVote.isError());
            JsonNode json = parseToolResult(secondVote);
            assertEquals("already_voted", json.path("error").asText());
        }
    }

    // --- Auth Agent Pending Approvals Resource Tests ---

    @Test
    void pendingApprovalsResourceReturnsPendingRequests() throws Exception {
        AgentRunningServer server = sharedSecondaryServer;
        DataSource dataSource = server.dbManager().dataSource();
        insertAgentApprovalRequest(dataSource, "req-sse-open", "bitcoin", "PENDING", 1, "bc1qsseopen", "0.04000000");

        String token = issueAccessToken(server);
        try (McpSyncClient client = createMcpClient(server.agentBaseUri(), token)) {
            client.initialize();

            ReadResourceResult result = client.readResource(new ReadResourceRequest("konkin://approvals/pending"));
            String text = ((TextResourceContents) result.contents().getFirst()).text();
            JsonNode json = JSON_MAPPER.readTree(text);
            assertTrue(json.isArray());
            assertTrue(json.size() >= 1);

            JsonNode first = json.get(0);
            assertEquals("req-sse-open", first.path("requestId").asText());
            assertEquals("bitcoin", first.path("coin").asText());
            assertEquals("send", first.path("type").asText());
            assertEquals("bc1qsseopen", first.path("to").asText());
            assertEquals("0.04000000", first.path("amount").asText());
        }
    }

    // --- Auth Agent Approval Details Resource Tests ---

    @Test
    void approvalDetailsWithAssignedCoinReturnsRequestDetailsAndVotes() throws Exception {
        AgentRunningServer server = sharedSecondaryServer;
        DataSource dataSource = server.dbManager().dataSource();
        insertAgentApprovalRequest(dataSource, "req-details", "bitcoin", "PENDING", 2, "bc1qdetails", "0.06000000");
        insertApprovalChannel(dataSource, "agent-alpha", "mcp_agent");
        insertApprovalVote(dataSource, "req-details", "agent-alpha", "approve");

        String token = issueAccessToken(server);
        try (McpSyncClient client = createMcpClient(server.agentBaseUri(), token)) {
            client.initialize();

            ReadResourceResult result = client.readResource(new ReadResourceRequest("konkin://approvals/req-details"));
            String text = ((TextResourceContents) result.contents().getFirst()).text();
            JsonNode json = JSON_MAPPER.readTree(text);
            assertEquals("req-details", json.path("requestId").asText());
            assertEquals("bitcoin", json.path("coin").asText());
            assertEquals("send", json.path("type").asText());
            assertEquals("PENDING", json.path("state").asText());
            assertFalse(json.path("terminal").asBoolean());
            assertEquals(2, json.path("minApprovalsRequired").asInt());
            assertEquals("bc1qdetails", json.path("to").asText());
            assertEquals("0.06000000", json.path("amount").asText());
            assertTrue(json.path("nonce").asText().startsWith("bitcoin|nonce-uuid-req-details|"));
            assertTrue(json.path("votes").isArray());
            assertEquals(1, json.path("votes").size());
            assertEquals("approve", json.path("votes").get(0).path("decision").asText());
            assertEquals("agent-alpha", json.path("votes").get(0).path("channelId").asText());
        }
    }

    @Test
    void approvalDetailsForUnknownRequestReturnsError() throws Exception {
        AgentRunningServer server = sharedSecondaryServer;
        String token = issueAccessToken(server);
        try (McpSyncClient client = createMcpClient(server.agentBaseUri(), token)) {
            client.initialize();

            ReadResourceResult result = client.readResource(new ReadResourceRequest("konkin://approvals/req-does-not-exist"));
            String text = ((TextResourceContents) result.contents().getFirst()).text();
            JsonNode json = JSON_MAPPER.readTree(text);
            assertEquals("request_not_found", json.path("error").asText());
        }
    }

    @Test
    void approvalDetailsForUnassignedCoinReturnsError() throws Exception {
        AgentRunningServer server = sharedSecondaryServer;
        DataSource dataSource = server.dbManager().dataSource();
        insertAgentApprovalRequest(dataSource, "req-details-unassigned", "monero", "PENDING", 1, "44Affq5kSiGBoZ...", "0.07000000");

        String token = issueAccessToken(server);
        try (McpSyncClient client = createMcpClient(server.agentBaseUri(), token)) {
            client.initialize();

            ReadResourceResult result = client.readResource(new ReadResourceRequest("konkin://approvals/req-details-unassigned"));
            String text = ((TextResourceContents) result.contents().getFirst()).text();
            JsonNode json = JSON_MAPPER.readTree(text);
            assertEquals("agent_not_assigned_to_coin", json.path("error").asText());
        }
    }

    // --- Auth Agent List Eligible Requests Tool Tests ---

    @Test
    void listEligibleRequestsReturnsAssignedUnvotedRequests() throws Exception {
        AgentRunningServer server = sharedSecondaryServer;
        DataSource dataSource = server.dbManager().dataSource();
        insertAgentApprovalRequest(dataSource, "req-eligible-1", "bitcoin", "QUEUED", 2, "bc1qeligible1", "0.10000000");

        String token = issueAccessToken(server);
        try (McpSyncClient client = createMcpClient(server.agentBaseUri(), token)) {
            client.initialize();

            CallToolResult result = client.callTool(new CallToolRequest("list_eligible_requests", Map.of()));
            assertFalse(result.isError());
            JsonNode json = parseToolResult(result);
            assertEquals("agent-alpha", json.path("agentName").asText());
            assertTrue(json.path("eligibleCount").asInt() >= 1);
            assertTrue(json.path("requests").isArray());

            boolean found = false;
            for (JsonNode req : json.path("requests")) {
                if ("req-eligible-1".equals(req.path("requestId").asText())) {
                    assertEquals("bitcoin", req.path("coin").asText());
                    assertEquals("send", req.path("type").asText());
                    assertEquals("bc1qeligible1", req.path("to").asText());
                    assertEquals("0.10000000", req.path("amount").asText());
                    assertTrue(req.has("reason"));
                    assertTrue(req.has("requestedAt"));
                    assertTrue(req.has("expiresAt"));
                    found = true;
                    break;
                }
            }
            assertTrue(found, "Expected req-eligible-1 in eligible requests");
        }
    }

    @Test
    void listEligibleRequestsExcludesAlreadyVotedRequests() throws Exception {
        AgentRunningServer server = sharedSecondaryServer;
        DataSource dataSource = server.dbManager().dataSource();
        insertAgentApprovalRequest(dataSource, "req-eligible-voted", "bitcoin", "QUEUED", 2, "bc1qvoted", "0.11000000");

        String token = issueAccessToken(server);
        try (McpSyncClient client = createMcpClient(server.agentBaseUri(), token)) {
            client.initialize();

            // Vote on the request first
            CallToolResult voteResult = client.callTool(new CallToolRequest("vote_on_approval",
                    Map.of("requestId", "req-eligible-voted", "decision", "approve", "reason", "eligible test")));
            assertFalse(voteResult.isError());

            // Now list eligible requests — the voted request should not appear
            CallToolResult listResult = client.callTool(new CallToolRequest("list_eligible_requests", Map.of()));
            assertFalse(listResult.isError());
            JsonNode json = parseToolResult(listResult);

            for (JsonNode req : json.path("requests")) {
                assertFalse("req-eligible-voted".equals(req.path("requestId").asText()),
                        "Already-voted request should not appear in eligible list");
            }
        }
    }

    @Test
    void listEligibleRequestsExcludesUnassignedCoinRequests() throws Exception {
        AgentRunningServer server = sharedSecondaryServer;
        DataSource dataSource = server.dbManager().dataSource();
        // monero is NOT assigned to agent-alpha (only bitcoin is)
        insertAgentApprovalRequest(dataSource, "req-eligible-unassigned", "monero", "QUEUED", 1, "44Affq5kSiGBoZ...", "1.00000000");

        String token = issueAccessToken(server);
        try (McpSyncClient client = createMcpClient(server.agentBaseUri(), token)) {
            client.initialize();

            CallToolResult result = client.callTool(new CallToolRequest("list_eligible_requests", Map.of()));
            assertFalse(result.isError());
            JsonNode json = parseToolResult(result);

            for (JsonNode req : json.path("requests")) {
                assertFalse("req-eligible-unassigned".equals(req.path("requestId").asText()),
                        "Unassigned coin request should not appear in eligible list");
            }
        }
    }

    // --- Driver Agent Config Requirements Resource Tests ---

    @Test
    void primaryRuntimeConfigRequirementsWithPlaceholderSecretsReturnsNotReady() throws Exception {
        try (AgentRunningServer server = startPrimaryAgentServerWithBitcoinSecrets(
                "rpcuser=REPLACE_WITH_BITCOIN_RPC_USER\nrpcpassword=REPLACE_WITH_BITCOIN_RPC_PASSWORD\n",
                "wallet=REPLACE_WITH_BITCOIN_WALLET_NAME\nwallet-passphrase=REPLACE_WITH_BITCOIN_WALLET_PASSPHRASE\n"
        )) {
            String token = issueAccessToken(server);
            try (McpSyncClient client = createMcpClient(server.agentBaseUri(), token)) {
                client.initialize();

                ReadResourceResult result = client.readResource(new ReadResourceRequest("konkin://runtime/config/requirements/bitcoin"));
                String text = ((TextResourceContents) result.contents().getFirst()).text();
                JsonNode json = JSON_MAPPER.readTree(text);
                assertEquals("bitcoin", json.path("coin").asText());
                assertEquals("NOT_READY", json.path("status").asText());
                assertTrue(json.path("checks").isArray());
                assertTrue(json.path("checks").size() > 0);
                assertTrue(json.path("invalid").isArray());
                assertTrue(json.path("invalid").size() >= 2);
            }
        }
    }

    @Test
    void primaryRuntimeConfigRequirementsWithTestDummyCoinReturnsReadyWhenDebugAndCoinAreEnabled() throws Exception {
        try (AgentRunningServer server = startPrimaryAgentServerForSendActionScenarios(true, false, true)) {
            String token = issueAccessToken(server);
            try (McpSyncClient client = createMcpClient(server.agentBaseUri(), token)) {
                client.initialize();

                ReadResourceResult result = client.readResource(new ReadResourceRequest("konkin://runtime/config/requirements/testdummycoin"));
                String text = ((TextResourceContents) result.contents().getFirst()).text();
                JsonNode json = JSON_MAPPER.readTree(text);
                assertEquals("testdummycoin", json.path("coin").asText());
                assertEquals("READY", json.path("status").asText());
                assertEquals(0, json.path("missing").size());
                assertEquals(0, json.path("invalid").size());
            }
        }
    }

    @Test
    void primaryRuntimeConfigRequirementsWithoutCoinReturnsServerReadyWhenServerIsReady() throws Exception {
        try (AgentRunningServer server = startPrimaryAgentServerWithBitcoinSecrets(
                "rpcuser=alice\nrpcpassword=secret\n",
                "wallet=main\nwallet-passphrase=pass\n"
        )) {
            String token = issueAccessToken(server);
            try (McpSyncClient client = createMcpClient(server.agentBaseUri(), token)) {
                client.initialize();

                ReadResourceResult result = client.readResource(new ReadResourceRequest("konkin://runtime/config/requirements"));
                String text = ((TextResourceContents) result.contents().getFirst()).text();
                JsonNode json = JSON_MAPPER.readTree(text);
                assertEquals("server", json.path("coin").asText());
                assertEquals("READY", json.path("status").asText());
                assertTrue(json.path("message").asText().contains("Server readiness passed"));
                assertEquals(0, json.path("missing").size());
                assertEquals(0, json.path("invalid").size());
            }
        }
    }

    // --- Driver Agent Send Coin Tool Tests ---

    @Test
    void primarySendCoinActionWithBearerReturnsAcceptedAndPersistsRequest() throws Exception {
        try (AgentRunningServer server = startPrimaryAgentServerWithBitcoinSecrets(
                "rpcuser=alice\nrpcpassword=secret\n",
                "wallet=main\nwallet-passphrase=pass\n"
        )) {
            String token = issueAccessToken(server);
            try (McpSyncClient client = createMcpClient(server.agentBaseUri(), token)) {
                client.initialize();

                Map<String, Object> args = new LinkedHashMap<>();
                args.put("coin", "bitcoin");
                args.put("toAddress", "bc1qtestdestination");
                args.put("amountNative", "0.25000000");
                args.put("feePolicy", "dynamic");
                args.put("feeCapNative", "0.00010000");
                args.put("memo", "integration test");
                args.put("reason", "Integration test: verify send_coin acceptance");

                CallToolResult result = client.callTool(new CallToolRequest("send_coin", args));
                assertFalse(result.isError());

                JsonNode accepted = parseToolResult(result);
                assertEquals("accepted", accepted.path("status").asText());
                assertEquals("bitcoin", accepted.path("coin").asText());
                assertEquals("send", accepted.path("action").asText());
                assertEquals("QUEUED", accepted.path("state").asText());

                String requestId = accepted.path("requestId").asText();
                assertTrue(requestId.startsWith("req-"));

                // Verify via REST API
                HttpResponse<String> requestResponse = get(
                        server.server().baseUri(),
                        "/api/v1/requests/" + requestId,
                        Map.of("X-API-Key", server.restApiKey())
                );
                assertEquals(200, requestResponse.statusCode());

                JsonNode saved = JSON.readTree(requestResponse.body());
                assertEquals(requestId, saved.path("id").asText());
                assertEquals("bitcoin", saved.path("coin").asText());
                assertEquals("wallet_send", saved.path("toolName").asText());
                assertEquals("QUEUED", saved.path("state").asText());
                assertEquals("bc1qtestdestination", saved.path("toAddress").asText());
                assertEquals("0.25000000", saved.path("amountNative").asText());
            }
        }
    }

    @Test
    void primarySendCoinActionWithTestDummyCoinReturnsAcceptedAndPersistsRequest() throws Exception {
        try (AgentRunningServer server = startPrimaryAgentServerForSendActionScenarios(true, false, true)) {
            String token = issueAccessToken(server);
            try (McpSyncClient client = createMcpClient(server.agentBaseUri(), token)) {
                client.initialize();

                Map<String, Object> args = new LinkedHashMap<>();
                args.put("coin", "testdummycoin");
                args.put("toAddress", "tdc1qtestdestination");
                args.put("amountNative", "1.25000000");
                args.put("memo", "test dummy integration");
                args.put("reason", "Integration test: verify testdummycoin send");

                CallToolResult result = client.callTool(new CallToolRequest("send_coin", args));
                assertFalse(result.isError());

                JsonNode accepted = parseToolResult(result);
                assertEquals("accepted", accepted.path("status").asText());
                assertEquals("testdummycoin", accepted.path("coin").asText());
                assertEquals("send", accepted.path("action").asText());
                assertEquals("QUEUED", accepted.path("state").asText());

                String requestId = accepted.path("requestId").asText();
                assertTrue(requestId.startsWith("req-"));

                HttpResponse<String> requestResponse = get(
                        server.server().baseUri(),
                        "/api/v1/requests/" + requestId,
                        Map.of("X-API-Key", server.restApiKey())
                );
                assertEquals(200, requestResponse.statusCode());

                JsonNode saved = JSON.readTree(requestResponse.body());
                assertEquals(requestId, saved.path("id").asText());
                assertEquals("testdummycoin", saved.path("coin").asText());
            }
        }
    }

    @Test
    void primarySendCoinActionWithUnsupportedCoinReturnsError() throws Exception {
        try (AgentRunningServer server = startPrimaryAgentServerWithBitcoinSecrets(
                "rpcuser=alice\nrpcpassword=secret\n",
                "wallet=main\nwallet-passphrase=pass\n"
        )) {
            String token = issueAccessToken(server);
            try (McpSyncClient client = createMcpClient(server.agentBaseUri(), token)) {
                client.initialize();

                CallToolResult result = client.callTool(new CallToolRequest("send_coin",
                        Map.of("coin", "dogecoin", "toAddress", "Dabc", "amountNative", "1.0", "reason", "test unsupported coin")));

                assertTrue(result.isError());
                JsonNode json = parseToolResult(result);
                assertEquals("unsupported_coin", json.path("error").asText());
                assertTrue(json.path("message").asText().contains("Supported coins: bitcoin, monero, testdummycoin."));
            }
        }
    }

    @Test
    void primarySendCoinActionWithDisabledCoinReturnsError() throws Exception {
        try (AgentRunningServer server = startPrimaryAgentServerWithBitcoinSecrets(
                "rpcuser=alice\nrpcpassword=secret\n",
                "wallet=main\nwallet-passphrase=pass\n"
        )) {
            String token = issueAccessToken(server);
            try (McpSyncClient client = createMcpClient(server.agentBaseUri(), token)) {
                client.initialize();

                CallToolResult result = client.callTool(new CallToolRequest("send_coin",
                        Map.of("coin", "testdummycoin", "toAddress", "tdc1qdisabled", "amountNative", "0.1", "reason", "test disabled coin")));

                assertTrue(result.isError());
                JsonNode json = parseToolResult(result);
                assertEquals("coin_not_enabled", json.path("error").asText());
                assertTrue(json.path("message").asText().contains(
                        "Enable [debug].enabled=true and [coins.testdummycoin].enabled=true in config.toml."
                ));
            }
        }
    }

    @Test
    void primarySendCoinActionWithNoEnabledCoinRuntimeReturnsError() throws Exception {
        try (AgentRunningServer server = startPrimaryAgentServerForSendActionScenarios(false, false, false)) {
            String token = issueAccessToken(server);
            try (McpSyncClient client = createMcpClient(server.agentBaseUri(), token)) {
                client.initialize();

                CallToolResult result = client.callTool(new CallToolRequest("send_coin",
                        Map.of("coin", "bitcoin", "toAddress", "bc1qnoruntime", "amountNative", "0.2", "reason", "test no runtime")));

                assertTrue(result.isError());
                JsonNode json = parseToolResult(result);
                assertEquals("no_coin_runtime_available", json.path("error").asText());
                assertTrue(json.path("message").asText().contains("No coin runtime is enabled on this server."));
            }
        }
    }

    // --- Driver Agent Decision Status Resource Tests ---

    @Test
    void primaryDecisionStatusWithBearerReturnsSnapshotForQueuedRequest() throws Exception {
        try (AgentRunningServer server = startPrimaryAgentServerWithBitcoinSecrets(
                "rpcuser=alice\nrpcpassword=secret\n",
                "wallet=main\nwallet-passphrase=pass\n"
        )) {
            String token = issueAccessToken(server);
            try (McpSyncClient client = createMcpClient(server.agentBaseUri(), token)) {
                client.initialize();

                // First, submit a send action
                CallToolResult sendResult = client.callTool(new CallToolRequest("send_coin",
                        Map.of("coin", "bitcoin", "toAddress", "bc1qstatusdestination", "amountNative", "0.05000000", "reason", "test decision status")));
                assertFalse(sendResult.isError());
                String requestId = parseToolResult(sendResult).path("requestId").asText();

                // Now read decision status
                ReadResourceResult statusResult = client.readResource(new ReadResourceRequest("konkin://decisions/" + requestId));
                String text = ((TextResourceContents) statusResult.contents().getFirst()).text();
                JsonNode status = JSON_MAPPER.readTree(text);
                assertEquals(requestId, status.path("requestId").asText());
                assertEquals("bitcoin", status.path("coin").asText());
                assertEquals("QUEUED", status.path("state").asText());
                assertFalse(status.path("terminal").asBoolean());
                assertEquals(1, status.path("minApprovalsRequired").asInt());
                assertEquals(0, status.path("approvalsGranted").asInt());
                assertEquals(0, status.path("approvalsDenied").asInt());
            }
        }
    }

    @Test
    void primaryDecisionStatusForUnknownRequestReturnsError() throws Exception {
        try (AgentRunningServer server = startPrimaryAgentServerWithBitcoinSecrets(
                "rpcuser=alice\nrpcpassword=secret\n",
                "wallet=main\nwallet-passphrase=pass\n"
        )) {
            String token = issueAccessToken(server);
            try (McpSyncClient client = createMcpClient(server.agentBaseUri(), token)) {
                client.initialize();

                ReadResourceResult result = client.readResource(new ReadResourceRequest("konkin://decisions/req-does-not-exist"));
                String text = ((TextResourceContents) result.contents().getFirst()).text();
                JsonNode json = JSON_MAPPER.readTree(text);
                assertEquals("request_not_found", json.path("error").asText());
            }
        }
    }

    @Test
    void primaryDecisionStatusReturnsTerminalStateAfterCompletion() throws Exception {
        try (AgentRunningServer server = startPrimaryAgentServerWithBitcoinSecrets(
                "rpcuser=alice\nrpcpassword=secret\n",
                "wallet=main\nwallet-passphrase=pass\n"
        )) {
            String token = issueAccessToken(server);
            try (McpSyncClient client = createMcpClient(server.agentBaseUri(), token)) {
                client.initialize();

                CallToolResult sendResult = client.callTool(new CallToolRequest("send_coin",
                        Map.of("coin", "bitcoin", "toAddress", "bc1qeventdestination", "amountNative", "0.07500000", "reason", "test terminal state")));
                String requestId = parseToolResult(sendResult).path("requestId").asText();

                markRequestCompleted(server.dbManager().dataSource(), requestId);

                ReadResourceResult statusResult = client.readResource(new ReadResourceRequest("konkin://decisions/" + requestId));
                String text = ((TextResourceContents) statusResult.contents().getFirst()).text();
                JsonNode status = JSON_MAPPER.readTree(text);
                assertEquals("COMPLETED", status.path("state").asText());
                assertTrue(status.path("terminal").asBoolean());
                assertEquals("executed", status.path("latestReasonCode").asText());
                assertEquals("Transaction broadcast", status.path("latestReasonText").asText());
                assertEquals("txid-abc123", status.path("txid").asText());
            }
        }
    }

    // --- Notification Tests ---

    @Test
    void pendingApprovalsNotificationFiredOnChange() throws Exception {
        AgentRunningServer server = sharedSecondaryServer;
        DataSource dataSource = server.dbManager().dataSource();
        String token = issueAccessToken(server);

        AtomicReference<Boolean> notified = new AtomicReference<>(false);
        CountDownLatch latch = new CountDownLatch(1);

        McpSyncClient client = McpClient.sync(
                HttpClientSseClientTransport.builder("http://127.0.0.1:" + server.agentPort())
                        .customizeRequest(b -> b.header("Authorization", "Bearer " + token))
                        .build()
        ).resourcesUpdateConsumer(resources -> {
            notified.set(true);
            latch.countDown();
        }).build();

        try {
            client.initialize();

            // Insert a pending approval to trigger change — the poller will detect the change
            // and send a notification to all connected clients
            insertAgentApprovalRequest(dataSource, "req-notify", "bitcoin", "PENDING", 1, "bc1qnotify", "0.01");

            assertTrue(latch.await(5, TimeUnit.SECONDS), "Expected resource update notification within 5 seconds");
            assertTrue(notified.get());
        } finally {
            client.close();
        }
    }

    // --- Helper methods ---

    private McpSyncClient createMcpClient(URI agentBaseUri, String token) {
        HttpClientSseClientTransport transport = HttpClientSseClientTransport.builder(agentBaseUri.toString())
                .customizeRequest(b -> b.header("Authorization", "Bearer " + token))
                .build();
        return McpClient.sync(transport).build();
    }

    private JsonNode parseToolResult(CallToolResult result) throws Exception {
        String text = ((TextContent) result.content().getFirst()).text();
        return JSON_MAPPER.readTree(text);
    }

    private AgentRunningServer startSecondaryAgentServer(String agentName) throws Exception {
        return startSecondaryAgentServer(tempDir, agentName);
    }

    private static AgentRunningServer startSecondaryAgentServer(Path workDir, String agentName) throws Exception {
        int serverPort = freePort();
        int agentPort = freePort();
        while (agentPort == serverPort) {
            agentPort = freePort();
        }

        Path agentSecretFile = workDir.resolve("secrets/" + agentName + ".secret");
        Path bitcoinDaemonSecretFile = workDir.resolve("secrets/bitcoin-daemon.conf");
        Path bitcoinWalletSecretFile = workDir.resolve("secrets/bitcoin-wallet.conf");
        String dbUrl = "jdbc:h2:mem:agent-test;DB_CLOSE_DELAY=-1";

        Path daemonParent = bitcoinDaemonSecretFile.getParent();
        if (daemonParent != null) {
            Files.createDirectories(daemonParent);
        }

        Files.writeString(bitcoinDaemonSecretFile, "rpcuser=alice\nrpcpassword=secret\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        Files.writeString(bitcoinWalletSecretFile, "wallet=main\nwallet-passphrase=pass\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        String configToml = TestConfigBuilder.create(serverPort)
                .withDatabase(dbUrl, "konkin", "konkin", 5)
                .withRestApi(false)
                .withWebUi(false)
                .withTelegram(false)
                .withSecondaryAgent(agentName, true, "127.0.0.1", agentPort, agentSecretFile)
                .withBitcoin(bitcoinDaemonSecretFile, bitcoinWalletSecretFile)
                .withBitcoinAuthFull(false, false, false, null, java.util.List.of(agentName), 1)
                .build();

        Path configFile = workDir.resolve("config-agent-%d.toml".formatted(System.nanoTime()));
        Files.writeString(configFile, configToml, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);

        KonkinConfig config = KonkinConfig.load(configFile.toString());
        DatabaseManager dbManager = new DatabaseManager(TestDatabaseManager.dataSource("agent-test"));
        KonkinWebServer webServer = new KonkinWebServer(config, "test-version", dbManager.dataSource());
        webServer.start();

        try {
            waitForHealth(serverPort);
            waitForMcpAgentHealth(agentPort);
        } catch (Exception e) {
            webServer.stop();
            dbManager.shutdown();
            throw e;
        }

        Properties secret = loadProperties(agentSecretFile);
        return new AgentRunningServer(
                new RunningServer(webServer, URI.create("http://127.0.0.1:" + serverPort)),
                URI.create("http://127.0.0.1:" + agentPort),
                agentPort,
                secret.getProperty("client-id", "").trim(),
                secret.getProperty("client-secret", "").trim(),
                "",
                dbManager
        );
    }

    private AgentRunningServer startPrimaryAgentServerWithBitcoinSecrets(
            String daemonSecretContent, String walletSecretContent
    ) throws Exception {
        int serverPort = freePort();
        int primaryAgentPort = freePort();
        while (primaryAgentPort == serverPort) {
            primaryAgentPort = freePort();
        }

        Path primarySecretFile = tempDir.resolve("secrets/agent-primary.secret");
        Path restApiSecretFile = tempDir.resolve("secrets/rest-api.secret");
        Path bitcoinDaemonSecretFile = tempDir.resolve("secrets/bitcoin-daemon.conf");
        Path bitcoinWalletSecretFile = tempDir.resolve("secrets/bitcoin-wallet.conf");
        String dbUrl = "jdbc:h2:mem:agent-test;DB_CLOSE_DELAY=-1";

        Path daemonParent = bitcoinDaemonSecretFile.getParent();
        if (daemonParent != null) {
            Files.createDirectories(daemonParent);
        }

        Files.writeString(bitcoinDaemonSecretFile, daemonSecretContent,
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        Files.writeString(bitcoinWalletSecretFile, walletSecretContent,
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        Files.writeString(restApiSecretFile, "api-key=test-api-key-primary",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        String configToml = TestConfigBuilder.create(serverPort)
                .withDatabase(dbUrl, "konkin", "konkin", 5)
                .withRestApiSecret(restApiSecretFile)
                .withWebUi(false)
                .withTelegram(false)
                .withPrimaryAgent(true, "127.0.0.1", primaryAgentPort, primarySecretFile)
                .withBitcoin(bitcoinDaemonSecretFile, bitcoinWalletSecretFile)
                .withBitcoinAuthFull(false, true, false, null, java.util.List.of(), 1)
                .build();

        Path configFile = tempDir.resolve("config-primary-agent-%d.toml".formatted(System.nanoTime()));
        Files.writeString(configFile, configToml, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);

        KonkinConfig config = KonkinConfig.load(configFile.toString());
        DatabaseManager dbManager = new DatabaseManager(TestDatabaseManager.dataSource("agent-test"));
        KonkinWebServer webServer = new KonkinWebServer(config, "test-version", dbManager.dataSource());
        webServer.start();

        try {
            waitForHealth(serverPort);
            waitForMcpAgentHealth(primaryAgentPort);
        } catch (Exception e) {
            webServer.stop();
            dbManager.shutdown();
            throw e;
        }

        Properties secret = loadProperties(primarySecretFile);
        String clientId = secret.getProperty("client-id", "").trim();
        String clientSecret = secret.getProperty("client-secret", "").trim();
        String restApiKey = loadProperties(restApiSecretFile).getProperty("api-key", "").trim();
        assertNotNull(clientId);
        assertNotNull(clientSecret);
        assertNotNull(restApiKey);
        return new AgentRunningServer(
                new RunningServer(webServer, URI.create("http://127.0.0.1:" + serverPort)),
                URI.create("http://127.0.0.1:" + primaryAgentPort),
                primaryAgentPort,
                clientId, clientSecret, restApiKey,
                dbManager
        );
    }

    private AgentRunningServer startPrimaryAgentServerForSendActionScenarios(
            boolean debugEnabled, boolean bitcoinEnabled, boolean testDummyEnabled
    ) throws Exception {
        int serverPort = freePort();
        int primaryAgentPort = freePort();

        Path primarySecretFile = tempDir.resolve("secrets/agent-primary-send-%d.secret".formatted(System.nanoTime()));
        Path restApiSecretFile = tempDir.resolve("secrets/rest-api-send-%d.secret".formatted(System.nanoTime()));
        Path bitcoinDaemonSecretFile = tempDir.resolve("secrets/bitcoin-daemon-send-%d.conf".formatted(System.nanoTime()));
        Path bitcoinWalletSecretFile = tempDir.resolve("secrets/bitcoin-wallet-send-%d.conf".formatted(System.nanoTime()));
        String dbUrl = "jdbc:h2:mem:agent-test;DB_CLOSE_DELAY=-1";

        Path daemonParent = bitcoinDaemonSecretFile.getParent();
        if (daemonParent != null) {
            Files.createDirectories(daemonParent);
        }

        Files.writeString(bitcoinDaemonSecretFile, "rpcuser=alice\nrpcpassword=secret\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        Files.writeString(bitcoinWalletSecretFile, "wallet=main\nwallet-passphrase=pass\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        Files.writeString(restApiSecretFile, "api-key=test-api-key-send",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        String configToml = TestConfigBuilder.create(serverPort)
                .withDatabase(dbUrl, "konkin", "konkin", 5)
                .withRestApiSecret(restApiSecretFile)
                .withWebUi(false)
                .withTelegram(false)
                .withDebug(debugEnabled)
                .withPrimaryAgent(true, "127.0.0.1", primaryAgentPort, primarySecretFile)
                .withBitcoinEnabled(bitcoinEnabled, bitcoinDaemonSecretFile, bitcoinWalletSecretFile)
                .withBitcoinAuthFull(false, true, false, null, java.util.List.of(), 1)
                .withTestDummyCoin(testDummyEnabled)
                .withTestDummyCoinAuth(false, true, false, "tdc-main", java.util.List.of(), 1)
                .build();

        Path configFile = tempDir.resolve("config-primary-send-agent-%d.toml".formatted(System.nanoTime()));
        Files.writeString(configFile, configToml, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);

        KonkinConfig config = KonkinConfig.load(configFile.toString());
        DatabaseManager dbManager = new DatabaseManager(TestDatabaseManager.dataSource("agent-test"));
        KonkinWebServer webServer = new KonkinWebServer(config, "test-version", dbManager.dataSource());
        webServer.start();

        try {
            waitForHealth(serverPort);
            waitForMcpAgentHealth(primaryAgentPort);
        } catch (Exception e) {
            webServer.stop();
            dbManager.shutdown();
            throw e;
        }

        Properties secret = loadProperties(primarySecretFile);
        String clientId = secret.getProperty("client-id", "").trim();
        String clientSecret = secret.getProperty("client-secret", "").trim();
        String restApiKey = loadProperties(restApiSecretFile).getProperty("api-key", "").trim();
        assertNotNull(clientId);
        assertNotNull(clientSecret);
        assertNotNull(restApiKey);
        return new AgentRunningServer(
                new RunningServer(webServer, URI.create("http://127.0.0.1:" + serverPort)),
                URI.create("http://127.0.0.1:" + primaryAgentPort),
                primaryAgentPort,
                clientId, clientSecret, restApiKey,
                dbManager
        );
    }

    private String issueAccessToken(AgentRunningServer server) throws Exception {
        HttpResponse<String> response = postForm(server.agentBaseUri(), "/oauth/token", oauthFormBody(server), Map.of());
        assertEquals(200, response.statusCode());
        JsonNode json = JSON.readTree(response.body());
        return json.path("access_token").asText();
    }

    private String oauthFormBody(AgentRunningServer server) {
        return "grant_type=client_credentials&client_id="
                + urlEncode(server.clientId())
                + "&client_secret="
                + urlEncode(server.clientSecret());
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static HttpResponse<String> get(URI baseUri, String path, Map<String, String> headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(Duration.ofSeconds(5))
                .GET();
        headers.forEach(builder::header);
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> postForm(URI baseUri, String path, String body, Map<String, String> headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(builder::header);
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void markRequestCompleted(DataSource dataSource, String requestId) {
        Instant now = Instant.now();
        JdbiFactory.create(dataSource).useTransaction(handle -> {
            handle.createUpdate("""
                            UPDATE approval_requests
                            SET state = :state,
                                state_reason_code = :reasonCode,
                                state_reason_text = :reasonText,
                                approvals_granted = :granted,
                                approvals_denied = :denied,
                                updated_at = :updatedAt,
                                resolved_at = :resolvedAt
                            WHERE id = :id
                            """)
                    .bind("state", "COMPLETED")
                    .bind("reasonCode", "executed")
                    .bind("reasonText", "Transaction broadcast")
                    .bind("granted", 1)
                    .bind("denied", 0)
                    .bind("updatedAt", now)
                    .bind("resolvedAt", now)
                    .bind("id", requestId)
                    .execute();

            handle.createUpdate("""
                            INSERT INTO approval_state_transitions (
                                request_id, from_state, to_state, actor_type, actor_id, reason_code, reason_text, created_at
                            ) VALUES (
                                :requestId, :fromState, :toState, :actorType, :actorId, :reasonCode, :reasonText, :createdAt
                            )
                            """)
                    .bind("requestId", requestId)
                    .bind("fromState", "QUEUED")
                    .bind("toState", "COMPLETED")
                    .bind("actorType", "executor")
                    .bind("actorId", "integration-test")
                    .bind("reasonCode", "executed")
                    .bind("reasonText", "Transaction broadcast")
                    .bind("createdAt", now.plusMillis(1))
                    .execute();

            handle.createUpdate("""
                            INSERT INTO approval_execution_attempts (
                                request_id, attempt_no, started_at, finished_at, result, txid
                            ) VALUES (
                                :requestId, :attemptNo, :startedAt, :finishedAt, :result, :txid
                            )
                            """)
                    .bind("requestId", requestId)
                    .bind("attemptNo", 1)
                    .bind("startedAt", now)
                    .bind("finishedAt", now.plusSeconds(1))
                    .bind("result", "success")
                    .bind("txid", "txid-abc123")
                    .execute();
        });
    }

    private static void insertAgentApprovalRequest(
            DataSource dataSource, String requestId, String coin, String state,
            int minApprovalsRequired, String toAddress, String amountNative
    ) {
        Instant now = Instant.now();
        JdbiFactory.create(dataSource).useHandle(h -> h.createUpdate("""
                        INSERT INTO approval_requests (
                            id, coin, tool_name, request_session_id, nonce_uuid, payload_hash_sha256, nonce_composite,
                            to_address, amount_native, fee_policy, fee_cap_native, memo, reason,
                            requested_at, expires_at, state, state_reason_code, state_reason_text,
                            min_approvals_required, approvals_granted, approvals_denied, policy_action_at_creation,
                            created_at, updated_at, resolved_at
                        ) VALUES (
                            :id, :coin, :toolName, :requestSessionId, :nonceUuid, :payloadHashSha256, :nonceComposite,
                            :toAddress, :amountNative, :feePolicy, :feeCapNative, :memo, :reason,
                            :requestedAt, :expiresAt, :state, :stateReasonCode, :stateReasonText,
                            :minApprovalsRequired, :approvalsGranted, :approvalsDenied, :policyActionAtCreation,
                            :createdAt, :updatedAt, :resolvedAt
                        )
                        """)
                .bind("id", requestId)
                .bind("coin", coin)
                .bind("toolName", "wallet_send")
                .bind("requestSessionId", "session-" + requestId)
                .bind("nonceUuid", "nonce-uuid-" + requestId)
                .bind("payloadHashSha256", "sha256-" + requestId)
                .bind("nonceComposite", coin + "|nonce-uuid-" + requestId + "|sha256-" + requestId)
                .bind("toAddress", toAddress)
                .bind("amountNative", amountNative)
                .bind("feePolicy", "dynamic")
                .bind("feeCapNative", "0.00010000")
                .bind("memo", "integration-test")
                .bind("reason", "Agent test: send " + amountNative + " " + coin + " to " + toAddress)
                .bind("requestedAt", now)
                .bind("expiresAt", now.plusSeconds(600))
                .bind("state", state)
                .bind("stateReasonCode", "queued_for_approval")
                .bind("stateReasonText", "Queued for approval")
                .bind("minApprovalsRequired", minApprovalsRequired)
                .bind("approvalsGranted", 0)
                .bind("approvalsDenied", 0)
                .bind("policyActionAtCreation", "manual")
                .bind("createdAt", now)
                .bind("updatedAt", now)
                .bindNull("resolvedAt", Types.TIMESTAMP)
                .execute());
    }

    private static int countVotesForRequest(DataSource dataSource, String requestId) {
        return JdbiFactory.create(dataSource).withHandle(h -> h.createQuery("""
                        SELECT COUNT(*)
                        FROM approval_votes
                        WHERE request_id = :requestId
                        """)
                .bind("requestId", requestId)
                .mapTo(Integer.class)
                .one());
    }

    private static String loadRequestState(DataSource dataSource, String requestId) {
        return JdbiFactory.create(dataSource).withHandle(h -> h.createQuery("""
                        SELECT state
                        FROM approval_requests
                        WHERE id = :requestId
                        """)
                .bind("requestId", requestId)
                .mapTo(String.class)
                .one());
    }

    private static void waitForMcpAgentHealth(int port) throws Exception {
        // MCP agent doesn't expose /health as HTTP GET anymore.
        // Instead, try to establish an MCP client connection.
        // As a simpler approach, just wait for the port to accept connections.
        Instant deadline = Instant.now().plusSeconds(5);
        while (Instant.now().isBefore(deadline)) {
            try {
                var socket = new java.net.Socket("127.0.0.1", port);
                socket.close();
                // Give server a moment to finish initialization
                Thread.sleep(200);
                return;
            } catch (Exception ignored) {
            }
            Thread.sleep(50L);
        }
        throw new IllegalStateException("MCP agent endpoint on port " + port + " did not become reachable in time");
    }

    private static Properties loadProperties(Path file) throws IOException {
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }

    private record AgentRunningServer(
            RunningServer server,
            URI agentBaseUri,
            int agentPort,
            String clientId,
            String clientSecret,
            String restApiKey,
            DatabaseManager dbManager
    ) implements AutoCloseable {
        @Override
        public void close() {
            server.close();
            if (dbManager != null) {
                dbManager.shutdown();
            }
        }
    }
}