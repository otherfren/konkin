package io.konkin.agent;

import com.fasterxml.jackson.databind.JsonNode;
import io.konkin.config.KonkinConfig;
import io.konkin.db.DatabaseManager;
import io.konkin.web.KonkinWebServer;
import io.konkin.web.WebIntegrationTestSupport;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Properties;

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
    void voteWithValidBearerTokenReturnsAccepted() throws Exception {
        try (AgentRunningServer server = startSecondaryAgentServer("agent-alpha")) {
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

    private AgentRunningServer startSecondaryAgentServer(String agentName) throws Exception {
        int serverPort = freePort();
        int agentPort = freePort();

        Path agentSecretFile = tempDir.resolve("secrets/" + agentName + ".secret");

        String configToml = """
                config-version = 1

                [server]
                host = "127.0.0.1"
                port = %d

                [agents.secondary.%s]
                enabled = true
                bind = "127.0.0.1"
                port = %d
                secret-file = "%s"
                """.formatted(
                serverPort,
                agentName,
                agentPort,
                tomlPath(agentSecretFile)
        );

        Path configFile = tempDir.resolve("config-agent-%d.toml".formatted(System.nanoTime()));
        Files.writeString(configFile, configToml, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);

        KonkinConfig config = KonkinConfig.load(configFile.toString());
        KonkinWebServer webServer = new KonkinWebServer(config, "test-version");
        webServer.start();

        try {
            waitForHealth(serverPort);
            waitForAgentHealth(agentPort);
        } catch (Exception e) {
            webServer.stop();
            throw e;
        }

        Properties secret = loadProperties(agentSecretFile);
        return new AgentRunningServer(
                new RunningServer(webServer, URI.create("http://127.0.0.1:" + serverPort)),
                URI.create("http://127.0.0.1:" + agentPort),
                secret.getProperty("client-id", "").trim(),
                secret.getProperty("client-secret", "").trim(),
                "",
                null
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
