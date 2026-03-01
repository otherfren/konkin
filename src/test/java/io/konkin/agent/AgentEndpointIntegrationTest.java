package io.konkin.agent;

import com.fasterxml.jackson.databind.JsonNode;
import io.konkin.config.KonkinConfig;
import io.konkin.db.DatabaseManager;
import io.konkin.db.JdbiFactory;
import io.konkin.web.KonkinWebServer;
import io.konkin.web.WebIntegrationTestSupport;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
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
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentEndpointIntegrationTest extends WebIntegrationTestSupport {

    @Test
    void oauthTokenWithCorrectCredentialsReturnsAccessToken() throws Exception {
        try (AgentRunningServer server = startSecondaryAgentServer("agent-alpha")) {
            HttpResponse<String> response = postForm(server.agentBaseUri(), "/oauth/token", oauthFormBody(server), Map.of());

            assertEquals(200, response.statusCode());
            JsonNode json = JSON.readTree(response.body());
            assertFalse(json.path("access_token").asText().isBlank());
            assertEquals("Bearer", json.path("token_type").asText());
            assertEquals(3600, json.path("expires_in").asInt());
        }
    }

    @Test
    void oauthTokenWithWrongCredentialsReturnsUnauthorized() throws Exception {
        try (AgentRunningServer server = startSecondaryAgentServer("agent-alpha")) {
            String body = "grant_type=client_credentials&client_id="
                    + urlEncode(server.clientId())
                    + "&client_secret="
                    + urlEncode("wrong-secret");

            HttpResponse<String> response = postForm(server.agentBaseUri(), "/oauth/token", body, Map.of());

            assertEquals(401, response.statusCode());
        }
    }

    @Test
    void healthEndpointReturnsOkWithoutAuth() throws Exception {
        try (AgentRunningServer server = startSecondaryAgentServer("agent-alpha")) {
            HttpResponse<String> response = get(server.agentBaseUri(), "/health", Map.of());

            assertEquals(200, response.statusCode());
            JsonNode json = JSON.readTree(response.body());
            assertEquals("healthy", json.path("status").asText());
            assertEquals("agent-alpha", json.path("agent").asText());
            assertEquals("secondary", json.path("type").asText());
        }
    }

    @Test
    void approvalsPendingWithoutBearerReturnsUnauthorized() throws Exception {
        try (AgentRunningServer server = startSecondaryAgentServer("agent-alpha")) {
            HttpResponse<String> response = get(server.agentBaseUri(), "/approvals/pending", Map.of());
            assertEquals(401, response.statusCode());
        }
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
    void voteWithValidBearerTokenReturnsAcceptedAndPersistsVote() throws Exception {
        try (AgentRunningServer server = startSecondaryAgentServer("agent-alpha")) {
            DataSource dataSource = server.dbManager().dataSource();
            insertAgentApprovalRequest(
                    dataSource,
                    "req-123",
                    "bitcoin",
                    "QUEUED",
                    1,
                    "bc1qvoteaccept",
                    "0.10000000"
            );

            String token = issueAccessToken(server);
            HttpResponse<String> response = postJson(
                    server.agentBaseUri(),
                    "/approvals/req-123/vote",
                    "{\"decision\":\"approve\",\"reason\":\"within policy\"}",
                    Map.of("Authorization", "Bearer " + token)
            );

            assertEquals(200, response.statusCode());
            JsonNode json = JSON.readTree(response.body());
            assertEquals("accepted", json.path("status").asText());
            assertEquals("req-123", json.path("requestId").asText());
            assertEquals("approve", json.path("decision").asText());

            assertEquals(1, countVotesForRequest(dataSource, "req-123"));
            assertEquals("APPROVED", loadRequestState(dataSource, "req-123"));
        }
    }

    @Test
    void voteForResolvedRequestReturnsNotFound() throws Exception {
        try (AgentRunningServer server = startSecondaryAgentServer("agent-alpha")) {
            DataSource dataSource = server.dbManager().dataSource();
            insertAgentApprovalRequest(
                    dataSource,
                    "req-resolved",
                    "bitcoin",
                    "COMPLETED",
                    1,
                    "bc1qresolved",
                    "0.01000000"
            );

            String token = issueAccessToken(server);
            HttpResponse<String> response = postJson(
                    server.agentBaseUri(),
                    "/approvals/req-resolved/vote",
                    "{\"decision\":\"approve\"}",
                    Map.of("Authorization", "Bearer " + token)
            );

            assertEquals(404, response.statusCode());
            JsonNode json = JSON.readTree(response.body());
            assertEquals("request_not_found_or_resolved", json.path("error").asText());
        }
    }

    @Test
    void voteForUnassignedCoinReturnsForbidden() throws Exception {
        try (AgentRunningServer server = startSecondaryAgentServer("agent-alpha")) {
            DataSource dataSource = server.dbManager().dataSource();
            insertAgentApprovalRequest(
                    dataSource,
                    "req-unassigned",
                    "monero",
                    "PENDING",
                    1,
                    "44Affq5kSiGBoZ...",
                    "0.02000000"
            );

            String token = issueAccessToken(server);
            HttpResponse<String> response = postJson(
                    server.agentBaseUri(),
                    "/approvals/req-unassigned/vote",
                    "{\"decision\":\"deny\",\"reason\":\"not assigned\"}",
                    Map.of("Authorization", "Bearer " + token)
            );

            assertEquals(403, response.statusCode());
            JsonNode json = JSON.readTree(response.body());
            assertEquals("agent_not_assigned_to_coin", json.path("error").asText());
        }
    }

    @Test
    void voteAfterAlreadyVotingReturnsConflict() throws Exception {
        try (AgentRunningServer server = startSecondaryAgentServer("agent-alpha")) {
            DataSource dataSource = server.dbManager().dataSource();
            insertAgentApprovalRequest(
                    dataSource,
                    "req-conflict",
                    "bitcoin",
                    "QUEUED",
                    2,
                    "bc1qconflict",
                    "0.03000000"
            );

            String token = issueAccessToken(server);

            HttpResponse<String> firstVote = postJson(
                    server.agentBaseUri(),
                    "/approvals/req-conflict/vote",
                    "{\"decision\":\"approve\",\"reason\":\"first\"}",
                    Map.of("Authorization", "Bearer " + token)
            );
            assertEquals(200, firstVote.statusCode());

            HttpResponse<String> secondVote = postJson(
                    server.agentBaseUri(),
                    "/approvals/req-conflict/vote",
                    "{\"decision\":\"approve\",\"reason\":\"second\"}",
                    Map.of("Authorization", "Bearer " + token)
            );
            assertEquals(409, secondVote.statusCode());
            JsonNode json = JSON.readTree(secondVote.body());
            assertEquals("already_voted", json.path("error").asText());
        }
    }

    @Test
    void approvalsPendingStreamEmitsApprovalRequestEvent() throws Exception {
        try (AgentRunningServer server = startSecondaryAgentServer("agent-alpha")) {
            DataSource dataSource = server.dbManager().dataSource();
            insertAgentApprovalRequest(
                    dataSource,
                    "req-sse-open",
                    "bitcoin",
                    "PENDING",
                    1,
                    "bc1qsseopen",
                    "0.04000000"
            );

            String token = issueAccessToken(server);
            HttpRequest request = HttpRequest.newBuilder(server.agentBaseUri().resolve("/approvals/pending"))
                    .timeout(Duration.ofSeconds(20))
                    .header("Accept", "text/event-stream")
                    .header("Authorization", "Bearer " + token)
                    .GET()
                    .build();

            var streamFuture = HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString());
            Thread.sleep(1500L);
            server.server().server().stop();

            HttpResponse<String> response = streamFuture.get(6, TimeUnit.SECONDS);
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("event: approval_request"));

            JsonNode payload = JSON.readTree(firstSseDataPayloadForEvent(response.body(), "approval_request"));
            assertEquals("req-sse-open", payload.path("requestId").asText());
            assertEquals("bitcoin", payload.path("coin").asText());
            assertEquals("send", payload.path("type").asText());
            assertEquals("bc1qsseopen", payload.path("to").asText());
            assertEquals("0.04000000", payload.path("amount").asText());
        }
    }

    @Test
    void approvalsPendingStreamEmitsApprovalCancelledEvent() throws Exception {
        try (AgentRunningServer server = startSecondaryAgentServer("agent-alpha")) {
            DataSource dataSource = server.dbManager().dataSource();
            insertAgentApprovalRequest(
                    dataSource,
                    "req-sse-cancel",
                    "bitcoin",
                    "PENDING",
                    1,
                    "bc1qssecancel",
                    "0.05000000"
            );

            String token = issueAccessToken(server);
            HttpRequest request = HttpRequest.newBuilder(server.agentBaseUri().resolve("/approvals/pending"))
                    .timeout(Duration.ofSeconds(20))
                    .header("Accept", "text/event-stream")
                    .header("Authorization", "Bearer " + token)
                    .GET()
                    .build();

            HttpResponse<InputStream> streamResponse = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200, streamResponse.statusCode());

            String sseBody;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(streamResponse.body(), StandardCharsets.UTF_8))) {
                String initialChunk = readSseUntilEvent(reader, "approval_request", Duration.ofSeconds(4));
                assertTrue(initialChunk.contains("event: approval_request"), initialChunk);

                updateApprovalRequestState(
                        dataSource,
                        "req-sse-cancel",
                        "DENIED",
                        "vote_denied",
                        "Denied during integration test"
                );
                assertEquals("DENIED", loadRequestState(dataSource, "req-sse-cancel"));

                String cancellationChunk = readSseUntilEvent(reader, "approval_cancelled", Duration.ofSeconds(6));
                sseBody = initialChunk + cancellationChunk;
            }

            assertTrue(sseBody.contains("event: approval_cancelled"), sseBody);

            JsonNode payload = JSON.readTree(firstSseDataPayloadForEvent(sseBody, "approval_cancelled"));
            assertEquals("req-sse-cancel", payload.path("requestId").asText());
            assertEquals("vote_denied", payload.path("reason").asText());
        }
    }

    @Test
    void primaryRuntimeConfigRequirementsWithoutBearerReturnsUnauthorized() throws Exception {
        try (AgentRunningServer server = startPrimaryAgentServerWithBitcoinSecrets(
                "rpcuser=alice\nrpcpassword=secret\n",
                "wallet=main\nwallet-passphrase=pass\n"
        )) {
            HttpResponse<String> response = get(server.agentBaseUri(), "/runtime/config/requirements", Map.of());
            assertEquals(401, response.statusCode());
        }
    }

    @Test
    void primaryRuntimeConfigRequirementsWithPlaceholderSecretsReturnsNotReady() throws Exception {
        try (AgentRunningServer server = startPrimaryAgentServerWithBitcoinSecrets(
                "rpcuser=REPLACE_WITH_BITCOIN_RPC_USER\nrpcpassword=REPLACE_WITH_BITCOIN_RPC_PASSWORD\n",
                "wallet=REPLACE_WITH_BITCOIN_WALLET_NAME\nwallet-passphrase=REPLACE_WITH_BITCOIN_WALLET_PASSPHRASE\n"
        )) {
            String token = issueAccessToken(server);

            HttpResponse<String> response = get(
                    server.agentBaseUri(),
                    "/runtime/config/requirements?coin=bitcoin",
                    Map.of("Authorization", "Bearer " + token)
            );

            assertEquals(200, response.statusCode());
            JsonNode json = JSON.readTree(response.body());
            assertEquals("bitcoin", json.path("coin").asText());
            assertEquals("NOT_READY", json.path("status").asText());
            assertTrue(json.path("checks").isArray());
            assertTrue(json.path("checks").size() > 0);
            assertTrue(json.path("invalid").isArray());
            assertTrue(json.path("invalid").size() >= 2);
        }
    }

    @Test
    void primaryRuntimeConfigRequirementsWithConfiguredSecretsReturnsReady() throws Exception {
        try (AgentRunningServer server = startPrimaryAgentServerWithBitcoinSecrets(
                "rpcuser=alice\nrpcpassword=secret\n",
                "wallet=main\nwallet-passphrase=pass\n"
        )) {
            String token = issueAccessToken(server);

            HttpResponse<String> response = get(
                    server.agentBaseUri(),
                    "/runtime/config/requirements",
                    Map.of("Authorization", "Bearer " + token)
            );

            assertEquals(200, response.statusCode());
            JsonNode json = JSON.readTree(response.body());
            assertEquals("bitcoin", json.path("coin").asText());
            assertEquals("READY", json.path("status").asText());
            assertTrue(json.path("checks").isArray());
            assertEquals(0, json.path("missing").size());
            assertEquals(0, json.path("invalid").size());
        }
    }

    @Test
    void primarySendCoinActionWithoutBearerReturnsUnauthorized() throws Exception {
        try (AgentRunningServer server = startPrimaryAgentServerWithBitcoinSecrets(
                "rpcuser=alice\nrpcpassword=secret\n",
                "wallet=main\nwallet-passphrase=pass\n"
        )) {
            HttpResponse<String> response = postJson(
                    server.agentBaseUri(),
                    "/coins/bitcoin/actions/send",
                    "{\"toAddress\":\"bc1qexample\",\"amountNative\":\"0.1\"}",
                    Map.of()
            );
            assertEquals(401, response.statusCode());
        }
    }

    @Test
    void primarySendCoinActionWithBearerReturnsAcceptedAndPersistsRequest() throws Exception {
        try (AgentRunningServer server = startPrimaryAgentServerWithBitcoinSecrets(
                "rpcuser=alice\nrpcpassword=secret\n",
                "wallet=main\nwallet-passphrase=pass\n"
        )) {
            String token = issueAccessToken(server);
            HttpResponse<String> response = postJson(
                    server.agentBaseUri(),
                    "/coins/bitcoin/actions/send",
                    """
                    {
                      \"toAddress\": \"bc1qtestdestination\",
                      \"amountNative\": \"0.25000000\",
                      \"feePolicy\": \"dynamic\",
                      \"feeCapNative\": \"0.00010000\",
                      \"memo\": \"integration test\"
                    }
                    """,
                    Map.of("Authorization", "Bearer " + token)
            );

            assertEquals(202, response.statusCode());
            JsonNode accepted = JSON.readTree(response.body());
            assertEquals("accepted", accepted.path("status").asText());
            assertEquals("bitcoin", accepted.path("coin").asText());
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
            assertEquals("bitcoin", saved.path("coin").asText());
            assertEquals("wallet_send", saved.path("toolName").asText());
            assertEquals("QUEUED", saved.path("state").asText());
            assertEquals("bc1qtestdestination", saved.path("toAddress").asText());
            assertEquals("0.25000000", saved.path("amountNative").asText());
        }
    }

    @Test
    void primaryDecisionStatusWithoutBearerReturnsUnauthorized() throws Exception {
        try (AgentRunningServer server = startPrimaryAgentServerWithBitcoinSecrets(
                "rpcuser=alice\nrpcpassword=secret\n",
                "wallet=main\nwallet-passphrase=pass\n"
        )) {
            HttpResponse<String> response = get(server.agentBaseUri(), "/decisions/req-unknown", Map.of());
            assertEquals(401, response.statusCode());
        }
    }

    @Test
    void primaryDecisionStatusWithBearerReturnsSnapshotForQueuedRequest() throws Exception {
        try (AgentRunningServer server = startPrimaryAgentServerWithBitcoinSecrets(
                "rpcuser=alice\nrpcpassword=secret\n",
                "wallet=main\nwallet-passphrase=pass\n"
        )) {
            String token = issueAccessToken(server);

            HttpResponse<String> acceptedResponse = postJson(
                    server.agentBaseUri(),
                    "/coins/bitcoin/actions/send",
                    """
                    {
                      \"toAddress\": \"bc1qstatusdestination\",
                      \"amountNative\": \"0.05000000\"
                    }
                    """,
                    Map.of("Authorization", "Bearer " + token)
            );
            assertEquals(202, acceptedResponse.statusCode());

            String requestId = JSON.readTree(acceptedResponse.body()).path("requestId").asText();
            HttpResponse<String> statusResponse = get(
                    server.agentBaseUri(),
                    "/decisions/" + requestId,
                    Map.of("Authorization", "Bearer " + token)
            );

            assertEquals(200, statusResponse.statusCode());
            JsonNode status = JSON.readTree(statusResponse.body());
            assertEquals(requestId, status.path("requestId").asText());
            assertEquals("bitcoin", status.path("coin").asText());
            assertEquals("QUEUED", status.path("state").asText());
            assertFalse(status.path("terminal").asBoolean());
            assertEquals(1, status.path("minApprovalsRequired").asInt());
            assertEquals(0, status.path("approvalsGranted").asInt());
            assertEquals(0, status.path("approvalsDenied").asInt());
        }
    }

    @Test
    void primaryDecisionStatusWithBearerReturnsNotFoundForUnknownRequest() throws Exception {
        try (AgentRunningServer server = startPrimaryAgentServerWithBitcoinSecrets(
                "rpcuser=alice\nrpcpassword=secret\n",
                "wallet=main\nwallet-passphrase=pass\n"
        )) {
            String token = issueAccessToken(server);

            HttpResponse<String> response = get(
                    server.agentBaseUri(),
                    "/decisions/req-does-not-exist",
                    Map.of("Authorization", "Bearer " + token)
            );
            assertEquals(404, response.statusCode());
        }
    }

    @Test
    void primaryDecisionEventsWithBearerReturnsTerminalSnapshotAndClosesStream() throws Exception {
        try (AgentRunningServer server = startPrimaryAgentServerWithBitcoinSecrets(
                "rpcuser=alice\nrpcpassword=secret\n",
                "wallet=main\nwallet-passphrase=pass\n"
        )) {
            String token = issueAccessToken(server);

            HttpResponse<String> acceptedResponse = postJson(
                    server.agentBaseUri(),
                    "/coins/bitcoin/actions/send",
                    """
                    {
                      \"toAddress\": \"bc1qeventdestination\",
                      \"amountNative\": \"0.07500000\"
                    }
                    """,
                    Map.of("Authorization", "Bearer " + token)
            );
            assertEquals(202, acceptedResponse.statusCode());

            String requestId = JSON.readTree(acceptedResponse.body()).path("requestId").asText();
            markRequestCompleted(server.dbManager().dataSource(), requestId);

            HttpResponse<String> sseResponse = getSse(
                    server.agentBaseUri(),
                    "/decisions/" + requestId + "/events",
                    Map.of("Authorization", "Bearer " + token)
            );

            assertEquals(200, sseResponse.statusCode());
            String contentType = sseResponse.headers().firstValue("content-type").orElse("");
            assertTrue(contentType.startsWith("text/event-stream"));

            String sseData = firstSseDataPayload(sseResponse.body());
            JsonNode event = JSON.readTree(sseData);
            assertEquals("snapshot", event.path("eventType").asText());
            assertEquals(requestId, event.path("requestId").asText());
            assertEquals("COMPLETED", event.path("state").asText());
            assertTrue(event.path("payload").path("terminal").asBoolean());
            assertEquals("executed", event.path("payload").path("latestReasonCode").asText());
            assertEquals("Transaction broadcast", event.path("payload").path("latestReasonText").asText());
            assertEquals("txid-abc123", event.path("payload").path("txid").asText());
        }
    }

    private AgentRunningServer startSecondaryAgentServer(String agentName) throws Exception {
        int serverPort = freePort();
        int agentPort = freePort();

        Path agentSecretFile = tempDir.resolve("secrets/" + agentName + ".secret");
        Path bitcoinDaemonSecretFile = tempDir.resolve("secrets/bitcoin-daemon.conf");
        Path bitcoinWalletSecretFile = tempDir.resolve("secrets/bitcoin-wallet.conf");
        String dbUrl = "jdbc:h2:" + tomlPath(tempDir.resolve("db-secondary-agent-" + System.nanoTime() + "/konkin"));

        Path daemonParent = bitcoinDaemonSecretFile.getParent();
        if (daemonParent != null) {
            Files.createDirectories(daemonParent);
        }

        Files.writeString(
                bitcoinDaemonSecretFile,
                "rpcuser=alice\nrpcpassword=secret\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        );
        Files.writeString(
                bitcoinWalletSecretFile,
                "wallet=main\nwallet-passphrase=pass\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        );

        String configToml = """
                config-version = 1

                [server]
                host = "127.0.0.1"
                port = %d

                [database]
                url = "%s"
                user = "konkin"
                password = "konkin"
                pool-size = 5

                [rest-api]
                enabled = false

                [web-ui]
                enabled = false

                [telegram]
                enabled = false

                [agents.secondary.%s]
                enabled = true
                bind = "127.0.0.1"
                port = %d
                secret-file = "%s"

                [coins.bitcoin]
                enabled = true

                [coins.bitcoin.secret-files]
                bitcoin-daemon-config-file = "%s"
                bitcoin-wallet-config-file = "%s"

                [coins.bitcoin.auth]
                web-ui = false
                rest-api = false
                telegram = false
                mcp-auth-channels = ["%s"]
                min-approvals-required = 1
                """.formatted(
                serverPort,
                dbUrl,
                agentName,
                agentPort,
                tomlPath(agentSecretFile),
                tomlPath(bitcoinDaemonSecretFile),
                tomlPath(bitcoinWalletSecretFile),
                agentName
        );

        Path configFile = tempDir.resolve("config-agent-%d.toml".formatted(System.nanoTime()));
        Files.writeString(configFile, configToml, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);

        KonkinConfig config = KonkinConfig.load(configFile.toString());
        DatabaseManager dbManager = new DatabaseManager(config);
        KonkinWebServer webServer = new KonkinWebServer(config, "test-version", dbManager.dataSource());
        webServer.start();

        try {
            waitForHealth(serverPort);
            waitForAgentHealth(agentPort);
        } catch (Exception e) {
            webServer.stop();
            dbManager.shutdown();
            throw e;
        }

        Properties secret = loadProperties(agentSecretFile);
        return new AgentRunningServer(
                new RunningServer(webServer, URI.create("http://127.0.0.1:" + serverPort)),
                URI.create("http://127.0.0.1:" + agentPort),
                secret.getProperty("client-id", "").trim(),
                secret.getProperty("client-secret", "").trim(),
                "",
                dbManager
        );
    }

    private AgentRunningServer startPrimaryAgentServerWithBitcoinSecrets(
            String daemonSecretContent,
            String walletSecretContent
    ) throws Exception {
        int serverPort = freePort();
        int primaryAgentPort = freePort();

        Path primarySecretFile = tempDir.resolve("secrets/agent-primary.secret");
        Path restApiSecretFile = tempDir.resolve("secrets/rest-api.secret");
        Path bitcoinDaemonSecretFile = tempDir.resolve("secrets/bitcoin-daemon.conf");
        Path bitcoinWalletSecretFile = tempDir.resolve("secrets/bitcoin-wallet.conf");
        String dbUrl = "jdbc:h2:" + tomlPath(tempDir.resolve("db-primary-agent-" + System.nanoTime() + "/konkin"));

        Path daemonParent = bitcoinDaemonSecretFile.getParent();
        if (daemonParent != null) {
            Files.createDirectories(daemonParent);
        }

        Files.writeString(
                bitcoinDaemonSecretFile,
                daemonSecretContent,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        );
        Files.writeString(
                bitcoinWalletSecretFile,
                walletSecretContent,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        );

        String configToml = """
                config-version = 1

                [server]
                host = "127.0.0.1"
                port = %d

                [database]
                url = "%s"
                user = "konkin"
                password = "konkin"
                pool-size = 5

                [rest-api]
                enabled = true
                secret-file = "%s"

                [web-ui]
                enabled = false

                [telegram]
                enabled = false

                [agents.primary]
                enabled = true
                bind = "127.0.0.1"
                port = %d
                secret-file = "%s"

                [coins.bitcoin]
                enabled = true

                [coins.bitcoin.secret-files]
                bitcoin-daemon-config-file = "%s"
                bitcoin-wallet-config-file = "%s"

                [coins.bitcoin.auth]
                web-ui = false
                rest-api = true
                telegram = false
                mcp-auth-channels = []
                min-approvals-required = 1
                """.formatted(
                serverPort,
                dbUrl,
                tomlPath(restApiSecretFile),
                primaryAgentPort,
                tomlPath(primarySecretFile),
                tomlPath(bitcoinDaemonSecretFile),
                tomlPath(bitcoinWalletSecretFile)
        );

        Path configFile = tempDir.resolve("config-primary-agent-%d.toml".formatted(System.nanoTime()));
        Files.writeString(configFile, configToml, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);

        KonkinConfig config = KonkinConfig.load(configFile.toString());
        DatabaseManager dbManager = new DatabaseManager(config);
        KonkinWebServer webServer = new KonkinWebServer(config, "test-version", dbManager.dataSource());
        webServer.start();

        try {
            waitForHealth(serverPort);
            waitForAgentHealth(primaryAgentPort);
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
                clientId,
                clientSecret,
                restApiKey,
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

    private static HttpResponse<String> postJson(URI baseUri, String path, String body, Map<String, String> headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(builder::header);
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> getSse(URI baseUri, String path, Map<String, String> headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(Duration.ofSeconds(8))
                .header("Accept", "text/event-stream")
                .GET();
        headers.forEach(builder::header);
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String firstSseDataPayload(String sseBody) {
        for (String line : sseBody.split("\\R")) {
            if (line.startsWith("data:")) {
                String payload = line.substring("data:".length()).trim();
                if (!payload.isEmpty()) {
                    return payload;
                }
            }
        }
        throw new IllegalStateException("No SSE data payload found in response body");
    }

    private static String firstSseDataPayloadForEvent(String sseBody, String eventName) {
        String currentEvent = null;
        for (String line : sseBody.split("\\R")) {
            if (line.startsWith("event:")) {
                currentEvent = line.substring("event:".length()).trim();
                continue;
            }

            if (line.startsWith("data:") && eventName.equals(currentEvent)) {
                String payload = line.substring("data:".length()).trim();
                if (!payload.isEmpty()) {
                    return payload;
                }
            }
        }
        throw new IllegalStateException("No SSE data payload found for event: " + eventName);
    }

    private static String readSseUntilEvent(BufferedReader reader, String eventName, Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        StringBuilder buffer = new StringBuilder();
        boolean targetEventSeen = false;
        boolean targetDataSeen = false;

        while (Instant.now().isBefore(deadline)) {
            String line = reader.readLine();
            if (line == null) {
                break;
            }

            buffer.append(line).append('\n');

            if (line.startsWith("event:")) {
                String currentEvent = line.substring("event:".length()).trim();
                if (eventName.equals(currentEvent)) {
                    targetEventSeen = true;
                    targetDataSeen = false;
                } else if (targetEventSeen && targetDataSeen) {
                    return buffer.toString();
                }
                continue;
            }

            if (targetEventSeen && line.startsWith("data:")) {
                targetDataSeen = true;
                continue;
            }

            if (targetEventSeen && targetDataSeen && line.isBlank()) {
                return buffer.toString();
            }
        }

        return buffer.toString();
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
            DataSource dataSource,
            String requestId,
            String coin,
            String state,
            int minApprovalsRequired,
            String toAddress,
            String amountNative
    ) {
        Instant now = Instant.now();
        JdbiFactory.create(dataSource).useHandle(h -> h.createUpdate("""
                        INSERT INTO approval_requests (
                            id, coin, tool_name, request_session_id, nonce_uuid, payload_hash_sha256, nonce_composite,
                            to_address, amount_native, fee_policy, fee_cap_native, memo,
                            requested_at, expires_at, state, state_reason_code, state_reason_text,
                            min_approvals_required, approvals_granted, approvals_denied, policy_action_at_creation,
                            created_at, updated_at, resolved_at
                        ) VALUES (
                            :id, :coin, :toolName, :requestSessionId, :nonceUuid, :payloadHashSha256, :nonceComposite,
                            :toAddress, :amountNative, :feePolicy, :feeCapNative, :memo,
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

    private static void updateApprovalRequestState(
            DataSource dataSource,
            String requestId,
            String state,
            String reasonCode,
            String reasonText
    ) {
        Instant now = Instant.now();
        JdbiFactory.create(dataSource).useHandle(h -> h.createUpdate("""
                        UPDATE approval_requests
                        SET state = :state,
                            state_reason_code = :reasonCode,
                            state_reason_text = :reasonText,
                            updated_at = :updatedAt,
                            resolved_at = :resolvedAt
                        WHERE id = :id
                        """)
                .bind("state", state)
                .bind("reasonCode", reasonCode)
                .bind("reasonText", reasonText)
                .bind("updatedAt", now)
                .bind("resolvedAt", now)
                .bind("id", requestId)
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

    private static void waitForAgentHealth(int port) throws Exception {
        URI uri = URI.create("http://127.0.0.1:" + port + "/health");
        Instant deadline = Instant.now().plusSeconds(2);

        while (Instant.now().isBefore(deadline)) {
            try {
                HttpResponse<String> response = HTTP.send(
                        HttpRequest.newBuilder(uri)
                                .timeout(Duration.ofSeconds(2))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString()
                );
                if (response.statusCode() == 200) {
                    return;
                }
            } catch (Exception ignored) {
                // endpoint still booting
            }
            Thread.sleep(50L);
        }

        throw new IllegalStateException("Agent endpoint did not become healthy in time");
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
