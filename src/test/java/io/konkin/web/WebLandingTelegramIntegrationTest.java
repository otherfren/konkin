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

import com.sun.net.httpserver.HttpServer;
import io.konkin.TestConfigBuilder;
import io.konkin.config.KonkinConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import io.konkin.db.JdbiFactory;
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

    @TempDir
    static Path sharedTempDir;
    private static RunningServer sharedLandingServer;
    private static RunningServer sharedProtectedServer;

    private static final String SHARED_LANDING_PASSWORD = "konkin-test-password";

    @BeforeAll
    static void startSharedServers() throws Exception {
        sharedLandingServer = startServer(sharedTempDir, true, false, "unused", "web-landing-test");
        sharedProtectedServer = startServer(sharedTempDir, true, true, SHARED_LANDING_PASSWORD, "web-landing-test");
    }

    @AfterAll
    static void stopSharedServers() {
        if (sharedProtectedServer != null) sharedProtectedServer.close();
        if (sharedLandingServer != null) sharedLandingServer.close();
    }

    @BeforeEach
    void cleanDb() {
        if (sharedLandingServer != null && sharedLandingServer.dbManager() != null) {
            cleanDatabase(sharedLandingServer.dbManager().dataSource());
        }
        if (sharedProtectedServer != null && sharedProtectedServer.dbManager() != null) {
            cleanDatabase(sharedProtectedServer.dbManager().dataSource());
        }
    }

    @Test
    void landingEnabledUnprotectedServesRootLogAndStaticAssets() throws Exception {
        RunningServer server = sharedLandingServer;
        HttpResponse<String> root = get(server, "/", Map.of());
        assertEquals(200, root.statusCode());
        assertTrue(root.body().contains("KONKIN"));
        assertTrue(root.body().contains("Authorization Queue"));
        assertFalse(root.body().contains("History"));

        HttpResponse<String> logPage = get(server, "/history", Map.of());
        assertEquals(200, logPage.statusCode());
        assertTrue(logPage.body().contains("KONKIN"));
        assertTrue(logPage.body().contains("History"));
        assertFalse(logPage.body().contains("Authorization Queue"));
        assertTrue(logPage.body().contains("menu-active\">history<"));

        HttpResponse<String> loginGet = get(server, "/login", Map.of());
        assertEquals(302, loginGet.statusCode());
        assertEquals("/", loginGet.headers().firstValue("location").orElse(""));

        HttpResponse<String> loginPost = postForm(server, "/login", "password=anything", Map.of());
        assertEquals(302, loginPost.statusCode());
        assertEquals("/", loginPost.headers().firstValue("location").orElse(""));

        assertFalse(root.body().contains("Telegram Broadcast"));
        assertFalse(root.body().contains("href=\"/auth_channels/telegram\""));
        assertFalse(root.body().contains(">github<"));
        assertTrue(root.body().contains("View on GitHub"));

        HttpResponse<String> telegramPage = get(server, "/auth_channels/telegram", Map.of());
        assertEquals(404, telegramPage.statusCode());

        HttpResponse<String> telegramSubmit = postForm(server, "/auth_channels/telegram/send", "telegram_message=hello", Map.of());
        assertEquals(404, telegramSubmit.statusCode());

        HttpResponse<String> telegramUnapprove = postForm(server, "/auth_channels/telegram/unapprove", "chat_id=-100123456789&confirm=yes", Map.of());
        assertEquals(404, telegramUnapprove.statusCode());

        HttpResponse<String> telegramReset = postForm(server, "/auth_channels/telegram/reset", "confirm=yes", Map.of());
        assertEquals(404, telegramReset.statusCode());

        HttpResponse<String> staticAsset = get(server, "/assets/img/bitcoin.svg", Map.of());
        assertEquals(200, staticAsset.statusCode());
        assertTrue(staticAsset.body().contains("<svg"));
    }

    @Test
    void queueDefaultsToExpiresAscendingWhenNoQueryParams() throws Exception {
        DataSource dataSource = sharedLandingServer.dbManager().dataSource();

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

        HttpResponse<String> root = get(sharedLandingServer, "/", Map.of());
        assertEquals(200, root.statusCode());

        String body = root.body();
        int soonIndex = body.indexOf("req-exp-soon");
        int lateIndex = body.indexOf("req-exp-late");
        assertTrue(soonIndex >= 0, "Expected req-exp-soon to be rendered on queue page");
        assertTrue(lateIndex >= 0, "Expected req-exp-late to be rendered on queue page");
        assertTrue(soonIndex < lateIndex, "Expected default queue ordering to be expires_at ascending");
    }

    @Test
    void queueShowsPagerAtTopAndBottomWhenMoreThanEightRows() throws Exception {
        DataSource dataSource = sharedLandingServer.dbManager().dataSource();

        for (int i = 1; i <= 9; i++) {
            String requestId = "req-pager-%02d".formatted(i);
            String nonce = "nonce-pager-%02d".formatted(i);
            insertApprovalRequest(dataSource, requestId, nonce, "PENDING");
        }

        HttpResponse<String> root = get(sharedLandingServer, "/", Map.of());
        assertEquals(200, root.statusCode());

        String body = root.body();
        assertTrue(body.contains("class=\"pager pager-top\""));
        assertEquals(2, countOccurrences(body, "<div class=\"pager"));
    }

    @Test
    void queueAndLogUseStateSplitAndLogSupportsSortAndExplicitFilters() throws Exception {
        RunningServer server = sharedLandingServer;
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

        HttpResponse<String> logPage = get(server, "/history", Map.of());
        assertEquals(200, logPage.statusCode());

        String body = logPage.body();
        assertTrue(body.contains("History"));
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

        HttpResponse<String> byDecider = get(server, "/history?log_queue_filter=test-actor", Map.of());
        assertEquals(200, byDecider.statusCode());
        assertTrue(byDecider.body().contains("req-log-completed-11111"));
        assertFalse(byDecider.body().contains("req-log-denied-33333"));
        assertFalse(byDecider.body().contains("req-log-failed-55555"));
        assertFalse(byDecider.body().contains("req-log-pending-22222"));
        assertFalse(byDecider.body().contains("req-log-queued-44444"));

        HttpResponse<String> byId = get(server, "/history?log_queue_filter=denied", Map.of());
        assertEquals(200, byId.statusCode());
        assertFalse(byId.body().contains("req-log-completed-11111"));
        assertTrue(byId.body().contains("req-log-denied-33333"));
        assertFalse(byId.body().contains("req-log-failed-55555"));

        HttpResponse<String> byCoin = get(server, "/history?log_queue_coin=litecoin", Map.of());
        assertEquals(200, byCoin.statusCode());
        assertFalse(byCoin.body().contains("req-log-completed-11111"));
        assertTrue(byCoin.body().contains("req-log-denied-33333"));
        assertTrue(byCoin.body().contains("req-log-failed-55555"));

        HttpResponse<String> byTool = get(server, "/history?log_queue_tool=wallet_sweep", Map.of());
        assertEquals(200, byTool.statusCode());
        assertFalse(byTool.body().contains("req-log-completed-11111"));
        assertTrue(byTool.body().contains("req-log-denied-33333"));
        assertTrue(byTool.body().contains("req-log-failed-55555"));

        HttpResponse<String> byState = get(server, "/history?log_queue_state=DENIED", Map.of());
        assertEquals(200, byState.statusCode());
        assertFalse(byState.body().contains("req-log-completed-11111"));
        assertTrue(byState.body().contains("req-log-denied-33333"));
        assertFalse(byState.body().contains("req-log-failed-55555"));

        HttpResponse<String> combined = get(
                server,
                "/history?log_queue_coin=litecoin&log_queue_tool=wallet_sweep&log_queue_state=DENIED",
                Map.of()
        );
        assertEquals(200, combined.statusCode());
        assertFalse(combined.body().contains("req-log-completed-11111"));
        assertTrue(combined.body().contains("req-log-denied-33333"));
        assertFalse(combined.body().contains("req-log-failed-55555"));

        HttpResponse<String> sortedByIdAsc = get(server, "/history?log_queue_sort=id&log_queue_dir=asc", Map.of());
        assertEquals(200, sortedByIdAsc.statusCode());
        assertTrue(sortedByIdAsc.body().contains("log_queue_sort=id&log_queue_dir=desc"));
    }

    @Test
    void queueProvidesNoJsDetailsFallbackLinkAndEndpoint() throws Exception {
        DataSource dataSource = sharedLandingServer.dbManager().dataSource();
        insertApprovalRequest(dataSource, "req-details-fallback", "nonce-details-fallback", "PENDING");

        HttpResponse<String> root = get(sharedLandingServer, "/", Map.of());
        assertEquals(200, root.statusCode());
        assertTrue(root.body().contains("id=\"menu-toggle-queue\""));
        assertTrue(root.body().contains("class=\"menu-toggle-btn\""));
        assertTrue(root.body().contains("href=\"/details?id=req-details-fallback\""));

        HttpResponse<String> details = get(sharedLandingServer, "/details?id=req-details-fallback", Map.of());
        assertEquals(200, details.statusCode());
        assertTrue(details.headers().firstValue("content-type").orElse("").startsWith("text/plain"));
        assertTrue(details.body().contains("\"request\""));
        assertTrue(details.body().contains("req-details-fallback"));
    }

    @Test
    void queueDecisionPostRequiresExplicitConfirmationBeforePersistingVote() throws Exception {
        RunningServer server = sharedLandingServer;
        DataSource dataSource = server.dbManager().dataSource();
        String requestId = "req-queue-confirm-11111";
        insertApprovalRequest(dataSource, requestId, "nonce-queue-confirm", "PENDING");

        HttpResponse<String> initialDecision = postForm(
                server,
                "/queue/approve",
                "request_id=" + requestId,
                Map.of()
        );
        assertEquals(200, initialDecision.statusCode());
        assertTrue(initialDecision.body().contains("queue-confirm-panel"));
        assertTrue(initialDecision.body().contains("confirm approve"));

        assertEquals(0, countVotesForRequest(dataSource, requestId));
        assertEquals("PENDING", stateForRequest(dataSource, requestId));
        assertEquals(0, countTransitionsForRequest(dataSource, requestId));

        HttpResponse<String> confirmedDecision = postForm(
                server,
                "/queue/approve",
                "request_id=" + requestId + "&confirm=yes",
                Map.of()
        );
        assertEquals(200, confirmedDecision.statusCode());
        assertTrue(confirmedDecision.body().contains("Approval vote recorded for request"));
        assertTrue(confirmedDecision.body().contains("queue-notice-success"));

        assertEquals(1, countVotesForRequest(dataSource, requestId));
        assertEquals("APPROVED", stateForRequest(dataSource, requestId));
        assertEquals(1, countTransitionsForRequest(dataSource, requestId));
        assertEquals(1, countTransitionsForRequestAndState(dataSource, requestId, "APPROVED"));
        assertEquals(1, countChannelById(dataSource, "web-ui"));
    }

    @Test
    void queueDenyDecisionStoresVoteAndMarksRequestDenied() throws Exception {
        RunningServer server = sharedLandingServer;
        DataSource dataSource = server.dbManager().dataSource();
        String requestId = "req-queue-deny-22222";
        insertApprovalRequest(dataSource, requestId, "nonce-queue-deny", "PENDING");

        HttpResponse<String> deny = postForm(
                server,
                "/queue/deny",
                "request_id=" + requestId + "&confirm=yes",
                Map.of()
        );
        assertEquals(200, deny.statusCode());
        assertTrue(deny.body().contains("Deny vote recorded for request"));

        assertEquals(1, countVotesForRequest(dataSource, requestId));
        assertEquals("DENIED", stateForRequest(dataSource, requestId));
        assertEquals(1, countTransitionsForRequestAndState(dataSource, requestId, "DENIED"));
        assertEquals(1, countTransitionsForRequestActor(dataSource, requestId, "web_ui", "web-ui"));
    }

    @Test
    void queueDecisionRequiresValidSessionWhenLandingProtectionEnabled() throws Exception {
        RunningServer server = sharedProtectedServer;
        DataSource dataSource = server.dbManager().dataSource();
        String requestId = "req-queue-protected-33333";
        insertApprovalRequest(dataSource, requestId, "nonce-queue-protected", "PENDING");

        HttpResponse<String> withoutSession = postForm(
                server,
                "/queue/approve",
                "request_id=" + requestId + "&confirm=yes",
                Map.of()
        );
        assertEquals(200, withoutSession.statusCode());
        assertTrue(withoutSession.body().contains("Enter your landing password"));
        assertEquals(0, countVotesForRequest(dataSource, requestId));

        HttpResponse<String> login = postForm(server, "/login", "password=" + SHARED_LANDING_PASSWORD, Map.of());
        assertEquals(302, login.statusCode());
        String sessionCookie = firstCookiePair(login.headers().firstValue("set-cookie").orElse(""));

        HttpResponse<String> page = get(server, "/", Map.of("Cookie", sessionCookie));
        String csrf = extractCsrfToken(page.body());

        HttpResponse<String> withSession = postForm(
                server,
                "/queue/approve",
                "request_id=" + requestId + "&confirm=yes&_csrf=" + csrf,
                Map.of("Cookie", sessionCookie)
        );
        assertEquals(200, withSession.statusCode());
        assertTrue(withSession.body().contains("Approval vote recorded for request"));
        assertEquals(1, countVotesForRequest(dataSource, requestId));
        assertEquals("APPROVED", stateForRequest(dataSource, requestId));
    }

    @Test
    void landingCssContainsResponsiveBurgerRulesWithoutJavaScript() throws Exception {
        HttpResponse<String> css = get(sharedLandingServer, "/assets/css/landing.css", Map.of());
        assertEquals(200, css.statusCode());
        assertTrue(css.body().contains("@media (max-width: 860px)"));
        assertTrue(css.body().contains(".menu-toggle-btn"));
        assertTrue(css.body().contains(".menu-toggle:checked ~ .menu"));
        assertTrue(css.body().contains(".queue-confirm-modal"));
        assertTrue(css.body().contains(".queue-notice-success"));
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

        String configToml = TestConfigBuilder.create(port)
                .withLanding(true)
                .withLandingPasswordProtection(false, landingPasswordFile)
                .withLandingTemplate(templateDir)
                .withLandingStatic(staticDir)
                .withLandingAutoReload(false)
                .withTelegramApiBaseUrl(secretFile, "http://127.0.0.1:" + telegramApiPort)
                .build();

        Path configFile = tempDir.resolve("config-telegram-%d.toml".formatted(System.nanoTime()));
        Files.writeString(configFile, configToml, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);

        KonkinConfig config = KonkinConfig.load(configFile.toString());
        KonkinWebServer server = new KonkinWebServer(config, "test-version");
        server.start();

        try (RunningServer runningServer = new RunningServer(server, URI.create("http://127.0.0.1:" + port))) {
            waitForHealth(port);

            HttpResponse<String> root = get(runningServer, "/", Map.of());
            assertEquals(200, root.statusCode());
            assertTrue(root.body().contains("href=\"/auth_channels/telegram\""));
            assertFalse(root.body().contains("Telegram Broadcast"));
            assertFalse(root.body().contains(">github<"));
            assertTrue(root.body().contains("View on GitHub"));

            HttpResponse<String> telegramPage = get(runningServer, "/auth_channels/telegram", Map.of());
            assertEquals(200, telegramPage.statusCode());
            assertTrue(telegramPage.body().contains("Telegram Broadcast"));
            assertTrue(telegramPage.body().contains("action=\"/auth_channels/telegram/send\""));
            assertFalse(telegramPage.body().contains(">github<"));
            assertTrue(telegramPage.body().contains("View on GitHub"));

            HttpResponse<String> telegramSubmit = postForm(
                    runningServer,
                    "/auth_channels/telegram/send",
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

        String configToml = TestConfigBuilder.create(port)
                .withLanding(true)
                .withLandingPasswordProtection(false, landingPasswordFile)
                .withLandingTemplate(templateDir)
                .withLandingStatic(staticDir)
                .withLandingAutoReload(false)
                .withTelegramApiBaseUrl(secretFile, "http://127.0.0.1:65534")
                .build();

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

        String configToml = TestConfigBuilder.create(port)
                .withLanding(true)
                .withLandingPasswordProtection(false, landingPasswordFile)
                .withLandingTemplate(templateDir)
                .withLandingStatic(staticDir)
                .withLandingAutoReload(false)
                .withTelegramApiBaseUrl(secretFile, "http://127.0.0.1:" + telegramApiPort)
                .build();

        Path configFile = tempDir.resolve("config-telegram-no-auto-approve-%d.toml".formatted(System.nanoTime()));
        Files.writeString(configFile, configToml, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);

        KonkinConfig config = KonkinConfig.load(configFile.toString());
        KonkinWebServer server = new KonkinWebServer(config, "test-version");
        server.start();

        try (RunningServer runningServer = new RunningServer(server, URI.create("http://127.0.0.1:" + port))) {
            waitForHealth(port);

            HttpResponse<String> telegramPage = get(runningServer, "/auth_channels/telegram", Map.of());
            assertEquals(200, telegramPage.statusCode());
            assertTrue(telegramPage.body().contains(discoveredChatId));

            HttpResponse<String> telegramSubmit = postForm(
                    runningServer,
                    "/auth_channels/telegram/send",
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
            assertFalse(secretContent.contains("chat-ids=" + approvedChatId + "," + discoveredChatId));
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

        String firstConfigToml = TestConfigBuilder.create(firstPort)
                .withLanding(true)
                .withLandingPasswordProtection(false, landingPasswordFile)
                .withLandingTemplate(templateDir)
                .withLandingStatic(staticDir)
                .withLandingAutoReload(false)
                .withTelegramApiBaseUrl(secretFile, "http://127.0.0.1:" + telegramApiPort)
                .build();

        String secondConfigToml = TestConfigBuilder.create(secondPort)
                .withLanding(true)
                .withLandingPasswordProtection(false, landingPasswordFile)
                .withLandingTemplate(templateDir)
                .withLandingStatic(staticDir)
                .withLandingAutoReload(false)
                .withTelegramApiBaseUrl(secretFile, "http://127.0.0.1:" + telegramApiPort)
                .build();

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

                HttpResponse<String> telegramPage = get(runningServer, "/auth_channels/telegram", Map.of());
                assertEquals(200, telegramPage.statusCode());
                assertTrue(telegramPage.body().contains(discoveredChatId));

                HttpResponse<String> telegramSubmit = postForm(
                        runningServer,
                        "/auth_channels/telegram/send",
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
            assertFalse(secretAfterFirstRun.contains("chat-ids=" + approvedChatId + "," + discoveredChatId));

            capturedPayload.set("");

            KonkinConfig secondConfig = KonkinConfig.load(secondConfigFile.toString());
            KonkinWebServer secondServer = new KonkinWebServer(secondConfig, "test-version");
            secondServer.start();

            try (RunningServer runningServer = new RunningServer(secondServer, URI.create("http://127.0.0.1:" + secondPort))) {
                waitForHealth(secondPort);

                HttpResponse<String> telegramPage = get(runningServer, "/auth_channels/telegram", Map.of());
                assertEquals(200, telegramPage.statusCode());
                assertTrue(telegramPage.body().contains(discoveredChatId));

                HttpResponse<String> telegramSubmit = postForm(
                        runningServer,
                        "/auth_channels/telegram/send",
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
            assertFalse(secretAfterSecondRun.contains("chat-ids=" + approvedChatId + "," + discoveredChatId));
        } finally {
            telegramApi.stop(0);
        }
    }

    @Test
    void telegramApprovePersistsMetadataAndRendersApprovedChatDisplayName() throws Exception {
        int telegramApiPort = freePort();
        String discoveredChatId = "-100555666777";

        HttpServer telegramApi = HttpServer.create(new InetSocketAddress("127.0.0.1", telegramApiPort), 0);
        telegramApi.createContext("/bottest-bot-token/getUpdates", exchange -> {
            try (exchange) {
                if (!"GET".equals(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(405, -1);
                    return;
                }

                byte[] body = ("""
                        {"ok":true,"result":[{"update_id":10,"message":{"chat":{"id":"%s","type":"group","title":"Konkin Ops","username":"konkin_ops"}}}]}
                        """).formatted(discoveredChatId).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        telegramApi.start();

        int port = freePort();
        Path landingPasswordFile = tempDir.resolve("unused-landing.password");
        Path secretFile = tempDir.resolve("telegram-approve.secret");
        Files.writeString(
                secretFile,
                "chat-ids=REPLACE_WITH_TELEGRAM_CHAT_IDS\nbot-token=test-bot-token\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW
        );

        Path templateDir = Path.of("src/main/resources/templates").toAbsolutePath().normalize();
        Path staticDir = Path.of("src/main/resources/static").toAbsolutePath().normalize();

        String configToml = TestConfigBuilder.create(port)
                .withLanding(true)
                .withLandingPasswordProtection(false, landingPasswordFile)
                .withLandingTemplate(templateDir)
                .withLandingStatic(staticDir)
                .withLandingAutoReload(false)
                .withTelegramApiBaseUrl(secretFile, "http://127.0.0.1:" + telegramApiPort)
                .build();

        Path configFile = tempDir.resolve("config-telegram-approve-%d.toml".formatted(System.nanoTime()));
        Files.writeString(configFile, configToml, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);

        KonkinConfig config = KonkinConfig.load(configFile.toString());
        KonkinWebServer server = new KonkinWebServer(config, "test-version");
        server.start();

        try (RunningServer runningServer = new RunningServer(server, URI.create("http://127.0.0.1:" + port))) {
            waitForHealth(port);

            HttpResponse<String> telegramPage = get(runningServer, "/auth_channels/telegram", Map.of());
            assertEquals(200, telegramPage.statusCode());
            assertTrue(telegramPage.body().contains(discoveredChatId));
            assertTrue(telegramPage.body().contains("Konkin Ops"));

            HttpResponse<String> approve = postForm(
                    runningServer,
                    "/auth_channels/telegram/approve",
                    "chat_id=" + discoveredChatId + "&chat_type=group&chat_title=Konkin+Ops&chat_username=konkin_ops",
                    Map.of()
            );
            assertEquals(200, approve.statusCode());
            assertTrue(approve.body().contains("Approved Telegram chat"));
            assertTrue(approve.body().contains("Konkin Ops"));
            assertTrue(approve.body().contains("/auth_channels/telegram/unapprove"));

            String secret = Files.readString(secretFile, StandardCharsets.UTF_8);
            assertTrue(secret.contains("chat-ids=" + discoveredChatId));
            assertTrue(secret.contains("chat-type." + discoveredChatId + "=group"));
            assertTrue(secret.contains("chat-title." + discoveredChatId + "=Konkin Ops"));
            assertTrue(secret.contains("chat-username." + discoveredChatId + "=konkin_ops"));
        } finally {
            telegramApi.stop(0);
        }
    }

    @Test
    void telegramUnapproveSupportsNoJsConfirmationFlowAndPreservesMetadata() throws Exception {
        int telegramApiPort = freePort();
        String approvedChatId = "-100123456789";

        HttpServer telegramApi = HttpServer.create(new InetSocketAddress("127.0.0.1", telegramApiPort), 0);
        telegramApi.createContext("/bottest-bot-token/getUpdates", exchange -> {
            try (exchange) {
                if (!"GET".equals(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(405, -1);
                    return;
                }

                byte[] body = "{\"ok\":true,\"result\":[]}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        telegramApi.start();

        int port = freePort();
        Path landingPasswordFile = tempDir.resolve("unused-landing.password");
        Path secretFile = tempDir.resolve("telegram-unapprove.secret");
        Files.writeString(
                secretFile,
                """
                bot-token=test-bot-token
                chat-ids=%s
                chat-type.%s=group
                chat-title.%s=Ops Chat
                chat-username.%s=ops_room
                """.formatted(approvedChatId, approvedChatId, approvedChatId, approvedChatId),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW
        );

        Path templateDir = Path.of("src/main/resources/templates").toAbsolutePath().normalize();
        Path staticDir = Path.of("src/main/resources/static").toAbsolutePath().normalize();

        String configToml = TestConfigBuilder.create(port)
                .withLanding(true)
                .withLandingPasswordProtection(false, landingPasswordFile)
                .withLandingTemplate(templateDir)
                .withLandingStatic(staticDir)
                .withLandingAutoReload(false)
                .withTelegramApiBaseUrl(secretFile, "http://127.0.0.1:" + telegramApiPort)
                .build();

        Path configFile = tempDir.resolve("config-telegram-unapprove-%d.toml".formatted(System.nanoTime()));
        Files.writeString(configFile, configToml, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);

        KonkinConfig config = KonkinConfig.load(configFile.toString());
        KonkinWebServer server = new KonkinWebServer(config, "test-version");
        server.start();

        try (RunningServer runningServer = new RunningServer(server, URI.create("http://127.0.0.1:" + port))) {
            waitForHealth(port);

            HttpResponse<String> pendingConfirm = postForm(
                    runningServer,
                    "/auth_channels/telegram/unapprove",
                    "chat_id=" + approvedChatId,
                    Map.of()
            );
            assertEquals(200, pendingConfirm.statusCode());
            assertTrue(pendingConfirm.body().contains("Please confirm to unapprove chat"));
            assertTrue(pendingConfirm.body().contains("queue-confirm-panel"));
            assertTrue(pendingConfirm.body().contains("confirm unapprove"));

            String secretBeforeConfirm = Files.readString(secretFile, StandardCharsets.UTF_8);
            assertTrue(secretBeforeConfirm.contains("chat-ids=" + approvedChatId));

            HttpResponse<String> confirmed = postForm(
                    runningServer,
                    "/auth_channels/telegram/unapprove",
                    "chat_id=" + approvedChatId + "&confirm=yes",
                    Map.of()
            );
            assertEquals(200, confirmed.statusCode());
            assertTrue(confirmed.body().contains("Unapproved Telegram chat"));

            String secretAfterConfirm = Files.readString(secretFile, StandardCharsets.UTF_8);
            assertTrue(secretAfterConfirm.contains("chat-ids=REPLACE_WITH_TELEGRAM_CHAT_IDS"));
            assertTrue(secretAfterConfirm.contains("chat-title." + approvedChatId + "=Ops Chat"));
            assertTrue(secretAfterConfirm.contains("chat-username." + approvedChatId + "=ops_room"));
        } finally {
            telegramApi.stop(0);
        }
    }

    @Test
    void telegramResetSupportsNoJsConfirmationFlowAndClearsApprovedChatIds() throws Exception {
        int telegramApiPort = freePort();
        String approvedChatIdOne = "-100111222333";
        String approvedChatIdTwo = "-100444555666";

        HttpServer telegramApi = HttpServer.create(new InetSocketAddress("127.0.0.1", telegramApiPort), 0);
        telegramApi.createContext("/bottest-bot-token/getUpdates", exchange -> {
            try (exchange) {
                if (!"GET".equals(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(405, -1);
                    return;
                }

                byte[] body = "{\"ok\":true,\"result\":[]}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        telegramApi.start();

        int port = freePort();
        Path landingPasswordFile = tempDir.resolve("unused-landing.password");
        Path secretFile = tempDir.resolve("telegram-reset.secret");
        Files.writeString(
                secretFile,
                """
                bot-token=test-bot-token
                chat-ids=%s,%s
                chat-title.%s=Ops One
                chat-title.%s=Ops Two
                """.formatted(approvedChatIdOne, approvedChatIdTwo, approvedChatIdOne, approvedChatIdTwo),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW
        );

        Path templateDir = Path.of("src/main/resources/templates").toAbsolutePath().normalize();
        Path staticDir = Path.of("src/main/resources/static").toAbsolutePath().normalize();

        String configToml = TestConfigBuilder.create(port)
                .withLanding(true)
                .withLandingPasswordProtection(false, landingPasswordFile)
                .withLandingTemplate(templateDir)
                .withLandingStatic(staticDir)
                .withLandingAutoReload(false)
                .withTelegramApiBaseUrl(secretFile, "http://127.0.0.1:" + telegramApiPort)
                .build();

        Path configFile = tempDir.resolve("config-telegram-reset-%d.toml".formatted(System.nanoTime()));
        Files.writeString(configFile, configToml, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);

        KonkinConfig config = KonkinConfig.load(configFile.toString());
        KonkinWebServer server = new KonkinWebServer(config, "test-version");
        server.start();

        try (RunningServer runningServer = new RunningServer(server, URI.create("http://127.0.0.1:" + port))) {
            waitForHealth(port);

            HttpResponse<String> pendingConfirm = postForm(runningServer, "/auth_channels/telegram/reset", "", Map.of());
            assertEquals(200, pendingConfirm.statusCode());
            assertTrue(pendingConfirm.body().contains("Please confirm reset of all approved Telegram chats."));
            assertTrue(pendingConfirm.body().contains("confirm reset"));

            String secretBeforeConfirm = Files.readString(secretFile, StandardCharsets.UTF_8);
            assertTrue(secretBeforeConfirm.contains("chat-ids=" + approvedChatIdOne + "," + approvedChatIdTwo));

            HttpResponse<String> confirmed = postForm(runningServer, "/auth_channels/telegram/reset", "confirm=yes", Map.of());
            assertEquals(200, confirmed.statusCode());
            assertTrue(confirmed.body().contains("Reset persisted approved Telegram chats."));

            String secretAfterConfirm = Files.readString(secretFile, StandardCharsets.UTF_8);
            assertTrue(secretAfterConfirm.contains("chat-ids=REPLACE_WITH_TELEGRAM_CHAT_IDS"));
            assertTrue(secretAfterConfirm.contains("chat-title." + approvedChatIdOne + "=Ops One"));
            assertTrue(secretAfterConfirm.contains("chat-title." + approvedChatIdTwo + "=Ops Two"));
        } finally {
            telegramApi.stop(0);
        }
    }

    @Test
    void telegramStartupSyncPreservesExistingMetadataWhenMergingConfiguredChatIds() throws Exception {
        int port = freePort();
        String configuredChatId = "-100999000111";
        String existingSecretChatId = "-100123456789";

        Path landingPasswordFile = tempDir.resolve("unused-landing.password");
        Path secretFile = tempDir.resolve("telegram-sync.secret");
        Files.writeString(
                secretFile,
                """
                bot-token=test-bot-token
                chat-ids=%s
                chat-title.%s=Synced Ops
                chat-username.%s=synced_ops
                """.formatted(existingSecretChatId, existingSecretChatId, existingSecretChatId),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW
        );

        Path templateDir = Path.of("src/main/resources/templates").toAbsolutePath().normalize();
        Path staticDir = Path.of("src/main/resources/static").toAbsolutePath().normalize();

        String configToml = TestConfigBuilder.create(port)
                .withLanding(true)
                .withLandingPasswordProtection(false, landingPasswordFile)
                .withLandingTemplate(templateDir)
                .withLandingStatic(staticDir)
                .withLandingAutoReload(false)
                .withTelegramApiBaseUrlAndChatIds(secretFile, "http://127.0.0.1:65534", configuredChatId)
                .build();

        Path configFile = tempDir.resolve("config-telegram-sync-%d.toml".formatted(System.nanoTime()));
        Files.writeString(configFile, configToml, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);

        KonkinConfig config = KonkinConfig.load(configFile.toString());
        KonkinWebServer server = new KonkinWebServer(config, "test-version");
        server.start();

        try (RunningServer runningServer = new RunningServer(server, URI.create("http://127.0.0.1:" + port))) {
            waitForHealth(port);
        }

        String secretAfterStartup = Files.readString(secretFile, StandardCharsets.UTF_8);
        assertTrue(secretAfterStartup.contains("chat-ids=" + configuredChatId + "," + existingSecretChatId));
        assertTrue(secretAfterStartup.contains("chat-title." + existingSecretChatId + "=Synced Ops"));
        assertTrue(secretAfterStartup.contains("chat-username." + existingSecretChatId + "=synced_ops"));
    }

    @Test
    void landingProtectedSupportsFullPasswordSessionFlowForRootAndLog() throws Exception {
        RunningServer server = sharedProtectedServer;

        HttpResponse<String> rootWithoutSession = get(server, "/", Map.of());
        assertEquals(200, rootWithoutSession.statusCode());
        assertTrue(rootWithoutSession.body().contains("Enter your landing password"));

        HttpResponse<String> logWithoutSession = get(server, "/history", Map.of());
        assertEquals(200, logWithoutSession.statusCode());
        assertTrue(logWithoutSession.body().contains("Enter your landing password"));

        HttpResponse<String> badLogin = postForm(server, "/login", "password=wrong", Map.of());
        assertEquals(401, badLogin.statusCode());
        assertTrue(badLogin.body().contains("Invalid password"));

        HttpResponse<String> okLogin = postForm(server, "/login", "password=" + SHARED_LANDING_PASSWORD, Map.of());
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
        assertFalse(rootWithSession.body().contains("History"));

        HttpResponse<String> logWithSession = get(server, "/history", Map.of("Cookie", sessionCookie));
        assertEquals(200, logWithSession.statusCode());
        assertTrue(logWithSession.body().contains("logout"));
        assertTrue(logWithSession.body().contains("History"));
        assertFalse(logWithSession.body().contains("Authorization Queue"));
        assertTrue(logWithSession.body().contains("menu-active\">history<"));

        HttpResponse<String> logout = postForm(server, "/logout", "", Map.of("Cookie", sessionCookie));
        assertEquals(302, logout.statusCode());
        assertEquals("/", logout.headers().firstValue("location").orElse(""));

        HttpResponse<String> rootAfterLogout = get(server, "/", Map.of("Cookie", sessionCookie));
        assertEquals(200, rootAfterLogout.statusCode());
        assertTrue(rootAfterLogout.body().contains("Enter your landing password"));

        HttpResponse<String> logAfterLogout = get(server, "/history", Map.of("Cookie", sessionCookie));
        assertEquals(200, logAfterLogout.statusCode());
        assertTrue(logAfterLogout.body().contains("Enter your landing password"));
    }

    @Test
    void landingTemplateAutoReloadPicksUpFtlChangesWithoutRestart() throws Exception {
        Path sourceTemplates = Path.of("src/main/resources/templates").toAbsolutePath().normalize();
        Path templateDir = tempDir.resolve("templates");
        Files.createDirectories(templateDir);
        for (String templateName : List.of("layout.ftl", "macros.ftl", "landing.ftl", "landing-log.ftl", "landing-login.ftl")) {
            Files.copy(sourceTemplates.resolve(templateName), templateDir.resolve(templateName));
        }

        Path staticDir = tempDir.resolve("static");
        Files.createDirectories(staticDir);

        int port = freePort();
        String configToml = TestConfigBuilder.create(port)
                .withLanding(true)
                .withLandingPasswordProtection(false, tempDir.resolve("unused-landing.password"))
                .withLandingTemplate(templateDir)
                .withLandingStatic(staticDir)
                .withLandingAutoReload(true, true)
                .build();

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

    @Test
    void historyExportReturnsEmptyCsvWhenNoCompletedRequests() throws Exception {
        RunningServer server = sharedLandingServer;

        // Insert a PENDING request — should not appear in export
        DataSource ds = server.dbManager().dataSource();
        insertApprovalRequest(ds, "req-pending-1", "nonce-pending-1", "PENDING");

        HttpResponse<String> response = get(server, "/history/export", Map.of());
        assertEquals(200, response.statusCode());

        String contentType = response.headers().firstValue("Content-Type").orElse("");
        assertTrue(contentType.contains("text/csv"), "Expected text/csv but was: " + contentType);

        String disposition = response.headers().firstValue("Content-Disposition").orElse("");
        assertTrue(disposition.contains("attachment"), "Expected attachment disposition");
        assertTrue(disposition.contains(".csv"), "Expected .csv filename");

        String body = response.body();
        // Header row must be present
        assertTrue(body.startsWith("\"Type\""), "CSV should start with header row");
        assertTrue(body.contains("\"Cur.\""), "CSV header should contain currency columns");

        // Only header line, no data lines
        String[] lines = body.strip().split("\n");
        assertEquals(1, lines.length, "Should only have the header line when no COMPLETED requests exist");
    }

    @Test
    void historyExportReturnsCoinTrackingCsvForCompletedRequests() throws Exception {
        RunningServer server = sharedLandingServer;
        DataSource ds = server.dbManager().dataSource();

        // Insert two COMPLETED requests
        insertApprovalRequest(ds, "req-export-1", "nonce-export-1", "COMPLETED");
        insertApprovalRequest(ds, "req-export-2", "nonce-export-2", "COMPLETED");

        // Set specific coin/amounts for export rows
        JdbiFactory.create(ds).useHandle(h -> {
            h.createUpdate("""
                UPDATE approval_requests
                SET coin = :coin, tool_name = :tool, amount_native = :amount,
                    to_address = :toAddr, resolved_at = :resolvedAt
                WHERE id = :id
            """)
                    .bind("coin", "bitcoin")
                    .bind("tool", "bitcoin_send")
                    .bind("amount", "0.05")
                    .bind("toAddr", "bc1qexporttest1")
                    .bind("resolvedAt", Instant.parse("2026-01-15T10:30:00Z"))
                    .bind("id", "req-export-1")
                    .execute();

            h.createUpdate("""
                UPDATE approval_requests
                SET coin = :coin, tool_name = :tool, amount_native = :amount,
                    to_address = :toAddr, resolved_at = :resolvedAt
                WHERE id = :id
            """)
                    .bind("coin", "monero")
                    .bind("tool", "monero_send")
                    .bind("amount", "1.5")
                    .bind("toAddr", "4exportTestAddr")
                    .bind("resolvedAt", Instant.parse("2026-01-16T14:00:00Z"))
                    .bind("id", "req-export-2")
                    .execute();

            // Insert execution attempts with txids and fees
            h.createUpdate("""
                INSERT INTO approval_execution_attempts (
                    request_id, attempt_no, started_at, finished_at, result, txid, daemon_fee_native
                ) VALUES (
                    :requestId, :attemptNo, :startedAt, :finishedAt, :result, :txid, :fee
                )
            """)
                    .bind("requestId", "req-export-1")
                    .bind("attemptNo", 1)
                    .bind("startedAt", Instant.parse("2026-01-15T10:29:50Z"))
                    .bind("finishedAt", Instant.parse("2026-01-15T10:30:00Z"))
                    .bind("result", "success")
                    .bind("txid", "btc-txid-abc123")
                    .bind("fee", "0.00001234")
                    .execute();

            h.createUpdate("""
                INSERT INTO approval_execution_attempts (
                    request_id, attempt_no, started_at, finished_at, result, txid, daemon_fee_native
                ) VALUES (
                    :requestId, :attemptNo, :startedAt, :finishedAt, :result, :txid, :fee
                )
            """)
                    .bind("requestId", "req-export-2")
                    .bind("attemptNo", 1)
                    .bind("startedAt", Instant.parse("2026-01-16T13:59:50Z"))
                    .bind("finishedAt", Instant.parse("2026-01-16T14:00:00Z"))
                    .bind("result", "success")
                    .bind("txid", "xmr-txid-def456")
                    .bind("fee", "0.00012")
                    .execute();
        });

        // Also insert a DENIED request — must NOT appear
        insertApprovalRequest(ds, "req-denied-x", "nonce-denied-x", "DENIED");

        HttpResponse<String> response = get(server, "/history/export", Map.of());
        assertEquals(200, response.statusCode());

        String body = response.body();
        String[] lines = body.strip().split("\n");
        assertEquals(3, lines.length, "Expected header + 2 data lines");

        // Verify header
        assertTrue(lines[0].contains("\"Type\""), "First line should be CSV header");
        assertTrue(lines[0].contains("\"Txid\""), "Header should include Txid column");

        // Verify first data row (bitcoin)
        assertTrue(body.contains("\"Withdrawal\""), "Should contain Withdrawal type");
        assertTrue(body.contains("\"BTC\""), "Should contain BTC ticker");
        assertTrue(body.contains("\"0.05\""), "Should contain bitcoin amount");
        assertTrue(body.contains("\"btc-txid-abc123\""), "Should contain bitcoin txid");
        assertTrue(body.contains("\"0.00001234\""), "Should contain bitcoin fee");

        // Verify second data row (monero)
        assertTrue(body.contains("\"XMR\""), "Should contain XMR ticker");
        assertTrue(body.contains("\"1.5\""), "Should contain monero amount");
        assertTrue(body.contains("\"xmr-txid-def456\""), "Should contain monero txid");

        // Verify addresses are in the comment
        assertTrue(body.contains("bc1qexporttest1"), "Should contain bitcoin address in comment");
        assertTrue(body.contains("4exportTestAddr"), "Should contain monero address in comment");
    }

    @Test
    void historyExportRequiresSessionWhenPasswordProtected() throws Exception {
        RunningServer server = sharedProtectedServer;

        // Without session — should redirect to login
        HttpResponse<String> response = get(server, "/history/export", Map.of());
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("login"), "Should show login page when not authenticated");
        assertFalse(response.body().contains("\"Type\""), "Should not contain CSV content");

        // Login to get session cookie
        HttpResponse<String> loginResponse = postForm(server, "/login",
                "password=" + SHARED_LANDING_PASSWORD, Map.of());
        String setCookie = loginResponse.headers().firstValue("Set-Cookie").orElse("");
        assertFalse(setCookie.isEmpty(), "Login should set session cookie");
        String cookie = firstCookiePair(setCookie);

        // With session — should return CSV
        HttpResponse<String> authedResponse = get(server, "/history/export", Map.of("Cookie", cookie));
        assertEquals(200, authedResponse.statusCode());

        String contentType = authedResponse.headers().firstValue("Content-Type").orElse("");
        assertTrue(contentType.contains("text/csv"), "Authenticated export should return text/csv");
        assertTrue(authedResponse.body().startsWith("\"Type\""), "Authenticated export should start with CSV header");
    }

    private static void updateApprovalRequestCoinAndTool(DataSource dataSource, String requestId, String coin, String toolName) {
        JdbiFactory.create(dataSource).useHandle(h ->
                h.createUpdate("UPDATE approval_requests SET coin = :coin, tool_name = :tool WHERE id = :id")
                        .bind("coin", coin)
                        .bind("tool", toolName)
                        .bind("id", requestId)
                        .execute()
        );
    }

    private static void updateApprovalUpdatedAt(DataSource dataSource, String requestId, Instant updatedAt) {
        JdbiFactory.create(dataSource).useHandle(h ->
                h.createUpdate("UPDATE approval_requests SET updated_at = :updatedAt WHERE id = :id")
                        .bind("updatedAt", updatedAt)
                        .bind("id", requestId)
                        .execute()
        );
    }

    private static int countVotesForRequest(DataSource dataSource, String requestId) {
        Long count = JdbiFactory.create(dataSource).withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM approval_votes WHERE request_id = :requestId")
                        .bind("requestId", requestId)
                        .mapTo(Long.class)
                        .one()
        );
        return count == null ? 0 : count.intValue();
    }

    private static String stateForRequest(DataSource dataSource, String requestId) {
        return JdbiFactory.create(dataSource).withHandle(h ->
                h.createQuery("SELECT state FROM approval_requests WHERE id = :id")
                        .bind("id", requestId)
                        .mapTo(String.class)
                        .findOne()
                        .orElse("")
        );
    }

    private static int countTransitionsForRequest(DataSource dataSource, String requestId) {
        Long count = JdbiFactory.create(dataSource).withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM approval_state_transitions WHERE request_id = :requestId")
                        .bind("requestId", requestId)
                        .mapTo(Long.class)
                        .one()
        );
        return count == null ? 0 : count.intValue();
    }

    private static int countTransitionsForRequestAndState(DataSource dataSource, String requestId, String toState) {
        Long count = JdbiFactory.create(dataSource).withHandle(h ->
                h.createQuery("""
                        SELECT COUNT(*)
                        FROM approval_state_transitions
                        WHERE request_id = :requestId
                          AND to_state = :toState
                        """)
                        .bind("requestId", requestId)
                        .bind("toState", toState)
                        .mapTo(Long.class)
                        .one()
        );
        return count == null ? 0 : count.intValue();
    }

    private static int countTransitionsForRequestActor(
            DataSource dataSource,
            String requestId,
            String actorType,
            String actorId
    ) {
        Long count = JdbiFactory.create(dataSource).withHandle(h ->
                h.createQuery("""
                        SELECT COUNT(*)
                        FROM approval_state_transitions
                        WHERE request_id = :requestId
                          AND actor_type = :actorType
                          AND actor_id = :actorId
                        """)
                        .bind("requestId", requestId)
                        .bind("actorType", actorType)
                        .bind("actorId", actorId)
                        .mapTo(Long.class)
                        .one()
        );
        return count == null ? 0 : count.intValue();
    }

    private static int countChannelById(DataSource dataSource, String channelId) {
        Long count = JdbiFactory.create(dataSource).withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM approval_channels WHERE id = :id")
                        .bind("id", channelId)
                        .mapTo(Long.class)
                        .one()
        );
        return count == null ? 0 : count.intValue();
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