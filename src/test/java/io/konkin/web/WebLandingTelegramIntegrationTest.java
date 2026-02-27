package io.konkin.web;

import com.sun.net.httpserver.HttpServer;
import io.konkin.config.KonkinConfig;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebLandingTelegramIntegrationTest extends WebIntegrationTestSupport {

    @Test
    void landingEnabledUnprotectedServesRootLogAndStaticAssets() throws Exception {
        try (RunningServer server = startServer(true, false, "unused")) {
            HttpResponse<String> root = get(server, "/", Map.of());
            assertEquals(200, root.statusCode());
            assertTrue(root.body().contains("KONKIN"));
            assertTrue(root.body().contains("Authorization Queue"));
            assertFalse(root.body().contains("Audit Log"));

            HttpResponse<String> logPage = get(server, "/log", Map.of());
            assertEquals(200, logPage.statusCode());
            assertTrue(logPage.body().contains("KONKIN"));
            assertTrue(logPage.body().contains("Audit Log"));
            assertFalse(logPage.body().contains("Authorization Queue"));
            assertTrue(logPage.body().contains("menu-active\">audit<"));

            HttpResponse<String> loginGet = get(server, "/login", Map.of());
            assertEquals(302, loginGet.statusCode());
            assertEquals("/", loginGet.headers().firstValue("location").orElse(""));

            HttpResponse<String> loginPost = postForm(server, "/login", "password=anything", Map.of());
            assertEquals(302, loginPost.statusCode());
            assertEquals("/", loginPost.headers().firstValue("location").orElse(""));

            assertFalse(root.body().contains("Telegram Broadcast"));
            assertFalse(root.body().contains("href=\"/telegram\""));
            assertFalse(root.body().contains(">github<"));
            assertTrue(root.body().contains("View on GitHub"));

            HttpResponse<String> telegramPage = get(server, "/telegram", Map.of());
            assertEquals(404, telegramPage.statusCode());

            HttpResponse<String> telegramSubmit = postForm(server, "/telegram/send", "telegram_message=hello", Map.of());
            assertEquals(404, telegramSubmit.statusCode());

            HttpResponse<String> staticAsset = get(server, "/assets/img/bitcoin.svg", Map.of());
            assertEquals(200, staticAsset.statusCode());
            assertTrue(staticAsset.body().contains("<svg"));
        }
    }

    @Test
    void queueDefaultsToExpiresAscendingWhenNoQueryParams() throws Exception {
        try (RunningServer server = startServer(true, false, "unused")) {
            DataSource dataSource = server.dbManager().dataSource();

            insertApprovalRequest(dataSource, "req-exp-late", "nonce-exp-late", "PENDING");
            insertApprovalRequest(dataSource, "req-exp-soon", "nonce-exp-soon", "PENDING");

            Instant now = Instant.now();
            updateApprovalRequestTimes(
                    dataSource,
                    "req-exp-late",
                    now.minusSeconds(7_200),
                    now.plusSeconds(7_200)
            );
            updateApprovalRequestTimes(
                    dataSource,
                    "req-exp-soon",
                    now.minusSeconds(3_600),
                    now.plusSeconds(300)
            );

            HttpResponse<String> root = get(server, "/", Map.of());
            assertEquals(200, root.statusCode());

            String body = root.body();
            int soonIndex = body.indexOf("req-exp-soon");
            int lateIndex = body.indexOf("req-exp-late");
            assertTrue(soonIndex >= 0, "Expected req-exp-soon to be rendered on queue page");
            assertTrue(lateIndex >= 0, "Expected req-exp-late to be rendered on queue page");
            assertTrue(soonIndex < lateIndex, "Expected default queue ordering to be expires_at ascending");
        }
    }

    @Test
    void queueShowsPagerAtTopAndBottomWhenMoreThanEightRows() throws Exception {
        try (RunningServer server = startServer(true, false, "unused")) {
            DataSource dataSource = server.dbManager().dataSource();

            for (int i = 1; i <= 9; i++) {
                String requestId = "req-pager-%02d".formatted(i);
                String nonce = "nonce-pager-%02d".formatted(i);
                insertApprovalRequest(dataSource, requestId, nonce, "PENDING");
            }

            HttpResponse<String> root = get(server, "/", Map.of());
            assertEquals(200, root.statusCode());

            String body = root.body();
            assertTrue(body.contains("class=\"pager pager-top\""));
            assertEquals(2, countOccurrences(body, "<div class=\"pager"));
        }
    }

    @Test
    void queueAndLogUseStateSplitAndLogSupportsSortAndExplicitFilters() throws Exception {
        try (RunningServer server = startServer(true, false, "unused")) {
            DataSource dataSource = server.dbManager().dataSource();

            insertApprovalRequest(dataSource, "req-log-completed-11111", "nonce-log-completed", "COMPLETED");
            insertApprovalRequest(dataSource, "req-log-denied-33333", "nonce-log-denied", "DENIED");
            insertApprovalRequest(dataSource, "req-log-failed-55555", "nonce-log-failed", "FAILED");
            insertApprovalRequest(dataSource, "req-log-pending-22222", "nonce-log-pending", "PENDING");
            insertApprovalRequest(dataSource, "req-log-queued-44444", "nonce-log-queued", "QUEUED");

            updateApprovalRequestCoinAndTool(dataSource, "req-log-denied-33333", "litecoin", "wallet_sweep");
            updateApprovalRequestCoinAndTool(dataSource, "req-log-failed-55555", "litecoin", "wallet_sweep");

            updateApprovalUpdatedAt(dataSource, "req-log-completed-11111", Instant.parse("2024-01-02T03:04:05Z"));

            insertApprovalChannel(dataSource, "telegram.main", "telegram");
            insertApprovalVote(dataSource, "req-log-completed-11111", "telegram.main", "approve");

            HttpResponse<String> rootPage = get(server, "/", Map.of());
            assertEquals(200, rootPage.statusCode());
            assertTrue(rootPage.body().contains("req-log-pending-22222"));
            assertTrue(rootPage.body().contains("req-log-queued-44444"));
            assertFalse(rootPage.body().contains("req-log-completed-11111"));
            assertFalse(rootPage.body().contains("req-log-denied-33333"));
            assertFalse(rootPage.body().contains("req-log-failed-55555"));

            HttpResponse<String> logPage = get(server, "/log", Map.of());
            assertEquals(200, logPage.statusCode());

            String body = logPage.body();
            assertTrue(body.contains("Audit Log"));
            assertTrue(body.contains("Resolved / Processed Requests"));
            assertFalse(body.contains("State Transitions"));
            assertEquals(1, countOccurrences(body, "<table class=\"queue-table\">"));

            assertTrue(body.contains("class=\"pager pager-top\""));
            assertEquals(1, countOccurrences(body, "<div class=\"pager"));

            assertTrue(body.contains("name=\"log_queue_filter\""));
            assertTrue(body.contains("name=\"log_queue_coin\""));
            assertTrue(body.contains("name=\"log_queue_tool\""));
            assertTrue(body.contains("name=\"log_queue_state\""));
            assertTrue(body.contains("placeholder=\"Filter by id or decider\""));
            assertTrue(body.contains("log_queue_sort=id"));
            assertTrue(body.contains("log_queue_sort=coin"));
            assertTrue(body.contains("log_queue_sort=tool_name"));
            assertTrue(body.contains("log_queue_sort=state"));
            assertTrue(body.contains("log_queue_sort=updated_at"));

            assertFalse(body.contains("req-log-pending-22222"));
            assertFalse(body.contains("req-log-queued-44444"));
            assertTrue(body.contains("req-log-completed-11111"));
            assertTrue(body.contains("req-log-denied-33333"));
            assertTrue(body.contains("req-log-failed-55555"));
            assertTrue(body.contains("2024 01 02 03:04"));
            assertTrue(body.contains("test-actor"));
            assertTrue(body.contains("queue-copy-btn"));
            assertTrue(body.contains("queue-details-trigger"));

            HttpResponse<String> byDecider = get(server, "/log?log_queue_filter=test-actor", Map.of());
            assertEquals(200, byDecider.statusCode());
            assertTrue(byDecider.body().contains("req-log-completed-11111"));
            assertFalse(byDecider.body().contains("req-log-denied-33333"));
            assertFalse(byDecider.body().contains("req-log-failed-55555"));
            assertFalse(byDecider.body().contains("req-log-pending-22222"));
            assertFalse(byDecider.body().contains("req-log-queued-44444"));

            HttpResponse<String> byId = get(server, "/log?log_queue_filter=denied", Map.of());
            assertEquals(200, byId.statusCode());
            assertFalse(byId.body().contains("req-log-completed-11111"));
            assertTrue(byId.body().contains("req-log-denied-33333"));
            assertFalse(byId.body().contains("req-log-failed-55555"));

            HttpResponse<String> byCoin = get(server, "/log?log_queue_coin=litecoin", Map.of());
            assertEquals(200, byCoin.statusCode());
            assertFalse(byCoin.body().contains("req-log-completed-11111"));
            assertTrue(byCoin.body().contains("req-log-denied-33333"));
            assertTrue(byCoin.body().contains("req-log-failed-55555"));

            HttpResponse<String> byTool = get(server, "/log?log_queue_tool=wallet_sweep", Map.of());
            assertEquals(200, byTool.statusCode());
            assertFalse(byTool.body().contains("req-log-completed-11111"));
            assertTrue(byTool.body().contains("req-log-denied-33333"));
            assertTrue(byTool.body().contains("req-log-failed-55555"));

            HttpResponse<String> byState = get(server, "/log?log_queue_state=DENIED", Map.of());
            assertEquals(200, byState.statusCode());
            assertFalse(byState.body().contains("req-log-completed-11111"));
            assertTrue(byState.body().contains("req-log-denied-33333"));
            assertFalse(byState.body().contains("req-log-failed-55555"));

            HttpResponse<String> combined = get(
                    server,
                    "/log?log_queue_coin=litecoin&log_queue_tool=wallet_sweep&log_queue_state=DENIED",
                    Map.of()
            );
            assertEquals(200, combined.statusCode());
            assertFalse(combined.body().contains("req-log-completed-11111"));
            assertTrue(combined.body().contains("req-log-denied-33333"));
            assertFalse(combined.body().contains("req-log-failed-55555"));

            HttpResponse<String> sortedByIdAsc = get(server, "/log?log_queue_sort=id&log_queue_dir=asc", Map.of());
            assertEquals(200, sortedByIdAsc.statusCode());
            assertTrue(sortedByIdAsc.body().contains("log_queue_sort=id&log_queue_dir=desc"));
        }
    }

    @Test
    void queueProvidesNoJsDetailsFallbackLinkAndEndpoint() throws Exception {
        try (RunningServer server = startServer(true, false, "unused")) {
            DataSource dataSource = server.dbManager().dataSource();
            insertApprovalRequest(dataSource, "req-details-fallback", "nonce-details-fallback", "PENDING");

            HttpResponse<String> root = get(server, "/", Map.of());
            assertEquals(200, root.statusCode());
            assertTrue(root.body().contains("id=\"menu-toggle-queue\""));
            assertTrue(root.body().contains("class=\"menu-toggle-btn\""));
            assertTrue(root.body().contains("href=\"/details?id=req-details-fallback\""));

            HttpResponse<String> details = get(server, "/details?id=req-details-fallback", Map.of());
            assertEquals(200, details.statusCode());
            assertTrue(details.headers().firstValue("content-type").orElse("").startsWith("text/plain"));
            assertTrue(details.body().contains("\"request\""));
            assertTrue(details.body().contains("req-details-fallback"));
        }
    }

    @Test
    void landingCssContainsResponsiveBurgerRulesWithoutJavaScript() throws Exception {
        try (RunningServer server = startServer(true, false, "unused")) {
            HttpResponse<String> css = get(server, "/assets/css/landing.css", Map.of());
            assertEquals(200, css.statusCode());
            assertTrue(css.body().contains("@media (max-width: 860px)"));
            assertTrue(css.body().contains(".menu-toggle-btn"));
            assertTrue(css.body().contains(".menu-toggle:checked ~ .menu"));
        }
    }

    @Test
    void landingTelegramEnabledShowsFormAndSubmitsToTelegramApi() throws Exception {
        int telegramApiPort = freePort();
        AtomicReference<String> capturedPayload = new AtomicReference<>("");

        HttpServer telegramApi = HttpServer.create(new InetSocketAddress("127.0.0.1", telegramApiPort), 0);
        telegramApi.createContext("/bottest-bot-token/sendMessage", exchange -> {
            try (exchange) {
                if (!"POST".equals(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(405, -1);
                    return;
                }

                String payload = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                capturedPayload.set(payload);

                byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        telegramApi.start();

        int port = freePort();
        Path landingPasswordFile = tempDir.resolve("unused-landing.password");
        Path secretFile = tempDir.resolve("telegram.secret");
        Files.writeString(
                secretFile,
                "chat-ids=-100123456789\nbot-token=test-bot-token\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW
        );

        Path templateDir = Path.of("src/main/resources/templates").toAbsolutePath().normalize();
        Path staticDir = Path.of("src/main/resources/static").toAbsolutePath().normalize();

        String configToml = """
                config-version = 1

                [server]
                host = "127.0.0.1"
                port = %d

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
                enabled = false

                [telegram]
                enabled = true
                secret-file = "%s"
                api-base-url = "http://127.0.0.1:%d"
                """.formatted(
                port,
                tomlPath(landingPasswordFile),
                tomlPath(templateDir),
                tomlPath(staticDir),
                tomlPath(secretFile),
                telegramApiPort
        );

        Path configFile = tempDir.resolve("config-telegram-%d.toml".formatted(System.nanoTime()));
        Files.writeString(configFile, configToml, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);

        KonkinConfig config = KonkinConfig.load(configFile.toString());
        KonkinWebServer server = new KonkinWebServer(config, "test-version");
        server.start();

        try (RunningServer runningServer = new RunningServer(server, URI.create("http://127.0.0.1:" + port))) {
            waitForHealth(port);

            HttpResponse<String> root = get(runningServer, "/", Map.of());
            assertEquals(200, root.statusCode());
            assertTrue(root.body().contains("href=\"/telegram\""));
            assertFalse(root.body().contains("Telegram Broadcast"));
            assertFalse(root.body().contains(">github<"));
            assertTrue(root.body().contains("View on GitHub"));

            HttpResponse<String> telegramPage = get(runningServer, "/telegram", Map.of());
            assertEquals(200, telegramPage.statusCode());
            assertTrue(telegramPage.body().contains("Telegram Broadcast"));
            assertTrue(telegramPage.body().contains("action=\"/telegram/send\""));
            assertFalse(telegramPage.body().contains(">github<"));
            assertTrue(telegramPage.body().contains("View on GitHub"));

            HttpResponse<String> telegramSubmit = postForm(
                    runningServer,
                    "/telegram/send",
                    "telegram_message=Hello+Konkin",
                    Map.of()
            );
            assertEquals(200, telegramSubmit.statusCode());
            assertTrue(telegramSubmit.body().contains("Telegram message sent."));

            String payload = capturedPayload.get();
            assertTrue(payload.contains("chat_id=-100123456789"));
            assertTrue(payload.contains("text=Hello+Konkin"));
        } finally {
            telegramApi.stop(0);
        }
    }

    @Test
    void telegramSecretFileIsBootstrappedAndStartupStaysStoppedUntilReplaced() throws Exception {
        int port = freePort();
        Path landingPasswordFile = tempDir.resolve("unused-landing.password");
        Path secretFile = tempDir.resolve("secrets/telegram.secret");

        Path templateDir = Path.of("src/main/resources/templates").toAbsolutePath().normalize();
        Path staticDir = Path.of("src/main/resources/static").toAbsolutePath().normalize();

        String configToml = """
                config-version = 1

                [server]
                host = "127.0.0.1"
                port = %d

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
                enabled = false

                [telegram]
                enabled = true
                secret-file = "%s"
                api-base-url = "http://127.0.0.1:65534"
                """.formatted(
                port,
                tomlPath(landingPasswordFile),
                tomlPath(templateDir),
                tomlPath(staticDir),
                tomlPath(secretFile)
        );

        Path configFile = tempDir.resolve("config-telegram-bootstrap-%d.toml".formatted(System.nanoTime()));
        Files.writeString(configFile, configToml, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);

        KonkinConfig config = KonkinConfig.load(configFile.toString());

        KonkinWebServer firstServer = new KonkinWebServer(config, "test-version");
        firstServer.start();
        assertFalse(firstServer.isRunning());
        assertTrue(Files.exists(secretFile));

        String bootstrap = Files.readString(secretFile, StandardCharsets.UTF_8);
        assertTrue(bootstrap.contains("chat-ids=REPLACE_WITH_TELEGRAM_CHAT_IDS"));
        assertTrue(bootstrap.contains("bot-token=REPLACE_WITH_TELEGRAM_BOT_TOKEN"));
        assertTrue(bootstrap.contains("@BotFather"));

        KonkinWebServer secondServer = new KonkinWebServer(config, "test-version");
        secondServer.start();
        assertFalse(secondServer.isRunning());
    }

    @Test
    void telegramDiscoveredChatIdsAreNotAutoApprovedOnStartup() throws Exception {
        int telegramApiPort = freePort();
        AtomicReference<String> capturedPayload = new AtomicReference<>("");

        String approvedChatId = "-100123456789";
        String discoveredChatId = "-100987654321";

        HttpServer telegramApi = HttpServer.create(new InetSocketAddress("127.0.0.1", telegramApiPort), 0);
        telegramApi.createContext("/bottest-bot-token/getUpdates", exchange -> {
            try (exchange) {
                if (!"GET".equals(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(405, -1);
                    return;
                }

                byte[] body = ("""
                        {"ok":true,"result":[{"update_id":1,"message":{"chat":{"id":"%s","type":"private","username":"pending_user","first_name":"Pending"}}}]}
                        """).formatted(discoveredChatId).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        telegramApi.createContext("/bottest-bot-token/sendMessage", exchange -> {
            try (exchange) {
                if (!"POST".equals(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(405, -1);
                    return;
                }

                String payload = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                capturedPayload.set(payload);

                byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        telegramApi.start();

        int port = freePort();
        Path landingPasswordFile = tempDir.resolve("unused-landing.password");
        Path secretFile = tempDir.resolve("telegram.secret");
        Files.writeString(
                secretFile,
                "chat-ids=%s\nbot-token=test-bot-token\n".formatted(approvedChatId),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW
        );

        Path templateDir = Path.of("src/main/resources/templates").toAbsolutePath().normalize();
        Path staticDir = Path.of("src/main/resources/static").toAbsolutePath().normalize();

        String configToml = """
                config-version = 1

                [server]
                host = "127.0.0.1"
                port = %d

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
                enabled = false

                [telegram]
                enabled = true
                secret-file = "%s"
                api-base-url = "http://127.0.0.1:%d"
                """.formatted(
                port,
                tomlPath(landingPasswordFile),
                tomlPath(templateDir),
                tomlPath(staticDir),
                tomlPath(secretFile),
                telegramApiPort
        );

        Path configFile = tempDir.resolve("config-telegram-no-auto-approve-%d.toml".formatted(System.nanoTime()));
        Files.writeString(configFile, configToml, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);

        KonkinConfig config = KonkinConfig.load(configFile.toString());
        KonkinWebServer server = new KonkinWebServer(config, "test-version");
        server.start();

        try (RunningServer runningServer = new RunningServer(server, URI.create("http://127.0.0.1:" + port))) {
            waitForHealth(port);

            HttpResponse<String> telegramPage = get(runningServer, "/telegram", Map.of());
            assertEquals(200, telegramPage.statusCode());
            assertTrue(telegramPage.body().contains(discoveredChatId));

            HttpResponse<String> telegramSubmit = postForm(
                    runningServer,
                    "/telegram/send",
                    "telegram_message=No+Auto+Approve",
                    Map.of()
            );
            assertEquals(200, telegramSubmit.statusCode());
            assertTrue(telegramSubmit.body().contains("Telegram message sent."));

            String payload = capturedPayload.get();
            assertTrue(payload.contains("chat_id=" + approvedChatId));
            assertFalse(payload.contains("chat_id=" + discoveredChatId));

            String secretContent = Files.readString(secretFile, StandardCharsets.UTF_8);
            assertTrue(secretContent.contains("chat-ids=" + approvedChatId));
            assertFalse(secretContent.contains(discoveredChatId));
        } finally {
            telegramApi.stop(0);
        }
    }

    @Test
    void telegramDiscoveredChatIdsAreNotAutoApprovedAfterReconnect() throws Exception {
        int telegramApiPort = freePort();
        AtomicReference<String> capturedPayload = new AtomicReference<>("");

        String approvedChatId = "-100123456789";
        String discoveredChatId = "-100987654321";

        HttpServer telegramApi = HttpServer.create(new InetSocketAddress("127.0.0.1", telegramApiPort), 0);
        telegramApi.createContext("/bottest-bot-token/getUpdates", exchange -> {
            try (exchange) {
                if (!"GET".equals(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(405, -1);
                    return;
                }

                byte[] body = ("""
                        {"ok":true,"result":[{"update_id":2,"message":{"chat":{"id":"%s","type":"private","username":"pending_user","first_name":"Pending"}}}]}
                        """).formatted(discoveredChatId).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        telegramApi.createContext("/bottest-bot-token/sendMessage", exchange -> {
            try (exchange) {
                if (!"POST".equals(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(405, -1);
                    return;
                }

                String payload = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                capturedPayload.set(payload);

                byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        telegramApi.start();

        int firstPort = freePort();
        int secondPort = freePort();
        Path landingPasswordFile = tempDir.resolve("unused-landing.password");
        Path secretFile = tempDir.resolve("telegram-reconnect.secret");
        Files.writeString(
                secretFile,
                "chat-ids=%s\nbot-token=test-bot-token\n".formatted(approvedChatId),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW
        );

        Path templateDir = Path.of("src/main/resources/templates").toAbsolutePath().normalize();
        Path staticDir = Path.of("src/main/resources/static").toAbsolutePath().normalize();

        String firstConfigToml = """
                config-version = 1

                [server]
                host = "127.0.0.1"
                port = %d

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
                enabled = false

                [telegram]
                enabled = true
                secret-file = "%s"
                api-base-url = "http://127.0.0.1:%d"
                """.formatted(
                firstPort,
                tomlPath(landingPasswordFile),
                tomlPath(templateDir),
                tomlPath(staticDir),
                tomlPath(secretFile),
                telegramApiPort
        );

        String secondConfigToml = """
                config-version = 1

                [server]
                host = "127.0.0.1"
                port = %d

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
                enabled = false

                [telegram]
                enabled = true
                secret-file = "%s"
                api-base-url = "http://127.0.0.1:%d"
                """.formatted(
                secondPort,
                tomlPath(landingPasswordFile),
                tomlPath(templateDir),
                tomlPath(staticDir),
                tomlPath(secretFile),
                telegramApiPort
        );

        Path firstConfigFile = tempDir.resolve("config-telegram-reconnect-first-%d.toml".formatted(System.nanoTime()));
        Path secondConfigFile = tempDir.resolve("config-telegram-reconnect-second-%d.toml".formatted(System.nanoTime()));
        Files.writeString(firstConfigFile, firstConfigToml, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        Files.writeString(secondConfigFile, secondConfigToml, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);

        try {
            KonkinConfig firstConfig = KonkinConfig.load(firstConfigFile.toString());
            KonkinWebServer firstServer = new KonkinWebServer(firstConfig, "test-version");
            firstServer.start();

            try (RunningServer runningServer = new RunningServer(firstServer, URI.create("http://127.0.0.1:" + firstPort))) {
                waitForHealth(firstPort);

                HttpResponse<String> telegramPage = get(runningServer, "/telegram", Map.of());
                assertEquals(200, telegramPage.statusCode());
                assertTrue(telegramPage.body().contains(discoveredChatId));

                HttpResponse<String> telegramSubmit = postForm(
                        runningServer,
                        "/telegram/send",
                        "telegram_message=First+Run",
                        Map.of()
                );
                assertEquals(200, telegramSubmit.statusCode());
                assertTrue(telegramSubmit.body().contains("Telegram message sent."));

                String payload = capturedPayload.get();
                assertTrue(payload.contains("chat_id=" + approvedChatId));
                assertFalse(payload.contains("chat_id=" + discoveredChatId));
            }

            String secretAfterFirstRun = Files.readString(secretFile, StandardCharsets.UTF_8);
            assertTrue(secretAfterFirstRun.contains("chat-ids=" + approvedChatId));
            assertFalse(secretAfterFirstRun.contains(discoveredChatId));

            capturedPayload.set("");

            KonkinConfig secondConfig = KonkinConfig.load(secondConfigFile.toString());
            KonkinWebServer secondServer = new KonkinWebServer(secondConfig, "test-version");
            secondServer.start();

            try (RunningServer runningServer = new RunningServer(secondServer, URI.create("http://127.0.0.1:" + secondPort))) {
                waitForHealth(secondPort);

                HttpResponse<String> telegramPage = get(runningServer, "/telegram", Map.of());
                assertEquals(200, telegramPage.statusCode());
                assertTrue(telegramPage.body().contains(discoveredChatId));

                HttpResponse<String> telegramSubmit = postForm(
                        runningServer,
                        "/telegram/send",
                        "telegram_message=Second+Run",
                        Map.of()
                );
                assertEquals(200, telegramSubmit.statusCode());
                assertTrue(telegramSubmit.body().contains("Telegram message sent."));

                String payload = capturedPayload.get();
                assertTrue(payload.contains("chat_id=" + approvedChatId));
                assertFalse(payload.contains("chat_id=" + discoveredChatId));
            }

            String secretAfterSecondRun = Files.readString(secretFile, StandardCharsets.UTF_8);
            assertTrue(secretAfterSecondRun.contains("chat-ids=" + approvedChatId));
            assertFalse(secretAfterSecondRun.contains(discoveredChatId));
        } finally {
            telegramApi.stop(0);
        }
    }

    @Test
    void landingProtectedSupportsFullPasswordSessionFlowForRootAndLog() throws Exception {
        String landingPassword = "landing-secret";

        try (RunningServer server = startServer(true, true, landingPassword)) {
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
            assertTrue(rootWithSession.body().contains("Authorization Queue"));
            assertFalse(rootWithSession.body().contains("Audit Log"));

            HttpResponse<String> logWithSession = get(server, "/log", Map.of("Cookie", sessionCookie));
            assertEquals(200, logWithSession.statusCode());
            assertTrue(logWithSession.body().contains("logout"));
            assertTrue(logWithSession.body().contains("Audit Log"));
            assertFalse(logWithSession.body().contains("Authorization Queue"));
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
            assertTrue(initial.body().contains("Authorization Queue"));

            Path landingTemplate = templateDir.resolve("landing.ftl");
            String currentTemplate = Files.readString(landingTemplate, StandardCharsets.UTF_8);
            String updatedTemplate = currentTemplate.replace("Authorization Queue", "Authorization Queue Reloaded");
            Files.writeString(
                    landingTemplate,
                    updatedTemplate,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );

            boolean observedUpdate = waitForBodyContains(runningServer, "/", "Authorization Queue Reloaded", Duration.ofSeconds(5));
            assertTrue(observedUpdate, "Template auto-reload did not apply the updated landing.ftl content");
        }
    }
    private static void updateApprovalRequestCoinAndTool(DataSource dataSource, String requestId, String coin, String toolName)
            throws SQLException {
        String sql = "UPDATE approval_requests SET coin = ?, tool_name = ? WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, coin);
            ps.setString(2, toolName);
            ps.setString(3, requestId);
            ps.executeUpdate();
        }
    }

    private static void updateApprovalUpdatedAt(DataSource dataSource, String requestId, Instant updatedAt)
            throws SQLException {
        String sql = "UPDATE approval_requests SET updated_at = ? WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.from(updatedAt));
            ps.setString(2, requestId);
            ps.executeUpdate();
        }
    }

    private static int countOccurrences(String source, String token) {
        int count = 0;
        int index = 0;
        while (true) {
            index = source.indexOf(token, index);
            if (index < 0) {
                return count;
            }
            count++;
            index += token.length();
        }
    }
}
