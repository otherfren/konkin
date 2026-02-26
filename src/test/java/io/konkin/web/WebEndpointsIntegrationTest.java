package io.konkin.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.konkin.config.KonkinConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.IOException;
import java.io.Writer;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebEndpointsIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private static final String PBKDF2_ALGO = "PBKDF2WithHmacSHA256";
    private static final int KEY_LENGTH_BITS = 256;

    @TempDir
    Path tempDir;

    @Test
    void healthEndpointReturnsHealthyStatus() throws Exception {
        try (RunningServer server = startServer(false, false, "unused", false, false, "unused")) {
            HttpResponse<String> response = get(server, "/api/v1/health", Map.of());

            assertEquals(200, response.statusCode());

            JsonNode json = JSON.readTree(response.body());
            assertEquals("healthy", json.path("status").asText());
            assertEquals("test-version", json.path("version").asText());
            assertEquals("connected", json.path("database").asText());
        }
    }

    @Test
    void authQueueWhenEnabledAndUnprotectedReturnsQueueStatus() throws Exception {
        try (RunningServer server = startServer(true, false, "unused", false, false, "unused")) {
            HttpResponse<String> response = get(server, "/api/v1/auth_queue", Map.of());

            assertEquals(200, response.statusCode());

            JsonNode json = JSON.readTree(response.body());
            assertEquals(0, json.path("pending").asInt());
            assertFalse(json.path("lockdown_active").asBoolean());
            assertTrue(json.path("message").asText().contains("Authorization queue is empty"));
        }
    }

    @Test
    void authQueueWhenDisabledReturns404() throws Exception {
        try (RunningServer server = startServer(false, false, "unused", false, false, "unused")) {
            HttpResponse<String> response = get(server, "/api/v1/auth_queue", Map.of());
            assertEquals(404, response.statusCode());
        }
    }

    @Test
    void authQueueProtectedRejectsUnauthorizedAndAcceptsValidApiKey() throws Exception {
        String password = "queue-secret";

        try (RunningServer server = startServer(true, true, password, false, false, "unused")) {
            HttpResponse<String> missingHeader = get(server, "/api/v1/auth_queue", Map.of());
            assertEquals(401, missingHeader.statusCode());

            JsonNode missingHeaderJson = JSON.readTree(missingHeader.body());
            assertEquals("unauthorized", missingHeaderJson.path("error").asText());

            HttpResponse<String> wrongHeader = get(server, "/api/v1/auth_queue", Map.of("X-Api-Key", "wrong"));
            assertEquals(401, wrongHeader.statusCode());

            HttpResponse<String> validHeader = get(server, "/api/v1/auth_queue", Map.of("X-Api-Key", password));
            assertEquals(200, validHeader.statusCode());

            JsonNode validJson = JSON.readTree(validHeader.body());
            assertEquals(0, validJson.path("pending").asInt());
        }
    }

    @Test
    void landingDisabledDoesNotExposeRootLogOrStaticAssets() throws Exception {
        try (RunningServer server = startServer(false, false, "unused", false, false, "unused")) {
            HttpResponse<String> root = get(server, "/", Map.of());
            assertEquals(404, root.statusCode());

            HttpResponse<String> logPage = get(server, "/log", Map.of());
            assertEquals(404, logPage.statusCode());

            HttpResponse<String> staticAsset = get(server, "/assets/favicon.svg", Map.of());
            assertEquals(404, staticAsset.statusCode());
        }
    }

    @Test
    void landingEnabledUnprotectedServesRootLogAndStaticAssets() throws Exception {
        try (RunningServer server = startServer(false, false, "unused", true, false, "unused")) {
            HttpResponse<String> root = get(server, "/", Map.of());
            assertEquals(200, root.statusCode());
            assertTrue(root.body().contains("KONKIN"));
            assertTrue(root.body().contains("Auth Queue"));
            assertFalse(root.body().contains("Audit Log"));

            HttpResponse<String> logPage = get(server, "/log", Map.of());
            assertEquals(200, logPage.statusCode());
            assertTrue(logPage.body().contains("KONKIN"));
            assertTrue(logPage.body().contains("Audit Log"));
            assertFalse(logPage.body().contains("Auth Queue"));
            assertTrue(logPage.body().contains("menu-active\">audit<"));

            HttpResponse<String> loginGet = get(server, "/login", Map.of());
            assertEquals(302, loginGet.statusCode());
            assertEquals("/", loginGet.headers().firstValue("location").orElse(""));

            HttpResponse<String> loginPost = postForm(server, "/login", "password=anything", Map.of());
            assertEquals(302, loginPost.statusCode());
            assertEquals("/", loginPost.headers().firstValue("location").orElse(""));

            HttpResponse<String> staticAsset = get(server, "/assets/favicon.svg", Map.of());
            assertEquals(200, staticAsset.statusCode());
            assertTrue(staticAsset.body().contains("<svg"));
        }
    }

    @Test
    void landingProtectedSupportsFullPasswordSessionFlowForRootAndLog() throws Exception {
        String landingPassword = "landing-secret";

        try (RunningServer server = startServer(false, false, "unused", true, true, landingPassword)) {
            HttpResponse<String> rootWithoutSession = get(server, "/", Map.of());
            assertEquals(200, rootWithoutSession.statusCode());
            assertTrue(rootWithoutSession.body().contains("Enter your landing password"));

            HttpResponse<String> logWithoutSession = get(server, "/log", Map.of());
            assertEquals(200, logWithoutSession.statusCode());
            assertTrue(logWithoutSession.body().contains("Enter your landing password"));

            HttpResponse<String> badLogin = postForm(server, "/login", "password=wrong", Map.of());
            assertEquals(401, badLogin.statusCode());
            assertTrue(badLogin.body().contains("Invalid password"));

            HttpResponse<String> okLogin = postForm(server, "/login", "password=" + landingPassword, Map.of());
            assertEquals(302, okLogin.statusCode());
            assertEquals("/", okLogin.headers().firstValue("location").orElse(""));

            String setCookie = okLogin.headers().firstValue("set-cookie").orElse(null);
            assertNotNull(setCookie);
            String sessionCookie = firstCookiePair(setCookie);
            assertTrue(sessionCookie.startsWith("konkin_landing_session="));

            HttpResponse<String> rootWithSession = get(server, "/", Map.of("Cookie", sessionCookie));
            assertEquals(200, rootWithSession.statusCode());
            assertTrue(rootWithSession.body().contains("logout"));
            assertTrue(rootWithSession.body().contains("Auth Queue"));
            assertFalse(rootWithSession.body().contains("Audit Log"));

            HttpResponse<String> logWithSession = get(server, "/log", Map.of("Cookie", sessionCookie));
            assertEquals(200, logWithSession.statusCode());
            assertTrue(logWithSession.body().contains("logout"));
            assertTrue(logWithSession.body().contains("Audit Log"));
            assertFalse(logWithSession.body().contains("Auth Queue"));
            assertTrue(logWithSession.body().contains("menu-active\">audit<"));

            HttpResponse<String> logout = postForm(server, "/logout", "", Map.of("Cookie", sessionCookie));
            assertEquals(302, logout.statusCode());
            assertEquals("/", logout.headers().firstValue("location").orElse(""));

            HttpResponse<String> rootAfterLogout = get(server, "/", Map.of("Cookie", sessionCookie));
            assertEquals(200, rootAfterLogout.statusCode());
            assertTrue(rootAfterLogout.body().contains("Enter your landing password"));

            HttpResponse<String> logAfterLogout = get(server, "/log", Map.of("Cookie", sessionCookie));
            assertEquals(200, logAfterLogout.statusCode());
            assertTrue(logAfterLogout.body().contains("Enter your landing password"));
        }
    }

    @Test
    void landingTemplateAutoReloadPicksUpFtlChangesWithoutRestart() throws Exception {
        Path sourceTemplates = Path.of("src/main/resources/templates").toAbsolutePath().normalize();
        Path templateDir = tempDir.resolve("templates");
        Files.createDirectories(templateDir);
        for (String templateName : List.of("layout.ftl", "landing.ftl", "landing-log.ftl", "landing-login.ftl")) {
            Files.copy(sourceTemplates.resolve(templateName), templateDir.resolve(templateName));
        }

        Path staticDir = tempDir.resolve("static");
        Files.createDirectories(staticDir);

        int port = freePort();
        String configToml = """
                config-version = 1

                [server]
                host = "127.0.0.1"
                port = %d

                [auth_queue]
                enabled = false

                [auth_queue.password-protection]
                enabled = false
                password-file = "%s"

                [landing]
                enabled = true

                [landing.password-protection]
                enabled = false
                password-file = "%s"

                [landing.template]
                directory = "%s"
                name = "landing.ftl"

                [landing.static]
                directory = "%s"
                hosted-path = "/assets"

                [landing.auto-reload]
                enabled = true
                assets-enabled = true
                """.formatted(
                port,
                tomlPath(tempDir.resolve("unused-auth-queue.password")),
                tomlPath(tempDir.resolve("unused-landing.password")),
                tomlPath(templateDir),
                tomlPath(staticDir)
        );

        Path configFile = tempDir.resolve("config-reload-%d.toml".formatted(System.nanoTime()));
        Files.writeString(configFile, configToml, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);

        KonkinConfig config = KonkinConfig.load(configFile.toString());
        KonkinWebServer server = new KonkinWebServer(config, "test-version");
        server.start();

        try (RunningServer runningServer = new RunningServer(server, URI.create("http://127.0.0.1:" + port))) {
            waitForHealth(port);

            HttpResponse<String> initial = get(runningServer, "/", Map.of());
            assertEquals(200, initial.statusCode());
            assertTrue(initial.body().contains("Auth Queue"));

            Path landingTemplate = templateDir.resolve("landing.ftl");
            String currentTemplate = Files.readString(landingTemplate, StandardCharsets.UTF_8);
            String updatedTemplate = currentTemplate.replace("Auth Queue", "Auth Queue Reloaded");
            Files.writeString(
                    landingTemplate,
                    updatedTemplate,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );

            boolean observedUpdate = waitForBodyContains(runningServer, "/", "Auth Queue Reloaded", Duration.ofSeconds(5));
            assertTrue(observedUpdate, "Template auto-reload did not apply the updated landing.ftl content");
        }
    }

    private RunningServer startServer(
            boolean authQueueEnabled,
            boolean authQueuePasswordProtected,
            String authQueuePassword,
            boolean landingEnabled,
            boolean landingPasswordProtected,
            String landingPassword
    ) throws Exception {
        int port = freePort();

        Path authQueuePasswordFile = tempDir.resolve("auth_queue.password");
        if (authQueuePasswordProtected) {
            writePasswordFile(authQueuePasswordFile, authQueuePassword);
        }

        Path landingPasswordFile = tempDir.resolve("landing.password");
        if (landingPasswordProtected) {
            writePasswordFile(landingPasswordFile, landingPassword);
        }

        Path templateDir = Path.of("src/main/resources/templates").toAbsolutePath().normalize();
        Path staticDir = Path.of("src/main/resources/static").toAbsolutePath().normalize();

        String configToml = """
                config-version = 1

                [server]
                host = "127.0.0.1"
                port = %d

                [auth_queue]
                enabled = %s

                [auth_queue.password-protection]
                enabled = %s
                password-file = "%s"

                [landing]
                enabled = %s

                [landing.password-protection]
                enabled = %s
                password-file = "%s"

                [landing.template]
                directory = "%s"
                name = "landing.ftl"

                [landing.static]
                directory = "%s"
                hosted-path = "/assets"

                [landing.auto-reload]
                enabled = false
                """.formatted(
                port,
                authQueueEnabled,
                authQueuePasswordProtected,
                tomlPath(authQueuePasswordFile),
                landingEnabled,
                landingPasswordProtected,
                tomlPath(landingPasswordFile),
                tomlPath(templateDir),
                tomlPath(staticDir)
        );

        Path configFile = tempDir.resolve("config-%d.toml".formatted(System.nanoTime()));
        Files.writeString(configFile, configToml, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);

        KonkinConfig config = KonkinConfig.load(configFile.toString());
        KonkinWebServer server = new KonkinWebServer(config, "test-version");
        server.start();

        try {
            waitForHealth(port);
        } catch (Exception e) {
            server.stop();
            throw e;
        }

        return new RunningServer(server, URI.create("http://127.0.0.1:" + port));
    }

    private static HttpResponse<String> get(RunningServer server, String path, Map<String, String> headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(server.baseUri.resolve(path))
                .timeout(Duration.ofSeconds(5))
                .GET();
        headers.forEach(builder::header);
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> postForm(RunningServer server, String path, String body, Map<String, String> headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(server.baseUri.resolve(path))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(builder::header);
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String firstCookiePair(String setCookieHeader) {
        int separator = setCookieHeader.indexOf(';');
        return separator >= 0 ? setCookieHeader.substring(0, separator) : setCookieHeader;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    private static boolean waitForBodyContains(RunningServer server, String path, String expected, Duration timeout)
            throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            try {
                HttpResponse<String> response = get(server, path, Map.of());
                if (response.statusCode() == 200 && response.body().contains(expected)) {
                    return true;
                }
            } catch (IOException ignored) {
                // retry while server settles
            }
            Thread.sleep(100L);
        }
        return false;
    }

    private static void waitForHealth(int port) throws Exception {
        URI uri = URI.create("http://127.0.0.1:" + port + "/api/v1/health");

        for (int i = 0; i < 40; i++) {
            try {
                HttpRequest request = HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofSeconds(2))
                        .GET()
                        .build();
                HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    return;
                }
            } catch (Exception ignored) {
                // server still booting
            }
            Thread.sleep(50L);
        }

        throw new IllegalStateException("Server did not become healthy in time");
    }

    private static void writePasswordFile(Path path, String cleartextPassword) throws Exception {
        byte[] salt = "konkin-test-salt".getBytes(StandardCharsets.UTF_8);
        int iterations = 1_000;
        byte[] hash = hash(cleartextPassword.toCharArray(), salt, iterations, KEY_LENGTH_BITS);

        Properties props = new Properties();
        props.setProperty("algorithm", PBKDF2_ALGO);
        props.setProperty("iterations", Integer.toString(iterations));
        props.setProperty("salt", Base64.getEncoder().encodeToString(salt));
        props.setProperty("hash", Base64.getEncoder().encodeToString(hash));

        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        try (Writer writer = Files.newBufferedWriter(
                path,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        )) {
            writer.write("# test password file\n");
            props.store(writer, null);
        }
    }

    private static byte[] hash(char[] password, byte[] salt, int iterations, int keyLengthBits) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, keyLengthBits);
        SecretKeyFactory factory = SecretKeyFactory.getInstance(PBKDF2_ALGO);
        return factory.generateSecret(spec).getEncoded();
    }

    private static String tomlPath(Path path) {
        return path.toAbsolutePath().normalize().toString().replace("\\", "\\\\");
    }

    private record RunningServer(KonkinWebServer server, URI baseUri) implements AutoCloseable {
        @Override
        public void close() {
            server.stop();
        }
    }
}
