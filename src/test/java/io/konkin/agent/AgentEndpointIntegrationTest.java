package io.konkin.agent;

import com.fasterxml.jackson.databind.JsonNode;
import io.konkin.config.KonkinConfig;
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
                secret.getProperty("client-secret", "").trim()
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
            String clientSecret
    ) implements AutoCloseable {
        @Override
        public void close() {
            server.close();
        }
    }
}
