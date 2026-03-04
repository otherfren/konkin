package io.konkin.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.konkin.TestDatabaseManager;
import io.konkin.config.KonkinConfig;
import io.konkin.db.DatabaseManager;
import io.konkin.db.JdbiFactory;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.sql.DataSource;
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
import java.util.Map;
import java.util.Properties;

public abstract class WebIntegrationTestSupport {

    protected static final ObjectMapper JSON = new ObjectMapper();
    protected static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private static final String PBKDF2_ALGO = "PBKDF2WithHmacSHA256";
    private static final int KEY_LENGTH_BITS = 256;

    @TempDir
    protected Path tempDir;

    protected Path writeBitcoinChannelValidationConfig(
            boolean webUiEnabled,
            boolean restApiEnabled,
            boolean telegramEnabled,
            boolean coinWebUi,
            boolean coinRestApi,
            boolean coinTelegram
    ) throws Exception {
        int port = freePort();

        Path daemonSecretFile = tempDir.resolve("secrets/bitcoin-daemon-%d.conf".formatted(System.nanoTime()));
        Path walletSecretFile = tempDir.resolve("secrets/bitcoin-wallet-%d.conf".formatted(System.nanoTime()));
        Path webUiPasswordFile = tempDir.resolve("unused-web-ui-%d.password".formatted(System.nanoTime()));

        String configToml = """
                config-version = 1

                [server]
                host = "127.0.0.1"
                port = %d

                [web-ui]
                enabled = %s

                [web-ui.password-protection]
                enabled = false
                password-file = "%s"

                [rest-api]
                enabled = %s

                [telegram]
                enabled = %s

                [coins.bitcoin]
                enabled = true

                [coins.bitcoin.secret-files]
                bitcoin-daemon-config-file = "%s"
                bitcoin-wallet-config-file = "%s"

                [coins.bitcoin.auth]
                web-ui = %s
                rest-api = %s
                telegram = %s
                mcp = "btc-main"
                """.formatted(
                port,
                webUiEnabled,
                tomlPath(webUiPasswordFile),
                restApiEnabled,
                telegramEnabled,
                tomlPath(daemonSecretFile),
                tomlPath(walletSecretFile),
                coinWebUi,
                coinRestApi,
                coinTelegram
        );

        Path configFile = tempDir.resolve("config-bitcoin-channel-validation-%d.toml".formatted(System.nanoTime()));
        Files.writeString(configFile, configToml, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        return configFile;
    }

    protected RunningServer startServer(
            boolean landingEnabled,
            boolean landingPasswordProtected,
            String landingPassword
    ) throws Exception {
        return startServer(tempDir, landingEnabled, landingPasswordProtected, landingPassword);
    }

    protected static RunningServer startServer(
            Path workDir,
            boolean landingEnabled,
            boolean landingPasswordProtected,
            String landingPassword
    ) throws Exception {
        int port = freePort();

        Path landingPasswordFile = workDir.resolve("landing.password");
        if (landingPasswordProtected) {
            writePasswordFile(landingPasswordFile, landingPassword);
        }

        Path templateDir = Path.of("src/main/resources/templates").toAbsolutePath().normalize();
        Path staticDir = Path.of("src/main/resources/static").toAbsolutePath().normalize();

        String dbUrl = "jdbc:h2:mem:konkin-test;DB_CLOSE_DELAY=-1";

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
                dbUrl,
                landingEnabled,
                landingPasswordProtected,
                tomlPath(landingPasswordFile),
                tomlPath(templateDir),
                tomlPath(staticDir)
        );

        Path configFile = workDir.resolve("config-%d.toml".formatted(System.nanoTime()));
        Files.writeString(configFile, configToml, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);

        KonkinConfig config = KonkinConfig.load(configFile.toString());
        DatabaseManager dbManager = new DatabaseManager(TestDatabaseManager.dataSource());
        KonkinWebServer server = new KonkinWebServer(config, "test-version", dbManager.dataSource());
        server.start();

        try {
            waitForHealth(port);
        } catch (Exception e) {
            server.stop();
            dbManager.shutdown();
            throw e;
        }

        return new RunningServer(server, URI.create("http://127.0.0.1:" + port), dbManager);
    }

