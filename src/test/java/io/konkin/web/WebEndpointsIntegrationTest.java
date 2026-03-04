package io.konkin.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import io.konkin.TestDatabaseManager;
import io.konkin.config.KonkinConfig;
import io.konkin.db.DatabaseManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebEndpointsIntegrationTest extends WebIntegrationTestSupport {

    @TempDir
    static Path sharedTempDir;
    private static RunningServer sharedServer;

    @BeforeAll
    static void startSharedServer() throws Exception {
        sharedServer = startServer(sharedTempDir, false, false, "unused");
    }

    @AfterAll
    static void stopSharedServer() {
        if (sharedServer != null) sharedServer.close();
    }

    @BeforeEach
    void cleanDb() {
        if (sharedServer != null && sharedServer.dbManager() != null) {
            cleanDatabase(sharedServer.dbManager().dataSource());
        }
    }

    @Test
    void restApiExposesRequestsAndSerializesInstantCorrectly() throws Exception {
        int port = freePort();
        Path restApiSecretFile = tempDir.resolve("rest-api-instant.password");
        Files.writeString(restApiSecretFile, "api-key=test-api-key-instant", StandardCharsets.UTF_8);

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
                [rest-api]
                enabled = true
                secret-file = "%s"
                [landing]
                enabled = true
                [landing.template]
                directory = "%s"
                [landing.static]
                directory = "%s"
                """.formatted(port, dbUrl, tomlPath(restApiSecretFile), tomlPath(templateDir), tomlPath(staticDir));

        KonkinConfig config = KonkinConfig.load(configFile(configToml));
        DatabaseManager dbManager = new DatabaseManager(TestDatabaseManager.dataSource());
        KonkinWebServer konkinServer = new KonkinWebServer(config, "test-version", dbManager.dataSource());
        konkinServer.start();

        try (RunningServer server = new RunningServer(konkinServer, URI.create("http://127.0.0.1:" + port), dbManager)) {
            insertApprovalRequest(server.dbManager().dataSource(), "req-instant", "nonce-instant", "PENDING");

            HttpResponse<String> response = get(server, "/api/v1/requests/req-instant", Map.of("X-API-Key", "test-api-key-instant"));
            assertEquals(200, response.statusCode());

            JsonNode json = JSON.readTree(response.body());
            assertEquals("req-instant", json.path("id").asText());
            
            // requestedAt should be present and not null. Jackson with JavaTimeModule 
            // serializes Instant as ISO-8601 string by default (or timestamp if configured, 
            // but the important thing is that it doesn't fail).
            assertTrue(json.has("requestedAt"));
            assertDoesNotThrow(() -> Instant.parse(json.path("requestedAt").asText()));
        }
    }

    private String configFile(String toml) throws IOException {
        Path path = tempDir.resolve("config-test-" + System.nanoTime() + ".toml");
        Files.writeString(path, toml);
        return path.toString();
    }

    @Test
    void healthEndpointReturnsHealthyStatus() throws Exception {
        HttpResponse<String> response = get(sharedServer, "/api/v1/health", Map.of());

        assertEquals(200, response.statusCode());

        JsonNode json = JSON.readTree(response.body());
        assertEquals("healthy", json.path("status").asText());
        assertEquals("test-version", json.path("version").asText());
        assertEquals("connected", json.path("database").asText());
    }

    @Test
    void approvalRequestsRejectDuplicateNonceComposite() throws Exception {
        DataSource dataSource = sharedServer.dbManager().dataSource();

        insertApprovalRequest(dataSource, "req-1", "nonce-duplicate", "PENDING");

        assertThrows(
                UnableToExecuteStatementException.class,
                () -> insertApprovalRequest(dataSource, "req-2", "nonce-duplicate", "PENDING")
        );
    }

    @Test
    void approvalVotesRejectDuplicateVotePerRequestAndChannel() throws Exception {
        DataSource dataSource = sharedServer.dbManager().dataSource();

        insertApprovalRequest(dataSource, "req-votes", "nonce-votes", "PENDING");
        insertApprovalChannel(dataSource, "telegram.main", "telegram");
        insertApprovalVote(dataSource, "req-votes", "telegram.main", "approve");

        assertThrows(
                UnableToExecuteStatementException.class,
                () -> insertApprovalVote(dataSource, "req-votes", "telegram.main", "deny")
        );
    }

    @Test
    void landingDisabledDoesNotExposeRootLogOrStaticAssets() throws Exception {
        HttpResponse<String> root = get(sharedServer, "/", Map.of());
        assertEquals(404, root.statusCode());

        HttpResponse<String> logPage = get(sharedServer, "/log", Map.of());
        assertEquals(404, logPage.statusCode());

        HttpResponse<String> authDefinitions = get(sharedServer, "/wallets", Map.of());
        assertEquals(404, authDefinitions.statusCode());

        HttpResponse<String> authChannels = get(sharedServer, "/auth_channels", Map.of());
        assertEquals(404, authChannels.statusCode());

        HttpResponse<String> driverAgent = get(sharedServer, "/driver_agent", Map.of());
        assertEquals(404, driverAgent.statusCode());

        HttpResponse<String> coinsPage = get(sharedServer, "/coins", Map.of());
        assertEquals(404, coinsPage.statusCode());

        HttpResponse<String> staticAsset = get(sharedServer, "/assets/favicon.svg", Map.of());
        assertEquals(404, staticAsset.statusCode());
    }

    @Test
    void webUiConfigSectionEnablesLandingRoutes() throws Exception {
        int port = freePort();

        Path templateDir = Path.of("src/main/resources/templates").toAbsolutePath().normalize();
        Path staticDir = Path.of("src/main/resources/static").toAbsolutePath().normalize();

        Path webUiPasswordFile = tempDir.resolve("unused-web-ui.password");

        String configToml = """
                config-version = 1

                [server]
                host = "127.0.0.1"
                port = %d

                [web-ui]
                enabled = true

                [web-ui.password-protection]
                enabled = false
                password-file = "%s"

                [web-ui.template]
                directory = "%s"
                name = "landing.ftl"

                [web-ui.static]
                directory = "%s"
                hosted-path = "/assets"

                [web-ui.auto-reload]
                enabled = false
                assets-enabled = false
                """.formatted(
                port,
                tomlPath(webUiPasswordFile),
                tomlPath(templateDir),
                tomlPath(staticDir)
        );

        Path configFile = tempDir.resolve("config-web-ui-%d.toml".formatted(System.nanoTime()));
        Files.writeString(configFile, configToml, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);

        KonkinConfig config = KonkinConfig.load(configFile.toString());
        assertTrue(config.landingEnabled());
        assertEquals(tomlPath(webUiPasswordFile), config.landingPasswordFile());

        KonkinWebServer server = new KonkinWebServer(config, "test-version");
        server.start();

        try (RunningServer runningServer = new RunningServer(server, URI.create("http://127.0.0.1:" + port))) {
            waitForHealth(port);

            HttpResponse<String> root = get(runningServer, "/", Map.of());
            assertEquals(200, root.statusCode());
            assertTrue(root.body().contains("KONKIN"));

            HttpResponse<String> authDefinitions = get(runningServer, "/wallets", Map.of());
            assertEquals(200, authDefinitions.statusCode());
            assertTrue(authDefinitions.body().contains("Wallets"));
            // litecoin/monero not configured (enabled=false) in this test config, so they should not appear
            assertFalse(authDefinitions.body().contains("LITECOIN"));
            assertFalse(authDefinitions.body().contains("MONERO"));
            assertFalse(authDefinitions.body().contains("/assets/img/litecoin.svg"));
            assertFalse(authDefinitions.body().contains("/assets/img/monero.svg"));

            HttpResponse<String> authChannelsPage = get(runningServer, "/auth_channels", Map.of());
            assertEquals(200, authChannelsPage.statusCode());
            assertTrue(authChannelsPage.body().contains("Auth Channels"));
            assertTrue(authChannelsPage.body().contains("Web UI Channel"));
            assertTrue(authChannelsPage.body().contains("REST API Channel"));
            assertTrue(authChannelsPage.body().contains("Auth Agent Bot Channels"));

            HttpResponse<String> driverAgentPage = get(runningServer, "/driver_agent", Map.of());
            assertEquals(200, driverAgentPage.statusCode());
            assertTrue(driverAgentPage.body().contains("Driver Agent"));
            assertTrue(driverAgentPage.body().contains("Driver Agent Endpoint"));
            assertTrue(driverAgentPage.body().contains("Auth Method"));
            assertTrue(driverAgentPage.body().contains("MCP Registration"));
            assertTrue(driverAgentPage.body().contains("No driver agent configured."));
            assertTrue(driverAgentPage.body().contains("Enable the driver agent to render ready-to-run token and MCP registration commands."));

            HttpResponse<String> coinsPage = get(runningServer, "/coins", Map.of());
            assertEquals(404, coinsPage.statusCode());
        }
    }

    @Test
    void bitcoinContradictoryAuthCriteriaRefusesStartupAtConfigLoad() throws Exception {
        int port = freePort();

        Path daemonSecretFile = tempDir.resolve("secrets/bitcoin-daemon.conf");
        Path walletSecretFile = tempDir.resolve("secrets/bitcoin-wallet.conf");

        String configToml = """
                config-version = 1

                [server]
                host = "127.0.0.1"
                port = %d

                [web-ui]
                enabled = true

                [coins.bitcoin]
                enabled = true

                [coins.bitcoin.secret-files]
                bitcoin-daemon-config-file = "%s"
                bitcoin-wallet-config-file = "%s"

                [coins.bitcoin.auth]
                web-ui = true
                rest-api = false
                telegram = false
                mcp = "btc-main"

                [[coins.bitcoin.auth.auto-accept]]
                [coins.bitcoin.auth.auto-accept.criteria]
                type = "value-lt"
                value = 1.0

                [[coins.bitcoin.auth.auto-deny]]
                [coins.bitcoin.auth.auto-deny.criteria]
                type = "value-gt"
                value = 0.5
                """.formatted(
                port,
                tomlPath(daemonSecretFile),
                tomlPath(walletSecretFile)
        );

        Path configFile = tempDir.resolve("config-bitcoin-contradiction-%d.toml".formatted(System.nanoTime()));
        Files.writeString(configFile, configToml, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> KonkinConfig.load(configFile.toString())
        );
        assertTrue(exception.getMessage().contains("contradictory auth criteria for coin 'bitcoin'"));
    }

    @Test
    void bitcoinAuthWebUiChannelRequiresWebUiEnabledAtConfigLoad() throws Exception {
        Path configFile = writeBitcoinChannelValidationConfig(
                false,
                true,
                false,
                true,
                false,
                false
        );

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> KonkinConfig.load(configFile.toString())
        );
        assertTrue(exception.getMessage().contains("coins.bitcoin.auth.web-ui=true requires web-ui.enabled=true"));
    }

    @Test
    void bitcoinAuthTelegramChannelRequiresTelegramEnabledAtConfigLoad() throws Exception {
        Path configFile = writeBitcoinChannelValidationConfig(
                false,
                true,
                false,
                false,
                false,
                true
        );

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> KonkinConfig.load(configFile.toString())
        );
        assertTrue(exception.getMessage().contains("coins.bitcoin.auth.telegram=true requires telegram.enabled=true"));
    }

    @Test
    void bitcoinAuthChannelsPassWhenAllRequiredGlobalChannelsAreEnabled() throws Exception {
        Path configFile = writeBitcoinChannelValidationConfig(
                true,
                true,
                true,
                true,
                true,
                true
        );

        assertDoesNotThrow(() -> KonkinConfig.load(configFile.toString()));
    }

    @Test
    void mcpAuthChannelReferenceMustExistInSecondaryAgentsAtConfigLoad() throws Exception {
        int port = freePort();

        Path daemonSecretFile = tempDir.resolve("secrets/bitcoin-daemon-mcp-ref.conf");
        Path walletSecretFile = tempDir.resolve("secrets/bitcoin-wallet-mcp-ref.conf");

        String configToml = """
                config-version = 1

                [server]
                host = "127.0.0.1"
                port = %d

                [agents.secondary.agent-default]
                enabled = true
                bind = "127.0.0.1"
                port = 9560
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
                mcp-auth-channels = ["agent-missing"]
                """.formatted(
                port,
                tomlPath(tempDir.resolve("secrets/agent-default-mcp-ref.secret")),
                tomlPath(daemonSecretFile),
                tomlPath(walletSecretFile)
        );

        Path configFile = tempDir.resolve("config-mcp-ref-missing-%d.toml".formatted(System.nanoTime()));
        Files.writeString(configFile, configToml, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> KonkinConfig.load(configFile.toString())
        );
        assertTrue(exception.getMessage().contains("mcp-auth-channel 'agent-missing' references undefined agent"));
    }

    @Test
    void agentPortsMustBeUniqueAcrossServerAndAgentsAtConfigLoad() throws Exception {
        int port = freePort();

        String configToml = """
                config-version = 1

                [server]
                host = "127.0.0.1"
                port = %d

                [agents.primary]
                enabled = true
                bind = "127.0.0.1"
                port = %d
                secret-file = "%s"
                """.formatted(
                port,
                port,
                tomlPath(tempDir.resolve("secrets/agent-primary-duplicate-port.secret"))
        );

        Path configFile = tempDir.resolve("config-agent-duplicate-port-%d.toml".formatted(System.nanoTime()));
        Files.writeString(configFile, configToml, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> KonkinConfig.load(configFile.toString())
        );
        assertTrue(exception.getMessage().contains("used by both server and agent 'driver'"));
    }

    @Test
    void validAgentsConfigLoadsAndBootstrapsAgentSecretsAtConfigLoad() throws Exception {
        int port = freePort();

        Path primarySecretFile = tempDir.resolve("secrets/agent-primary-valid.secret");
        Path defaultAgentSecretFile = tempDir.resolve("secrets/agent-default-valid.secret");
        Path backupAgentSecretFile = tempDir.resolve("secrets/agent-backup-valid.secret");
        Path daemonSecretFile = tempDir.resolve("secrets/bitcoin-daemon-valid-agents.conf");
        Path walletSecretFile = tempDir.resolve("secrets/bitcoin-wallet-valid-agents.conf");

        String configToml = """
                config-version = 1

                [server]
                host = "127.0.0.1"
                port = %d

                [agents.primary]
                enabled = true
                bind = "127.0.0.1"
                port = 9550
                secret-file = "%s"

                [agents.secondary.agent-default]
                enabled = true
                bind = "127.0.0.1"
                port = 9560
                secret-file = "%s"

                [agents.secondary.agent-backup]
                enabled = true
                bind = "127.0.0.1"
                port = 9561
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
                mcp-auth-channels = ["agent-default", "agent-backup"]
                """.formatted(
                port,
                tomlPath(primarySecretFile),
                tomlPath(defaultAgentSecretFile),
                tomlPath(backupAgentSecretFile),
                tomlPath(daemonSecretFile),
                tomlPath(walletSecretFile)
        );

        Path configFile = tempDir.resolve("config-agents-valid-%d.toml".formatted(System.nanoTime()));
        Files.writeString(configFile, configToml, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);

        KonkinConfig config = KonkinConfig.load(configFile.toString());

        assertNotNull(config.primaryAgent());
        assertEquals(9550, config.primaryAgent().port());
        assertEquals(2, config.secondaryAgents().size());
        assertNotNull(config.secondaryAgent("agent-default"));
        assertNotNull(config.secondaryAgent("agent-backup"));

        assertTrue(Files.exists(primarySecretFile));
        assertTrue(Files.exists(defaultAgentSecretFile));
        assertTrue(Files.exists(backupAgentSecretFile));

        Properties primarySecretProps = new Properties();
        try (var reader = Files.newBufferedReader(primarySecretFile, StandardCharsets.UTF_8)) {
            primarySecretProps.load(reader);
        }
        assertEquals("konkin-primary", primarySecretProps.getProperty("client-id"));
        assertEquals(64, primarySecretProps.getProperty("client-secret", "").trim().length());

        Properties defaultSecretProps = new Properties();
        try (var reader = Files.newBufferedReader(defaultAgentSecretFile, StandardCharsets.UTF_8)) {
            defaultSecretProps.load(reader);
        }
        assertEquals("agent-default", defaultSecretProps.getProperty("client-id"));
        assertEquals(64, defaultSecretProps.getProperty("client-secret", "").trim().length());

        Properties backupSecretProps = new Properties();
        try (var reader = Files.newBufferedReader(backupAgentSecretFile, StandardCharsets.UTF_8)) {
            backupSecretProps.load(reader);
        }
        assertEquals("agent-backup", backupSecretProps.getProperty("client-id"));
        assertEquals(64, backupSecretProps.getProperty("client-secret", "").trim().length());
    }

    @Test
    void humanFriendlyDurationsAreAcceptedAtConfigLoad() throws Exception {
        int port = freePort();

        Path daemonSecretFile = tempDir.resolve("secrets/bitcoin-daemon-human.conf");
        Path walletSecretFile = tempDir.resolve("secrets/bitcoin-wallet-human.conf");

        String configToml = """
                config-version = 1

                [server]
                host = "127.0.0.1"
                port = %d

                [web-ui]
                enabled = true

                [coins.bitcoin]
                enabled = true

                [coins.bitcoin.secret-files]
                bitcoin-daemon-config-file = "%s"
                bitcoin-wallet-config-file = "%s"

                [coins.bitcoin.auth]
                web-ui = true
                rest-api = false
                telegram = false
                mcp = "btc-main"

                [[coins.bitcoin.auth.auto-accept]]
                [coins.bitcoin.auth.auto-accept.criteria]
                type = "cumulated-value-lt"
                value = 0.6
                period = "7d 2h"

                [[coins.bitcoin.auth.auto-deny]]
                [coins.bitcoin.auth.auto-deny.criteria]
                type = "cumulated-value-gt"
                value = 2.0
                period = "7 days and 2 hours"
                """.formatted(
                port,
                tomlPath(daemonSecretFile),
                tomlPath(walletSecretFile)
        );

        Path configFile = tempDir.resolve("config-bitcoin-human-duration-%d.toml".formatted(System.nanoTime()));
        Files.writeString(configFile, configToml, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);

        assertDoesNotThrow(() -> KonkinConfig.load(configFile.toString()));
    }

    @Test
    void telegramAutoDenyTimeoutDefaultsToFiveMinutesWhenMissing() throws Exception {
        int port = freePort();

        String configToml = """
                config-version = 1

                [server]
                host = "127.0.0.1"
                port = %d
                """.formatted(port);

        KonkinConfig config = KonkinConfig.load(configFile(configToml));
        assertEquals(Duration.ofMinutes(5), config.telegramAutoDenyTimeout());
    }

    @Test
    void telegramAutoDenyTimeoutSupportsHumanFriendlyDurationAtConfigLoad() throws Exception {
        int port = freePort();

        String configToml = """
                config-version = 1

                [server]
                host = "127.0.0.1"
                port = %d

                [telegram]
                auto-deny-timeout = "3m"
                """.formatted(port);

        KonkinConfig config = KonkinConfig.load(configFile(configToml));
        assertEquals(Duration.ofMinutes(3), config.telegramAutoDenyTimeout());
    }

    @Test
    void testDummyCoinIsUnavailableWhenDebugModeIsDisabledAtConfigLoad() throws Exception {
        int port = freePort();

        String configToml = """
                config-version = 1

                [server]
                host = "127.0.0.1"
                port = %d

                [debug]
                enabled = false

                [coins.testdummycoin]
                enabled = true
                """.formatted(port);

        KonkinConfig config = KonkinConfig.load(configFile(configToml));
        assertFalse(config.debugEnabled());
        assertFalse(config.testDummyCoin().enabled());
        assertEquals(List.of(), config.testDummyCoin().auth().mcpAuthChannels());
    }

    @Test
    void testDummyCoinCanBeEnabledWhenDebugModeIsEnabledAtConfigLoad() throws Exception {
        int port = freePort();

        String configToml = """
                config-version = 1

                [server]
                host = "127.0.0.1"
                port = %d

                [debug]
                enabled = true

                [coins.testdummycoin]
                enabled = true

                [coins.testdummycoin.auth]
                web-ui = false
                rest-api = false
                telegram = false
                mcp = "tdc-main"
                mcp-auth-channels = ["tdc-main"]
                min-approvals-required = 1
                """.formatted(port);

        KonkinConfig config = KonkinConfig.load(configFile(configToml));
        assertTrue(config.debugEnabled());
        assertTrue(config.testDummyCoin().enabled());
        assertEquals("tdc-main", config.testDummyCoin().auth().mcp());
        assertEquals(List.of("tdc-main"), config.testDummyCoin().auth().mcpAuthChannels());
    }

    @Test
    void bitcoinAuthMinApprovalsRequiredMustBePositiveAtConfigLoad() throws Exception {
        int port = freePort();

        Path daemonSecretFile = tempDir.resolve("secrets/bitcoin-daemon-min-approvals-positive.conf");
        Path walletSecretFile = tempDir.resolve("secrets/bitcoin-wallet-min-approvals-positive.conf");

        String configToml = """
                config-version = 1

                [server]
                host = "127.0.0.1"
                port = %d

                [coins.bitcoin]
                enabled = true

                [coins.bitcoin.secret-files]
                bitcoin-daemon-config-file = "%s"
                bitcoin-wallet-config-file = "%s"

                [coins.bitcoin.auth]
                web-ui = false
                rest-api = false
                telegram = false
                mcp = "btc-main"
                min-approvals-required = 0
                """.formatted(
                port,
                tomlPath(daemonSecretFile),
                tomlPath(walletSecretFile)
        );

        Path configFile = tempDir.resolve("config-bitcoin-min-approvals-positive-%d.toml".formatted(System.nanoTime()));
        Files.writeString(configFile, configToml, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> KonkinConfig.load(configFile.toString())
        );
        assertTrue(exception.getMessage().contains("coins.bitcoin.auth.min-approvals-required must be > 0"));
    }

    @Test
    void bitcoinAuthMinApprovalsRequiredCannotExceedConfiguredChannelCountAtConfigLoad() throws Exception {
        int port = freePort();

        Path daemonSecretFile = tempDir.resolve("secrets/bitcoin-daemon-min-approvals-max.conf");
        Path walletSecretFile = tempDir.resolve("secrets/bitcoin-wallet-min-approvals-max.conf");

        String configToml = """
                config-version = 1

                [server]
                host = "127.0.0.1"
                port = %d

                [coins.bitcoin]
                enabled = true

                [coins.bitcoin.secret-files]
                bitcoin-daemon-config-file = "%s"
                bitcoin-wallet-config-file = "%s"

                [coins.bitcoin.auth]
                web-ui = false
                rest-api = false
                telegram = false
                mcp = "btc-main"
                min-approvals-required = 2
                """.formatted(
                port,
                tomlPath(daemonSecretFile),
                tomlPath(walletSecretFile)
        );

        Path configFile = tempDir.resolve("config-bitcoin-min-approvals-max-%d.toml".formatted(System.nanoTime()));
        Files.writeString(configFile, configToml, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> KonkinConfig.load(configFile.toString())
        );
        assertTrue(exception.getMessage().contains("coins.bitcoin.auth.min-approvals-required=2 exceeds configured auth channels=1"));
    }

    @Test
    void bitcoinAuthVetoChannelsMustReferenceEnabledChannelsAtConfigLoad() throws Exception {
        int port = freePort();

        Path daemonSecretFile = tempDir.resolve("secrets/bitcoin-daemon-veto-invalid.conf");
        Path walletSecretFile = tempDir.resolve("secrets/bitcoin-wallet-veto-invalid.conf");

        String configToml = """
                config-version = 1

                [server]
                host = "127.0.0.1"
                port = %d

                [web-ui]
                enabled = true

                [coins.bitcoin]
                enabled = true

                [coins.bitcoin.secret-files]
                bitcoin-daemon-config-file = "%s"
                bitcoin-wallet-config-file = "%s"

                [coins.bitcoin.auth]
                web-ui = true
                rest-api = false
                telegram = false
                mcp = "btc-main"
                veto-channels = ["telegram"]
                """.formatted(
                port,
                tomlPath(daemonSecretFile),
                tomlPath(walletSecretFile)
        );

        Path configFile = tempDir.resolve("config-bitcoin-veto-invalid-%d.toml".formatted(System.nanoTime()));
        Files.writeString(configFile, configToml, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> KonkinConfig.load(configFile.toString())
        );
        assertTrue(exception.getMessage().contains("coins.bitcoin.auth.veto-channels contains 'telegram' which is not an enabled auth channel"));
    }

    @Test
    void bitcoinAuthQuorumAndVetoUseCaseConfigLoadsSuccessfully() throws Exception {
        int port = freePort();

        Path daemonSecretFile = tempDir.resolve("secrets/bitcoin-daemon-quorum-veto.conf");
        Path walletSecretFile = tempDir.resolve("secrets/bitcoin-wallet-quorum-veto.conf");
        Path telegramSecretFile = tempDir.resolve("secrets/telegram-quorum-veto.secret");

        String configToml = """
                config-version = 1

                [server]
                host = "127.0.0.1"
                port = %d

                [telegram]
                enabled = true
                secret-file = "%s"
                auto-deny-timeout = "3m"

                [agents.secondary.agent-a]
                enabled = true
                bind = "127.0.0.1"
                port = 9560
                secret-file = "%s"

                [agents.secondary.agent-b]
                enabled = true
                bind = "127.0.0.1"
                port = 9561
                secret-file = "%s"

                [coins.bitcoin]
                enabled = true

                [coins.bitcoin.secret-files]
                bitcoin-daemon-config-file = "%s"
                bitcoin-wallet-config-file = "%s"

                [coins.bitcoin.auth]
                web-ui = false
                rest-api = false
                telegram = true
                mcp-auth-channels = ["agent-a", "agent-b"]
                min-approvals-required = 2
                veto-channels = ["telegram", "agent-a"]
                """.formatted(
                port,
                tomlPath(telegramSecretFile),
                tomlPath(tempDir.resolve("secrets/agent-a-quorum-veto.secret")),
                tomlPath(tempDir.resolve("secrets/agent-b-quorum-veto.secret")),
                tomlPath(daemonSecretFile),
                tomlPath(walletSecretFile)
        );

        Path configFile = tempDir.resolve("config-bitcoin-quorum-veto-valid-%d.toml".formatted(System.nanoTime()));
        Files.writeString(configFile, configToml, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);

        assertDoesNotThrow(() -> KonkinConfig.load(configFile.toString()));
    }

    @Test
    void authDefinitionsPageShowsTimeoutQuorumAndVetoValues() throws Exception {
        int port = freePort();

        Path templateDir = Path.of("src/main/resources/templates").toAbsolutePath().normalize();
        Path staticDir = Path.of("src/main/resources/static").toAbsolutePath().normalize();
        Path webUiPasswordFile = tempDir.resolve("unused-web-ui-auth-defs-timeout-quorum.password");
        Path daemonSecretFile = tempDir.resolve("secrets/bitcoin-daemon-auth-defs-timeout-quorum.conf");
        Path walletSecretFile = tempDir.resolve("secrets/bitcoin-wallet-auth-defs-timeout-quorum.conf");
        Path telegramSecretFile = tempDir.resolve("secrets/telegram-auth-defs-timeout-quorum.secret");

        String dbUrl = "jdbc:h2:mem:konkin-test;DB_CLOSE_DELAY=-1";

        Path telegramSecretDir = telegramSecretFile.getParent();
        if (telegramSecretDir != null) {
            Files.createDirectories(telegramSecretDir);
        }
        Files.writeString(
                telegramSecretFile,
                "bot-token=test-bot-token\nchat-ids=\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW
        );

        String configToml = """
                config-version = 1

                [server]
                host = "127.0.0.1"
                port = %d

                [database]
                url = "%s"

                [web-ui]
                enabled = true

                [web-ui.password-protection]
                enabled = false
                password-file = "%s"

                [web-ui.template]
                directory = "%s"
                name = "landing.ftl"

                [web-ui.static]
                directory = "%s"
                hosted-path = "/assets"

                [web-ui.auto-reload]
                enabled = false
                assets-enabled = false

                [telegram]
                enabled = true
                secret-file = "%s"
                auto-deny-timeout = "3m"

                [agents.secondary.agent-a]
                enabled = true
                bind = "127.0.0.1"
                port = 9562
                secret-file = "%s"

                [agents.secondary.agent-b]
                enabled = true
                bind = "127.0.0.1"
                port = 9563
                secret-file = "%s"

                [coins.bitcoin]
                enabled = true

                [coins.bitcoin.secret-files]
                bitcoin-daemon-config-file = "%s"
                bitcoin-wallet-config-file = "%s"

                [coins.bitcoin.auth]
                web-ui = false
                rest-api = false
                telegram = true
                mcp-auth-channels = ["agent-a", "agent-b"]
                min-approvals-required = 2
                veto-channels = ["telegram"]
                """.formatted(
                port,
                dbUrl,
                tomlPath(webUiPasswordFile),
                tomlPath(templateDir),
                tomlPath(staticDir),
                tomlPath(telegramSecretFile),
                tomlPath(tempDir.resolve("secrets/agent-a-auth-defs-timeout-quorum.secret")),
                tomlPath(tempDir.resolve("secrets/agent-b-auth-defs-timeout-quorum.secret")),
                tomlPath(daemonSecretFile),
                tomlPath(walletSecretFile)
        );

        Path configFile = tempDir.resolve("config-auth-defs-timeout-quorum-veto-%d.toml".formatted(System.nanoTime()));
        Files.writeString(configFile, configToml, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);

        KonkinConfig config = KonkinConfig.load(configFile.toString());
        DatabaseManager dbManager = new DatabaseManager(TestDatabaseManager.dataSource());
        KonkinWebServer server = new KonkinWebServer(config, "test-version", dbManager.dataSource());
        server.start();

        try (RunningServer runningServer = new RunningServer(server, URI.create("http://127.0.0.1:" + port), dbManager)) {
            waitForHealth(port);
            HttpResponse<String> authDefinitions = get(runningServer, "/wallets", Map.of());
            assertEquals(200, authDefinitions.statusCode());
            assertTrue(authDefinitions.body().contains("Auth channel configured"));
            assertTrue(authDefinitions.body().contains("verification-agent:agent-a"));
            assertTrue(authDefinitions.body().contains("verification-agent:agent-b"));
            assertTrue(authDefinitions.body().contains("2-of-N"));
            assertTrue(authDefinitions.body().contains("Veto channels"));
            assertTrue(authDefinitions.body().contains("telegram"));
            assertTrue(authDefinitions.body().contains("agent-a @ http://127.0.0.1:9562"));
            assertTrue(authDefinitions.body().contains("agent-b @ http://127.0.0.1:9563"));
        }
    }

    @Test
    void authDefinitionsPageShowsFriendlyDurationWindow() throws Exception {
        int port = freePort();

        Path templateDir = Path.of("src/main/resources/templates").toAbsolutePath().normalize();
        Path staticDir = Path.of("src/main/resources/static").toAbsolutePath().normalize();
        Path webUiPasswordFile = tempDir.resolve("unused-web-ui-auth-defs.password");
        Path daemonSecretFile = tempDir.resolve("secrets/bitcoin-daemon-auth-defs.conf");
        Path walletSecretFile = tempDir.resolve("secrets/bitcoin-wallet-auth-defs.conf");

        String configToml = """
                config-version = 1

                [server]
                host = "127.0.0.1"
                port = %d

                [web-ui]
                enabled = true

                [web-ui.password-protection]
                enabled = false
                password-file = "%s"

                [web-ui.template]
                directory = "%s"
                name = "landing.ftl"

                [web-ui.static]
                directory = "%s"
                hosted-path = "/assets"

                [web-ui.auto-reload]
                enabled = false
                assets-enabled = false

                [agents.secondary.btc-main]
                enabled = true
                bind = "127.0.0.1"
                port = 9564
                secret-file = "%s"

                [agents.secondary.btc-backup]
                enabled = true
                bind = "127.0.0.1"
                port = 9565
                secret-file = "%s"

                [coins.bitcoin]
                enabled = true

                [coins.bitcoin.secret-files]
                bitcoin-daemon-config-file = "%s"
                bitcoin-wallet-config-file = "%s"

                [coins.bitcoin.auth]
                web-ui = true
                rest-api = false
                telegram = false
                mcp-auth-channels = ["btc-main", "btc-backup"]

                [[coins.bitcoin.auth.auto-deny]]
                [coins.bitcoin.auth.auto-deny.criteria]
                type = "cumulated-value-gt"
                value = 2.0
                period = "7 days and 2 hours"
                """.formatted(
                port,
                tomlPath(webUiPasswordFile),
                tomlPath(templateDir),
                tomlPath(staticDir),
                tomlPath(tempDir.resolve("secrets/btc-main-auth-defs.secret")),
                tomlPath(tempDir.resolve("secrets/btc-backup-auth-defs.secret")),
                tomlPath(daemonSecretFile),
                tomlPath(walletSecretFile)
        );

        Path configFile = tempDir.resolve("config-auth-defs-friendly-window-%d.toml".formatted(System.nanoTime()));
        Files.writeString(configFile, configToml, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);

        KonkinConfig config = KonkinConfig.load(configFile.toString());
        KonkinWebServer server = new KonkinWebServer(config, "test-version");
        server.start();

        try (RunningServer runningServer = new RunningServer(server, URI.create("http://127.0.0.1:" + port))) {
            waitForHealth(port);
            HttpResponse<String> authDefinitions = get(runningServer, "/wallets", Map.of());
            assertEquals(200, authDefinitions.statusCode());
            assertTrue(authDefinitions.body().contains("7d 2h"));
            assertTrue(authDefinitions.body().contains("sum in window >"));
            assertTrue(authDefinitions.body().contains("Time window"));
            // litecoin/monero not configured (enabled=false) in this test config, so they should not appear
            assertFalse(authDefinitions.body().contains("LITECOIN"));
            assertFalse(authDefinitions.body().contains("MONERO"));
            assertTrue(authDefinitions.body().contains("/assets/img/bitcoin.svg"));
            assertFalse(authDefinitions.body().contains("/assets/img/litecoin.svg"));
            assertFalse(authDefinitions.body().contains("/assets/img/monero.svg"));
            assertTrue(authDefinitions.body().contains("Auth channels"));
            assertTrue(authDefinitions.body().contains("btc-main @ http://127.0.0.1:9564"));
            assertTrue(authDefinitions.body().contains("btc-backup @ http://127.0.0.1:9565"));
            assertTrue(authDefinitions.body().contains(">***<"));
            assertTrue(authDefinitions.body().contains("aria-label=\"Reveal wallet balance\""));
        }
    }

    @Test
    void authDefinitionsPageShowsRestApiAndEnabledSecondaryAgentChannels() throws Exception {
        int port = freePort();
        int enabledSecondaryAgentPort = freePort();
        int disabledSecondaryAgentPort = freePort();

        Path templateDir = Path.of("src/main/resources/templates").toAbsolutePath().normalize();
        Path staticDir = Path.of("src/main/resources/static").toAbsolutePath().normalize();
        Path webUiPasswordFile = tempDir.resolve("unused-web-ui-auth-defs-rest.password");
        Path restApiSecretFile = tempDir.resolve("secrets/rest-api-auth-defs.secret");
        Path daemonSecretFile = tempDir.resolve("secrets/bitcoin-daemon-auth-defs-rest.conf");
        Path walletSecretFile = tempDir.resolve("secrets/bitcoin-wallet-auth-defs-rest.conf");

        String configToml = """
                config-version = 1

                [server]
                host = "127.0.0.1"
                port = %d

                [web-ui]
                enabled = true

                [web-ui.password-protection]
                enabled = false
                password-file = "%s"

                [web-ui.template]
                directory = "%s"
                name = "landing.ftl"

                [web-ui.static]
                directory = "%s"
                hosted-path = "/assets"

                [web-ui.auto-reload]
                enabled = false
                assets-enabled = false

                [rest-api]
                enabled = true
                secret-file = "%s"

                [agents.secondary.agent-enabled]
                enabled = true
                bind = "127.0.0.1"
                port = %d
                secret-file = "%s"

                [agents.secondary.agent-disabled]
                enabled = false
                bind = "127.0.0.1"
                port = %d
                secret-file = "%s"

                [coins.bitcoin]
                enabled = true

                [coins.bitcoin.secret-files]
                bitcoin-daemon-config-file = "%s"
                bitcoin-wallet-config-file = "%s"

                [coins.bitcoin.auth]
                web-ui = true
                rest-api = true
                telegram = false
                """.formatted(
                port,
                tomlPath(webUiPasswordFile),
                tomlPath(templateDir),
                tomlPath(staticDir),
                tomlPath(restApiSecretFile),
                enabledSecondaryAgentPort,
                tomlPath(tempDir.resolve("secrets/agent-enabled-auth-defs.secret")),
                disabledSecondaryAgentPort,
                tomlPath(tempDir.resolve("secrets/agent-disabled-auth-defs.secret")),
                tomlPath(daemonSecretFile),
                tomlPath(walletSecretFile)
        );

        Path configFile = tempDir.resolve("config-auth-defs-rest-and-secondary-%d.toml".formatted(System.nanoTime()));
        Files.writeString(configFile, configToml, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);

        KonkinConfig config = KonkinConfig.load(configFile.toString());
        KonkinWebServer server = new KonkinWebServer(config, "test-version");
        server.start();

        try (RunningServer runningServer = new RunningServer(server, URI.create("http://127.0.0.1:" + port))) {
            waitForHealth(port);
            HttpResponse<String> authDefinitions = get(runningServer, "/wallets", Map.of());
            assertEquals(200, authDefinitions.statusCode());
            assertTrue(authDefinitions.body().contains("Auth channel configured"));
            assertTrue(authDefinitions.body().contains("web-ui: <strong>enabled</strong>"));
            assertTrue(authDefinitions.body().contains("rest-api: <strong>enabled</strong>"));
            assertTrue(authDefinitions.body().contains("telegram: <strong>disabled</strong>"));
            assertTrue(authDefinitions.body().contains("verification-agent:agent-enabled"));
            assertFalse(authDefinitions.body().contains("verification-agent:agent-disabled"));
        }
    }

    @Test
    void authChannelsAndDriverAgentPagesShowConfiguredChannelsAndMaskedTelegramIdentifiers() throws Exception {
        int port = freePort();
        int primaryAgentPort = freePort();
        int secondaryAgentPort = freePort();

        Path templateDir = Path.of("src/main/resources/templates").toAbsolutePath().normalize();
        Path staticDir = Path.of("src/main/resources/static").toAbsolutePath().normalize();
        Path webUiPasswordFile = tempDir.resolve("unused-web-ui-auth-channels.password");
        Path telegramSecretFile = tempDir.resolve("secrets/telegram-auth-channels.secret");
        Path primarySecretFile = tempDir.resolve("secrets/agent-primary-auth-channels.secret");
        Path secondarySecretFile = tempDir.resolve("secrets/agent-a-auth-channels.secret");

        Path telegramSecretDir = telegramSecretFile.getParent();
        if (telegramSecretDir != null) {
            Files.createDirectories(telegramSecretDir);
        }
        Files.writeString(
                telegramSecretFile,
                "bot-token=test-bot-token\nchat-ids=-1004005006\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW
        );

        String configToml = """
                config-version = 1

                [server]
                host = "127.0.0.1"
                port = %d

                [web-ui]
                enabled = true

                [web-ui.password-protection]
                enabled = false
                password-file = "%s"

                [web-ui.template]
                directory = "%s"
                name = "landing.ftl"

                [web-ui.static]
                directory = "%s"
                hosted-path = "/assets"

                [web-ui.auto-reload]
                enabled = false
                assets-enabled = false

                [telegram]
                enabled = true
                secret-file = "%s"

                [agents.primary]
                enabled = true
                bind = "127.0.0.1"
                port = %d
                secret-file = "%s"

                [agents.secondary.agent-a]
                enabled = true
                bind = "127.0.0.1"
                port = %d
                secret-file = "%s"
                """.formatted(
                port,
                tomlPath(webUiPasswordFile),
                tomlPath(templateDir),
                tomlPath(staticDir),
                tomlPath(telegramSecretFile),
                primaryAgentPort,
                tomlPath(primarySecretFile),
                secondaryAgentPort,
                tomlPath(secondarySecretFile)
        );

        Path configFile = tempDir.resolve("config-auth-channels-page-%d.toml".formatted(System.nanoTime()));
        Files.writeString(configFile, configToml, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);

        KonkinConfig config = KonkinConfig.load(configFile.toString());
        KonkinWebServer server = new KonkinWebServer(config, "test-version");
        server.start();

        try (RunningServer runningServer = new RunningServer(server, URI.create("http://127.0.0.1:" + port))) {
            waitForHealth(port);
            HttpResponse<String> authChannels = get(runningServer, "/auth_channels", Map.of());
            assertEquals(200, authChannels.statusCode());
            assertTrue(authChannels.body().contains("Auth Channels"));
            assertTrue(authChannels.body().contains("Web UI Channel"));
            assertTrue(authChannels.body().contains("REST API Channel"));
            assertTrue(authChannels.body().contains("Telegram Connected Users"));
            assertTrue(authChannels.body().contains("Auth Agent Bot Channels"));
            assertFalse(authChannels.body().contains("Driver Agent Endpoint"));
            assertTrue(authChannels.body().contains("<span class=\"menu-active\">auth channels</span>"));
            assertTrue(authChannels.body().contains("data-secret-value=\"-1004005006\""));
            assertTrue(authChannels.body().contains(">***<"));
            assertTrue(authChannels.body().contains("aria-label=\"Reveal Telegram identifier\""));
            assertTrue(authChannels.body().contains("approved"));
            assertTrue(authChannels.body().contains("<th>Type</th>"));
            assertTrue(authChannels.body().contains("<th>Name</th>"));
            assertTrue(authChannels.body().contains("<th>Title</th>"));
            assertTrue(authChannels.body().contains("<th>Auth Channel ID</th>"));
            assertTrue(authChannels.body().contains("verification-agent:agent-a"));
            assertTrue(authChannels.body().contains("telegram.secret"));
            assertTrue(authChannels.body().contains("mcp-auth-channels"));
            assertTrue(authChannels.body().contains("Reference format"));
            assertTrue(authChannels.body().contains("Runtime checks"));
            assertTrue(authChannels.body().contains("http://127.0.0.1:" + secondaryAgentPort + "/health"));
            assertTrue(authChannels.body().contains("http://127.0.0.1:" + secondaryAgentPort + "/oauth/token"));

            HttpResponse<String> driverAgent = get(runningServer, "/driver_agent", Map.of());
            assertEquals(200, driverAgent.statusCode());
            assertTrue(driverAgent.body().contains("Driver Agent"));
            assertTrue(driverAgent.body().contains("Driver Agent Endpoint"));
            assertTrue(driverAgent.body().contains("Auth Method"));
            assertTrue(driverAgent.body().contains("MCP Registration"));
            assertTrue(driverAgent.body().contains("<span class=\"menu-active\">driver agent</span>"));
            assertTrue(driverAgent.body().contains("http://127.0.0.1:" + primaryAgentPort + "/health"));
            assertTrue(driverAgent.body().contains("http://127.0.0.1:" + primaryAgentPort + "/oauth/token"));
            assertTrue(driverAgent.body().contains("http://127.0.0.1:" + primaryAgentPort + "/sse"));
            assertTrue(driverAgent.body().contains("konkin-primary"));
            assertTrue(driverAgent.body().contains("claude mcp add --transport sse"));
            assertTrue(driverAgent.body().contains("claude mcp list"));
            assertTrue(driverAgent.body().contains("documents/SKILL-driver-agent.md"));
        }
    }

    @Test
    void coinsPageShowsMaskedBalanceWithRevealButton() throws Exception {
        int port = freePort();

        Path templateDir = Path.of("src/main/resources/templates").toAbsolutePath().normalize();
        Path staticDir = Path.of("src/main/resources/static").toAbsolutePath().normalize();
        Path webUiPasswordFile = tempDir.resolve("unused-web-ui-coins.password");
        Path daemonSecretFile = tempDir.resolve("secrets/bitcoin-daemon-coins.conf");
        Path walletSecretFile = tempDir.resolve("secrets/bitcoin-wallet-coins.conf");

        String configToml = """
                config-version = 1

                [server]
                host = "127.0.0.1"
                port = %d

                [web-ui]
                enabled = true

                [web-ui.password-protection]
                enabled = false
                password-file = "%s"

                [web-ui.template]
                directory = "%s"
                name = "landing.ftl"

                [web-ui.static]
                directory = "%s"
                hosted-path = "/assets"

                [web-ui.auto-reload]
                enabled = false
                assets-enabled = false

                [agents.secondary.btc-main]
                enabled = true
                bind = "127.0.0.1"
                port = 9566
                secret-file = "%s"

                [agents.secondary.btc-backup]
                enabled = true
                bind = "127.0.0.1"
                port = 9567
                secret-file = "%s"

                [coins.bitcoin]
                enabled = true

                [coins.bitcoin.secret-files]
                bitcoin-daemon-config-file = "%s"
                bitcoin-wallet-config-file = "%s"

                [coins.bitcoin.auth]
                web-ui = true
                rest-api = false
                telegram = false
                mcp-auth-channels = ["btc-main", "btc-backup"]
                """.formatted(
                port,
                tomlPath(webUiPasswordFile),
                tomlPath(templateDir),
                tomlPath(staticDir),
                tomlPath(tempDir.resolve("secrets/btc-main-coins.secret")),
                tomlPath(tempDir.resolve("secrets/btc-backup-coins.secret")),
                tomlPath(daemonSecretFile),
                tomlPath(walletSecretFile)
        );

        Path configFile = tempDir.resolve("config-coins-page-%d.toml".formatted(System.nanoTime()));
        Files.writeString(configFile, configToml, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);

        KonkinConfig config = KonkinConfig.load(configFile.toString());
        KonkinWebServer server = new KonkinWebServer(config, "test-version");
        server.start();

        try (RunningServer runningServer = new RunningServer(server, URI.create("http://127.0.0.1:" + port))) {
            waitForHealth(port);
            HttpResponse<String> coinsPage = get(runningServer, "/coins", Map.of());
            assertEquals(404, coinsPage.statusCode());
        }
    }

    @Test
    void bitcoinAuthInvalidMultipleChannelDependenciesFailsFastOnFirstViolation() throws Exception {
        Path configFile = writeBitcoinChannelValidationConfig(
                false,
                false,
                false,
                true,
                true,
                true
        );

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> KonkinConfig.load(configFile.toString())
        );
        assertTrue(exception.getMessage().contains("coins.bitcoin.auth.web-ui=true requires web-ui.enabled=true"));
    }

    @Test
    void bitcoinAuthRestApiChannelRequiresRestApiEnabledAtConfigLoad() throws Exception {
        Path configFile = writeBitcoinChannelValidationConfig(
                true,
                false,
                false,
                false,
                true,
                false
        );

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> KonkinConfig.load(configFile.toString())
        );
        assertTrue(exception.getMessage().contains("coins.bitcoin.auth.rest-api=true requires rest-api.enabled=true"));
    }

    @Test
    void restApiSecretFileIsGeneratedWithApiKeyWhenMissing() throws Exception {
        int port = freePort();
        Path restApiSecretFile = tempDir.resolve("secrets/rest-api-generated.secret");

        String configToml = """
                config-version = 1

                [server]
                host = "127.0.0.1"
                port = %d

                [rest-api]
                enabled = true
                secret-file = "%s"
                """.formatted(port, tomlPath(restApiSecretFile));

        Path configFile = tempDir.resolve("config-rest-api-secret-generation-%d.toml".formatted(System.nanoTime()));
        Files.writeString(configFile, configToml, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);

        KonkinConfig.load(configFile.toString());

        assertTrue(Files.exists(restApiSecretFile));

        Properties secretProps = new Properties();
        try (var reader = Files.newBufferedReader(restApiSecretFile, StandardCharsets.UTF_8)) {
            secretProps.load(reader);
        }

        String apiKey = secretProps.getProperty("api-key", "").trim();
        assertTrue(!apiKey.isEmpty());
    }

    @Test
    void restApiProtectedRoutesRejectMissingOrWrongApiKey() throws Exception {
        int port = freePort();
        Path restApiSecretFile = tempDir.resolve("secrets/rest-api-protected.secret");

        String configToml = """
                config-version = 1

                [server]
                host = "127.0.0.1"
                port = %d

                [database]
                url = "jdbc:h2:%s"
                user = "konkin"
                password = "konkin"
                pool-size = 5

                [rest-api]
                enabled = true
                secret-file = "%s"

                [web-ui]
                enabled = false
                """.formatted(
                port,
                "mem:konkin-test;DB_CLOSE_DELAY=-1",
                tomlPath(restApiSecretFile)
        );

        Path configFile = tempDir.resolve("config-rest-api-protected-%d.toml".formatted(System.nanoTime()));
        Files.writeString(configFile, configToml, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);

        KonkinConfig config = KonkinConfig.load(configFile.toString());

        Properties secretProps = new Properties();
        try (var reader = Files.newBufferedReader(restApiSecretFile, StandardCharsets.UTF_8)) {
            secretProps.load(reader);
        }
        String correctApiKey = secretProps.getProperty("api-key", "").trim();
        assertTrue(!correctApiKey.isEmpty());

        DatabaseManager dbManager = new DatabaseManager(TestDatabaseManager.dataSource());
        KonkinWebServer server = new KonkinWebServer(config, "test-version", dbManager.dataSource());
        server.start();

        try (RunningServer runningServer = new RunningServer(server, URI.create("http://127.0.0.1:" + port), dbManager)) {
            waitForHealth(port);

            HttpResponse<String> missingKey = get(runningServer, "/api/v1/protected-probe", Map.of());
            assertEquals(401, missingKey.statusCode());

            HttpResponse<String> wrongKey = get(runningServer, "/api/v1/protected-probe", Map.of("X-API-Key", "wrong-key"));
            assertEquals(401, wrongKey.statusCode());
        }
    }

    @Test
    void restApiProtectedRoutesAllowCorrectApiKeyAndHealthWithoutKey() throws Exception {
        int port = freePort();
        Path restApiSecretFile = tempDir.resolve("secrets/rest-api-allowed.secret");

        String configToml = """
                config-version = 1

                [server]
                host = "127.0.0.1"
                port = %d

                [database]
                url = "jdbc:h2:%s"
                user = "konkin"
                password = "konkin"
                pool-size = 5

                [rest-api]
                enabled = true
                secret-file = "%s"

                [web-ui]
                enabled = false
                """.formatted(
                port,
                "mem:konkin-test;DB_CLOSE_DELAY=-1",
                tomlPath(restApiSecretFile)
        );

        Path configFile = tempDir.resolve("config-rest-api-allowed-%d.toml".formatted(System.nanoTime()));
        Files.writeString(configFile, configToml, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);

        KonkinConfig config = KonkinConfig.load(configFile.toString());

        Properties secretProps = new Properties();
        try (var reader = Files.newBufferedReader(restApiSecretFile, StandardCharsets.UTF_8)) {
            secretProps.load(reader);
        }
        String correctApiKey = secretProps.getProperty("api-key", "").trim();
        assertTrue(!correctApiKey.isEmpty());

        DatabaseManager dbManager = new DatabaseManager(TestDatabaseManager.dataSource());
        KonkinWebServer server = new KonkinWebServer(config, "test-version", dbManager.dataSource());
        server.start();

        try (RunningServer runningServer = new RunningServer(server, URI.create("http://127.0.0.1:" + port), dbManager)) {
            waitForHealth(port);

            HttpResponse<String> allowedProbe = get(runningServer, "/api/v1/protected-probe", Map.of("X-API-Key", correctApiKey));
            assertEquals(404, allowedProbe.statusCode());

            HttpResponse<String> healthWithoutKey = get(runningServer, "/api/v1/health", Map.of());
            assertEquals(200, healthWithoutKey.statusCode());
        }
    }
}
