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

package io.konkin.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import io.konkin.TestConfigBuilder;
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
        sharedServer = startServer(sharedTempDir, false, false, "unused", "web-endpoints-test");
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

        String dbUrl = "jdbc:h2:mem:web-endpoints-test;DB_CLOSE_DELAY=-1";

        String configToml = TestConfigBuilder.create(port)
                .withDatabase(dbUrl)
                .withRestApiSecret(restApiSecretFile)
                .withLanding(true)
                .withLandingTemplate(templateDir)
                .withLandingStatic(staticDir)
                .build();

        KonkinConfig config = KonkinConfig.load(configFile(configToml));
        DatabaseManager dbManager = new DatabaseManager(TestDatabaseManager.dataSource("web-endpoints-test"));
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

        HttpResponse<String> logPage = get(sharedServer, "/history", Map.of());
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

        String configToml = TestConfigBuilder.create(port)
                .withWebUi(true)
                .withWebUiPasswordProtection(false, webUiPasswordFile)
                .withWebUiTemplate(templateDir)
                .withWebUiStatic(staticDir)
                .withAutoReload(false)
                .build();

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

            // No coins configured — /wallets renders overview with all coins (including disabled)
            HttpResponse<String> authDefinitions = get(runningServer, "/wallets", Map.of());
            assertEquals(200, authDefinitions.statusCode());
            assertTrue(authDefinitions.body().contains("Wallets"));
            assertTrue(authDefinitions.body().contains("BITCOIN"));
            assertTrue(authDefinitions.body().contains("LITECOIN"));
            assertTrue(authDefinitions.body().contains("MONERO"));
            assertTrue(authDefinitions.body().contains("disabled"));

            HttpResponse<String> authChannelsPage = get(runningServer, "/auth_channels", Map.of());
            assertEquals(200, authChannelsPage.statusCode());
            assertTrue(authChannelsPage.body().contains("Auth Channels"));
            assertTrue(authChannelsPage.body().contains("Auth Agent Bot Channels"));

            HttpResponse<String> authChannelWebUiPage = get(runningServer, "/auth_channels/web-ui", Map.of());
            assertEquals(200, authChannelWebUiPage.statusCode());
            assertTrue(authChannelWebUiPage.body().contains("Web UI Channel"));

            HttpResponse<String> authChannelApiKeysPage = get(runningServer, "/auth_channels/api_keys", Map.of());
            assertEquals(200, authChannelApiKeysPage.statusCode());
            assertTrue(authChannelApiKeysPage.body().contains("REST API Channel"));

            HttpResponse<String> driverAgentPage = get(runningServer, "/driver_agent", Map.of());
            assertEquals(200, driverAgentPage.statusCode());
            assertTrue(driverAgentPage.body().contains("Driver Agent"));
            assertTrue(driverAgentPage.body().contains("Driver Agent Endpoint"));
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

        String configToml = TestConfigBuilder.create(port)
                .withWebUi(true)
                .withBitcoin(daemonSecretFile, walletSecretFile)
                .withBitcoinAuthMcp(true, false, false, "btc-main")
                .withBitcoinAutoAcceptRule("value-lt", 1.0)
                .withBitcoinAutoDenyRule("value-gt", 0.5)
                .build();

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

        String configToml = TestConfigBuilder.create(port)
                .withSecondaryAgent("agent-default", true, "127.0.0.1", 9560,
                        tempDir.resolve("secrets/agent-default-mcp-ref.secret"))
                .withBitcoin(daemonSecretFile, walletSecretFile)
                .withBitcoinAuthWithMcpAuthChannels(false, false, false, List.of("agent-missing"))
                .build();

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

        String configToml = TestConfigBuilder.create(port)
                .withPrimaryAgent(true, "127.0.0.1", port,
                        tempDir.resolve("secrets/agent-primary-duplicate-port.secret"))
                .build();

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

        String configToml = TestConfigBuilder.create(port)
                .withPrimaryAgent(true, "127.0.0.1", 9550, primarySecretFile)
                .withSecondaryAgent("agent-default", true, "127.0.0.1", 9560, defaultAgentSecretFile)
                .withSecondaryAgent("agent-backup", true, "127.0.0.1", 9561, backupAgentSecretFile)
                .withBitcoin(daemonSecretFile, walletSecretFile)
                .withBitcoinAuthWithMcpAuthChannels(false, false, false, List.of("agent-default", "agent-backup"))
                .build();

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

        String configToml = TestConfigBuilder.create(port)
                .withWebUi(true)
                .withBitcoin(daemonSecretFile, walletSecretFile)
                .withBitcoinAuthMcp(true, false, false, "btc-main")
                .withBitcoinAutoAcceptRule("cumulated-value-lt", 0.6, "7d 2h")
                .withBitcoinAutoDenyRule("cumulated-value-gt", 2.0, "7 days and 2 hours")
                .build();

        Path configFile = tempDir.resolve("config-bitcoin-human-duration-%d.toml".formatted(System.nanoTime()));
        Files.writeString(configFile, configToml, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);

        assertDoesNotThrow(() -> KonkinConfig.load(configFile.toString()));
    }

    @Test
    void telegramAutoDenyTimeoutDefaultsToFiveMinutesWhenMissing() throws Exception {
        int port = freePort();

        String configToml = TestConfigBuilder.create(port).build();

        KonkinConfig config = KonkinConfig.load(configFile(configToml));
        assertEquals(Duration.ofMinutes(5), config.telegramAutoDenyTimeout());
    }

    @Test
    void telegramAutoDenyTimeoutSupportsHumanFriendlyDurationAtConfigLoad() throws Exception {
        int port = freePort();

        String configToml = TestConfigBuilder.create(port)
                .withTelegramAutoDenyTimeout("3m")
                .build();

        KonkinConfig config = KonkinConfig.load(configFile(configToml));
        assertEquals(Duration.ofMinutes(3), config.telegramAutoDenyTimeout());
    }

    @Test
    void testDummyCoinIsUnavailableWhenDebugModeIsDisabledAtConfigLoad() throws Exception {
        int port = freePort();

        String configToml = TestConfigBuilder.create(port)
                .withDebug(false)
                .withTestDummyCoin(true)
                .build();

        KonkinConfig config = KonkinConfig.load(configFile(configToml));
        assertFalse(config.debugEnabled());
        assertFalse(config.testDummyCoin().enabled());
        assertEquals(List.of(), config.testDummyCoin().auth().mcpAuthChannels());
    }

    @Test
    void testDummyCoinCanBeEnabledWhenDebugModeIsEnabledAtConfigLoad() throws Exception {
        int port = freePort();

        String configToml = TestConfigBuilder.create(port)
                .withDebug(true)
                .withTestDummyCoin(true)
                .withTestDummyCoinAuth(false, false, false, "tdc-main", List.of("tdc-main"), 1)
                .build();

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

        String configToml = TestConfigBuilder.create(port)
                .withBitcoin(daemonSecretFile, walletSecretFile)
                .withBitcoinAuthMinApprovals(false, false, false, "btc-main", 0)
                .build();

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

        String configToml = TestConfigBuilder.create(port)
                .withBitcoin(daemonSecretFile, walletSecretFile)
                .withBitcoinAuthMinApprovals(false, false, false, "btc-main", 2)
                .build();

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

        String configToml = TestConfigBuilder.create(port)
                .withWebUi(true)
                .withBitcoin(daemonSecretFile, walletSecretFile)
                .withBitcoinAuthVetoChannels(true, false, false, "btc-main", "telegram")
                .build();

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

        String configToml = TestConfigBuilder.create(port)
                .withTelegramFull(telegramSecretFile, "3m")
                .withSecondaryAgent("agent-a", true, "127.0.0.1", 9560,
                        tempDir.resolve("secrets/agent-a-quorum-veto.secret"))
                .withSecondaryAgent("agent-b", true, "127.0.0.1", 9561,
                        tempDir.resolve("secrets/agent-b-quorum-veto.secret"))
                .withBitcoin(daemonSecretFile, walletSecretFile)
                .withBitcoinAuthWithMcpChannelsAndQuorum(false, false, true,
                        List.of("agent-a", "agent-b"), 2, List.of("telegram", "agent-a"))
                .build();

        Path configFile = tempDir.resolve("config-bitcoin-quorum-veto-valid-%d.toml".formatted(System.nanoTime()));
        Files.writeString(configFile, configToml, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);

        assertDoesNotThrow(() -> KonkinConfig.load(configFile.toString()));
    }

    @Test
    void authDefinitionsPageShowsTimeoutQuorumAndVetoValues() throws Exception {
        int port = freePort();
        int agentAPort = freePort();
        int agentBPort = freePort();

        Path templateDir = Path.of("src/main/resources/templates").toAbsolutePath().normalize();
        Path staticDir = Path.of("src/main/resources/static").toAbsolutePath().normalize();
        Path webUiPasswordFile = tempDir.resolve("unused-web-ui-auth-defs-timeout-quorum.password");
        Path daemonSecretFile = tempDir.resolve("secrets/bitcoin-daemon-auth-defs-timeout-quorum.conf");
        Path walletSecretFile = tempDir.resolve("secrets/bitcoin-wallet-auth-defs-timeout-quorum.conf");
        Path telegramSecretFile = tempDir.resolve("secrets/telegram-auth-defs-timeout-quorum.secret");

        String dbUrl = "jdbc:h2:mem:web-endpoints-test;DB_CLOSE_DELAY=-1";

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

        String configToml = TestConfigBuilder.create(port)
                .withDatabase(dbUrl)
                .withWebUi(true)
                .withWebUiPasswordProtection(false, webUiPasswordFile)
                .withWebUiTemplate(templateDir)
                .withWebUiStatic(staticDir)
                .withAutoReload(false)
                .withTelegramFull(telegramSecretFile, "3m")
                .withSecondaryAgent("agent-a", true, "127.0.0.1", agentAPort,
                        tempDir.resolve("secrets/agent-a-auth-defs-timeout-quorum.secret"))
                .withSecondaryAgent("agent-b", true, "127.0.0.1", agentBPort,
                        tempDir.resolve("secrets/agent-b-auth-defs-timeout-quorum.secret"))
                .withBitcoin(daemonSecretFile, walletSecretFile)
                .withBitcoinAuthWithMcpChannelsAndQuorum(false, false, true,
                        List.of("agent-a", "agent-b"), 2, List.of("telegram"))
                .build();

        Path configFile = tempDir.resolve("config-auth-defs-timeout-quorum-veto-%d.toml".formatted(System.nanoTime()));
        Files.writeString(configFile, configToml, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);

        KonkinConfig config = KonkinConfig.load(configFile.toString());
        DatabaseManager dbManager = new DatabaseManager(TestDatabaseManager.dataSource("web-endpoints-test"));
        KonkinWebServer server = new KonkinWebServer(config, "test-version", dbManager.dataSource());
        server.start();

        try (RunningServer runningServer = new RunningServer(server, URI.create("http://127.0.0.1:" + port), dbManager)) {
            waitForHealth(port);
            HttpResponse<String> authDefinitions = get(runningServer, "/wallets/bitcoin", Map.of());
            assertEquals(200, authDefinitions.statusCode());
            assertTrue(authDefinitions.body().contains("2-of-N"));
            assertTrue(authDefinitions.body().contains("Veto channels"));
            assertTrue(authDefinitions.body().contains("telegram"));
            assertTrue(authDefinitions.body().contains("agent-a @ http://127.0.0.1:" + agentAPort));
            assertTrue(authDefinitions.body().contains("agent-b @ http://127.0.0.1:" + agentBPort));
        }
    }

    @Test
    void authDefinitionsPageShowsFriendlyDurationWindow() throws Exception {
        int port = freePort();
        int btcMainPort = freePort();
        int btcBackupPort = freePort();

        Path templateDir = Path.of("src/main/resources/templates").toAbsolutePath().normalize();
        Path staticDir = Path.of("src/main/resources/static").toAbsolutePath().normalize();
        Path webUiPasswordFile = tempDir.resolve("unused-web-ui-auth-defs.password");
        Path daemonSecretFile = tempDir.resolve("secrets/bitcoin-daemon-auth-defs.conf");
        Path walletSecretFile = tempDir.resolve("secrets/bitcoin-wallet-auth-defs.conf");

        String configToml = TestConfigBuilder.create(port)
                .withWebUi(true)
                .withWebUiPasswordProtection(false, webUiPasswordFile)
                .withWebUiTemplate(templateDir)
                .withWebUiStatic(staticDir)
                .withAutoReload(false)
                .withSecondaryAgent("btc-main", true, "127.0.0.1", btcMainPort,
                        tempDir.resolve("secrets/btc-main-auth-defs.secret"))
                .withSecondaryAgent("btc-backup", true, "127.0.0.1", btcBackupPort,
                        tempDir.resolve("secrets/btc-backup-auth-defs.secret"))
                .withBitcoin(daemonSecretFile, walletSecretFile)
                .withBitcoinAuthWithMcpAuthChannels(true, false, false, List.of("btc-main", "btc-backup"))
                .withBitcoinAutoDenyRule("cumulated-value-gt", 2.0, "7 days and 2 hours")
                .build();

        Path configFile = tempDir.resolve("config-auth-defs-friendly-window-%d.toml".formatted(System.nanoTime()));
        Files.writeString(configFile, configToml, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);

        KonkinConfig config = KonkinConfig.load(configFile.toString());
        KonkinWebServer server = new KonkinWebServer(config, "test-version");
        server.start();

        try (RunningServer runningServer = new RunningServer(server, URI.create("http://127.0.0.1:" + port))) {
            waitForHealth(port);
            HttpResponse<String> authDefinitions = get(runningServer, "/wallets/bitcoin", Map.of());
            assertEquals(200, authDefinitions.statusCode());
            assertTrue(authDefinitions.body().contains("value=\"170\""));
            assertTrue(authDefinitions.body().contains("sum in window &gt;"));
            assertTrue(authDefinitions.body().contains("Time window"));
            // litecoin/monero not configured (enabled=false) in this test config, so they should not appear
            assertFalse(authDefinitions.body().contains("LITECOIN"));
            assertFalse(authDefinitions.body().contains("MONERO"));
            assertTrue(authDefinitions.body().contains("/assets/img/bitcoin.svg"));
            assertFalse(authDefinitions.body().contains("/assets/img/litecoin.svg"));
            assertFalse(authDefinitions.body().contains("/assets/img/monero.svg"));
            assertTrue(authDefinitions.body().contains("Auth channels"));
            assertTrue(authDefinitions.body().contains("btc-main @ http://127.0.0.1:" + btcMainPort));
            assertTrue(authDefinitions.body().contains("btc-backup @ http://127.0.0.1:" + btcBackupPort));
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

        String configToml = TestConfigBuilder.create(port)
                .withWebUi(true)
                .withWebUiPasswordProtection(false, webUiPasswordFile)
                .withWebUiTemplate(templateDir)
                .withWebUiStatic(staticDir)
                .withAutoReload(false)
                .withRestApiSecret(restApiSecretFile)
                .withSecondaryAgent("agent-enabled", true, "127.0.0.1", enabledSecondaryAgentPort,
                        tempDir.resolve("secrets/agent-enabled-auth-defs.secret"))
                .withSecondaryAgent("agent-disabled", false, "127.0.0.1", disabledSecondaryAgentPort,
                        tempDir.resolve("secrets/agent-disabled-auth-defs.secret"))
                .withBitcoin(daemonSecretFile, walletSecretFile)
                .withBitcoinAuth(true, true, false)
                .build();

        Path configFile = tempDir.resolve("config-auth-defs-rest-and-secondary-%d.toml".formatted(System.nanoTime()));
        Files.writeString(configFile, configToml, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);

        Files.createDirectories(restApiSecretFile.getParent());
        Files.writeString(restApiSecretFile, "api-key=test-api-key-auth-defs", StandardCharsets.UTF_8);

        KonkinConfig config = KonkinConfig.load(configFile.toString());
        KonkinWebServer server = new KonkinWebServer(config, "test-version");
        server.start();

        try (RunningServer runningServer = new RunningServer(server, URI.create("http://127.0.0.1:" + port))) {
            waitForHealth(port);
            HttpResponse<String> authDefinitions = get(runningServer, "/wallets/bitcoin", Map.of());
            assertEquals(200, authDefinitions.statusCode());
            assertTrue(authDefinitions.body().contains("web-ui <strong>on</strong>"));
            assertTrue(authDefinitions.body().contains("rest-api <strong>on</strong>"));
            assertTrue(authDefinitions.body().contains("telegram <strong>off</strong>"));
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

        String configToml = TestConfigBuilder.create(port)
                .withWebUi(true)
                .withWebUiPasswordProtection(false, webUiPasswordFile)
                .withWebUiTemplate(templateDir)
                .withWebUiStatic(staticDir)
                .withAutoReload(false)
                .withTelegramSecret(telegramSecretFile)
                .withPrimaryAgent(true, "127.0.0.1", primaryAgentPort, primarySecretFile)
                .withSecondaryAgent("agent-a", true, "127.0.0.1", secondaryAgentPort, secondarySecretFile)
                .build();

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
            assertFalse(authChannels.body().contains("Telegram Connected Users"));
            assertTrue(authChannels.body().contains("Auth Agent Bot Channels"));
            assertFalse(authChannels.body().contains("Driver Agent Endpoint"));
            assertTrue(authChannels.body().contains("<span class=\"menu-active\">auth channels</span>"));
            assertTrue(authChannels.body().contains("Last Lifesign"));
            assertTrue(authChannels.body().contains("mcp-auth-channels"));
            assertFalse(authChannels.body().contains("Reference format"));
            assertFalse(authChannels.body().contains("Runtime checks"));
            assertTrue(authChannels.body().contains("http://127.0.0.1:" + secondaryAgentPort + "/health"));
            assertTrue(authChannels.body().contains("http://127.0.0.1:" + secondaryAgentPort + "/oauth/token"));

            HttpResponse<String> driverAgent = get(runningServer, "/driver_agent", Map.of());
            assertEquals(200, driverAgent.statusCode());
            assertTrue(driverAgent.body().contains("Driver Agent"));
            assertTrue(driverAgent.body().contains("Driver Agent Endpoint"));
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

        String configToml = TestConfigBuilder.create(port)
                .withWebUi(true)
                .withWebUiPasswordProtection(false, webUiPasswordFile)
                .withWebUiTemplate(templateDir)
                .withWebUiStatic(staticDir)
                .withAutoReload(false)
                .withSecondaryAgent("btc-main", true, "127.0.0.1", 9566,
                        tempDir.resolve("secrets/btc-main-coins.secret"))
                .withSecondaryAgent("btc-backup", true, "127.0.0.1", 9567,
                        tempDir.resolve("secrets/btc-backup-coins.secret"))
                .withBitcoin(daemonSecretFile, walletSecretFile)
                .withBitcoinAuthWithMcpAuthChannels(true, false, false, List.of("btc-main", "btc-backup"))
                .build();

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
    void restApiSecretFileIsNotAutoGeneratedWhenMissing() throws Exception {
        int port = freePort();
        Path restApiSecretFile = tempDir.resolve("secrets/rest-api-generated.secret");

        String configToml = TestConfigBuilder.create(port)
                .withRestApiSecret(restApiSecretFile)
                .build();

        Path configFile = tempDir.resolve("config-rest-api-secret-generation-%d.toml".formatted(System.nanoTime()));
        Files.writeString(configFile, configToml, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);

        KonkinConfig.load(configFile.toString());

        assertFalse(Files.exists(restApiSecretFile));
    }

    @Test
    void restApiProtectedRoutesRejectMissingOrWrongApiKey() throws Exception {
        int port = freePort();
        Path restApiSecretFile = tempDir.resolve("secrets/rest-api-protected.secret");

        String configToml = TestConfigBuilder.create(port)
                .withDatabase("jdbc:h2:mem:web-endpoints-test;DB_CLOSE_DELAY=-1", "konkin", "konkin", 5)
                .withRestApiSecret(restApiSecretFile)
                .withWebUi(false)
                .build();

        String correctApiKey = "test-api-key-for-protected-routes";
        Files.createDirectories(restApiSecretFile.getParent());
        Files.writeString(restApiSecretFile, "api-key=" + correctApiKey + System.lineSeparator(),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);

        Path configFile = tempDir.resolve("config-rest-api-protected-%d.toml".formatted(System.nanoTime()));
        Files.writeString(configFile, configToml, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);

        KonkinConfig config = KonkinConfig.load(configFile.toString());

        DatabaseManager dbManager = new DatabaseManager(TestDatabaseManager.dataSource("web-endpoints-test"));
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

        String configToml = TestConfigBuilder.create(port)
                .withDatabase("jdbc:h2:mem:web-endpoints-test;DB_CLOSE_DELAY=-1", "konkin", "konkin", 5)
                .withRestApiSecret(restApiSecretFile)
                .withWebUi(false)
                .build();

        String correctApiKey = "test-api-key-for-allowed-routes";
        Files.createDirectories(restApiSecretFile.getParent());
        Files.writeString(restApiSecretFile, "api-key=" + correctApiKey + System.lineSeparator(),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);

        Path configFile = tempDir.resolve("config-rest-api-allowed-%d.toml".formatted(System.nanoTime()));
        Files.writeString(configFile, configToml, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);

        KonkinConfig config = KonkinConfig.load(configFile.toString());

        DatabaseManager dbManager = new DatabaseManager(TestDatabaseManager.dataSource("web-endpoints-test"));
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