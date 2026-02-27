package io.konkin.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import io.konkin.config.KonkinConfig;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.SQLException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebEndpointsIntegrationTest extends WebIntegrationTestSupport {

    @Test
    void healthEndpointReturnsHealthyStatus() throws Exception {
        try (RunningServer server = startServer(false, false, "unused")) {
            HttpResponse<String> response = get(server, "/api/v1/health", Map.of());

            assertEquals(200, response.statusCode());

            JsonNode json = JSON.readTree(response.body());
            assertEquals("healthy", json.path("status").asText());
            assertEquals("test-version", json.path("version").asText());
            assertEquals("connected", json.path("database").asText());
        }
    }

    @Test
    void approvalRequestsRejectDuplicateNonceComposite() throws Exception {
        try (RunningServer server = startServer(false, false, "unused")) {
            DataSource dataSource = server.dbManager().dataSource();

            insertApprovalRequest(dataSource, "req-1", "nonce-duplicate", "PENDING");

            assertThrows(
                    SQLException.class,
                    () -> insertApprovalRequest(dataSource, "req-2", "nonce-duplicate", "PENDING")
            );
        }
    }

    @Test
    void approvalVotesRejectDuplicateVotePerRequestAndChannel() throws Exception {
        try (RunningServer server = startServer(false, false, "unused")) {
            DataSource dataSource = server.dbManager().dataSource();

            insertApprovalRequest(dataSource, "req-votes", "nonce-votes", "PENDING");
            insertApprovalChannel(dataSource, "telegram.main", "telegram");
            insertApprovalVote(dataSource, "req-votes", "telegram.main", "approve");

            assertThrows(
                    SQLException.class,
                    () -> insertApprovalVote(dataSource, "req-votes", "telegram.main", "deny")
            );
        }
    }

    @Test
    void landingDisabledDoesNotExposeRootLogOrStaticAssets() throws Exception {
        try (RunningServer server = startServer(false, false, "unused")) {
            HttpResponse<String> root = get(server, "/", Map.of());
            assertEquals(404, root.statusCode());

            HttpResponse<String> logPage = get(server, "/log", Map.of());
            assertEquals(404, logPage.statusCode());

            HttpResponse<String> authDefinitions = get(server, "/auth_definitions", Map.of());
            assertEquals(404, authDefinitions.statusCode());

            HttpResponse<String> staticAsset = get(server, "/assets/favicon.svg", Map.of());
            assertEquals(404, staticAsset.statusCode());
        }
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

            HttpResponse<String> authDefinitions = get(runningServer, "/auth_definitions", Map.of());
            assertEquals(200, authDefinitions.statusCode());
            assertTrue(authDefinitions.body().contains("Auth Definitions"));
            assertTrue(authDefinitions.body().contains("BITCOIN"));
            assertTrue(authDefinitions.body().contains("LITECOIN"));
            assertTrue(authDefinitions.body().contains("MONERO"));
            assertTrue(authDefinitions.body().contains("/assets/img/bitcoin.svg"));
            assertTrue(authDefinitions.body().contains("/assets/img/litecoin.svg"));
            assertTrue(authDefinitions.body().contains("/assets/img/monero.svg"));
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
                true
        );

        assertDoesNotThrow(() -> KonkinConfig.load(configFile.toString()));
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
            HttpResponse<String> authDefinitions = get(runningServer, "/auth_definitions", Map.of());
            assertEquals(200, authDefinitions.statusCode());
            assertTrue(authDefinitions.body().contains("7d 2h"));
            assertTrue(authDefinitions.body().contains("sum in window >"));
            assertTrue(authDefinitions.body().contains("Time window"));
            assertTrue(authDefinitions.body().contains("LITECOIN"));
            assertTrue(authDefinitions.body().contains("MONERO"));
            assertTrue(authDefinitions.body().contains("/assets/img/bitcoin.svg"));
            assertTrue(authDefinitions.body().contains("/assets/img/litecoin.svg"));
            assertTrue(authDefinitions.body().contains("/assets/img/monero.svg"));
            assertTrue(authDefinitions.body().contains(">***<"));
            assertTrue(authDefinitions.body().contains("aria-label=\"Reveal MCP value\""));
        }
    }

    @Test
    void bitcoinAuthInvalidMultipleChannelDependenciesFailsFastOnFirstViolation() throws Exception {
        Path configFile = writeBitcoinChannelValidationConfig(
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
}