    protected static HttpResponse<String> get(RunningServer server, String path, Map<String, String> headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(server.baseUri.resolve(path))
                .timeout(Duration.ofSeconds(5))
                .GET();
        headers.forEach(builder::header);
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    protected static HttpResponse<String> postForm(RunningServer server, String path, String body, Map<String, String> headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(server.baseUri.resolve(path))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(builder::header);
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    protected static String firstCookiePair(String setCookieHeader) {
        int separator = setCookieHeader.indexOf(';');
        return separator >= 0 ? setCookieHeader.substring(0, separator) : setCookieHeader;
    }

    protected static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    protected static boolean waitForBodyContains(RunningServer server, String path, String expected, Duration timeout)
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

    protected static void insertApprovalRequest(DataSource dataSource, String requestId, String nonceComposite, String state) {
        Instant now = Instant.now();
        JdbiFactory.create(dataSource).useHandle(h ->
                h.createUpdate("""
                        INSERT INTO approval_requests (
                            id, coin, tool_name, nonce_uuid, payload_hash_sha256, nonce_composite,
                            requested_at, expires_at, state, min_approvals_required
                        ) VALUES (:id, :coin, :tool, :nonceUuid, :sha256, :nonce, :requestedAt, :expiresAt, :state, :minApprovals)
                        """)
                        .bind("id", requestId)
                        .bind("coin", "bitcoin")
                        .bind("tool", "bitcoin_send")
                        .bind("nonceUuid", "nonce-uuid-" + requestId)
                        .bind("sha256", "sha256-" + requestId)
                        .bind("nonce", nonceComposite)
                        .bind("requestedAt", now)
                        .bind("expiresAt", now.plusSeconds(600))
                        .bind("state", state)
                        .bind("minApprovals", 1)
                        .execute()
        );
    }

    protected static void updateApprovalRequestTimes(DataSource dataSource, String requestId, Instant requestedAt, Instant expiresAt) {
        JdbiFactory.create(dataSource).useHandle(h ->
                h.createUpdate("UPDATE approval_requests SET requested_at = :requestedAt, expires_at = :expiresAt WHERE id = :id")
                        .bind("requestedAt", requestedAt)
                        .bind("expiresAt", expiresAt)
                        .bind("id", requestId)
                        .execute()
        );
    }

    protected static void insertCoinLockdown(DataSource dataSource, String coin, Instant lockdownUntil) {
        JdbiFactory.create(dataSource).useHandle(h ->
                h.createUpdate("""
                        INSERT INTO approval_coin_runtime (coin, active_request_id, cooldown_until, lockdown_until)
                        VALUES (:coin, NULL, NULL, :lockdownUntil)
                        """)
                        .bind("coin", coin)
                        .bind("lockdownUntil", lockdownUntil)
                        .execute()
        );
    }

    protected static void insertApprovalChannel(DataSource dataSource, String channelId, String channelType) {
        JdbiFactory.create(dataSource).useHandle(h ->
                h.createUpdate("""
                        INSERT INTO approval_channels (id, channel_type, display_name, enabled, config_fingerprint)
                        VALUES (:id, :type, :name, :enabled, :fingerprint)
                        """)
                        .bind("id", channelId)
                        .bind("type", channelType)
                        .bind("name", channelId)
                        .bind("enabled", true)
                        .bind("fingerprint", "test-fingerprint")
                        .execute()
        );
    }

    protected static void insertApprovalVote(DataSource dataSource, String requestId, String channelId, String decision) {
        JdbiFactory.create(dataSource).useHandle(h ->
                h.createUpdate("""
                        INSERT INTO approval_votes (request_id, channel_id, decision, decision_reason, decided_by)
                        VALUES (:requestId, :channelId, :decision, :reason, :decidedBy)
                        """)
                        .bind("requestId", requestId)
                        .bind("channelId", channelId)
                        .bind("decision", decision)
                        .bind("reason", "integration-test")
                        .bind("decidedBy", "test-actor")
                        .execute()
        );
    }

    protected static void cleanDatabase(DataSource dataSource) {
        JdbiFactory.create(dataSource).useHandle(h -> {
            h.execute("DELETE FROM approval_execution_attempts");
            h.execute("DELETE FROM approval_votes");
            h.execute("DELETE FROM approval_request_channels");
            h.execute("DELETE FROM approval_state_transitions");
            h.execute("DELETE FROM approval_coin_runtime");
            h.execute("DELETE FROM approval_requests");
            h.execute("DELETE FROM approval_channels");
            h.execute("DELETE FROM agent_tokens");
        });
    }

    protected static void waitForHealth(int port) throws Exception {
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

    protected static void writePasswordFile(Path path, String cleartextPassword) throws Exception {
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

    protected static String tomlPath(Path path) {
        return path.toAbsolutePath().normalize().toString().replace("\\", "\\\\");
    }

    protected record RunningServer(KonkinWebServer server, URI baseUri, DatabaseManager dbManager) implements AutoCloseable {
        public RunningServer(KonkinWebServer server, URI baseUri) {
            this(server, baseUri, null);
        }

        @Override
        public void close() {
            server.stop();
            if (dbManager != null) {
                dbManager.shutdown();
            }
        }
    }
}
