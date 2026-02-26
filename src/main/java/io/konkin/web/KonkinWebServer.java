package io.konkin.web;

import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import io.konkin.config.KonkinConfig;
import io.konkin.security.PasswordFileManager;
import io.konkin.web.controller.AuthQueueController;
import io.konkin.web.controller.HealthController;
import io.konkin.web.controller.LandingPageController;
import io.konkin.web.service.AuthQueueService;
import io.konkin.web.service.HealthService;
import io.konkin.web.service.LandingPageService;
import io.konkin.web.service.LandingResourceWatcher;
import io.konkin.web.service.TelegramSecretService;
import io.konkin.web.service.TelegramService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

public class KonkinWebServer {

    private static final Logger log = LoggerFactory.getLogger(KonkinWebServer.class);

    private final KonkinConfig config;
    private final String version;

    private Javalin app;
    private LandingResourceWatcher landingResourceWatcher;
    private boolean running;

    public KonkinWebServer(KonkinConfig config, String version) {
        this.config = config;
        this.version = version;
    }

    public void start() {
        running = false;

        HealthService healthService = new HealthService(version);
        HealthController healthController = new HealthController(healthService);

        AuthQueueService authQueueService = new AuthQueueService();
        PasswordFileManager authQueuePasswordFileManager = null;
        if (config.authQueueEnabled() && config.authQueuePasswordProtectionEnabled()) {
            authQueuePasswordFileManager = PasswordFileManager.bootstrap(Path.of(config.authQueuePasswordFile()));
        }
        AuthQueueController authQueueController = new AuthQueueController(
                authQueueService,
                config.authQueuePasswordProtectionEnabled(),
                authQueuePasswordFileManager
        );

        LandingPageController landingPageController = null;
        LandingPageService landingPageService = null;
        Path landingTemplateDirectory = null;
        Path landingStaticDirectory = null;

        TelegramService telegramService = null;
        TelegramSecretService telegramSecretService = null;

        if (config.telegramEnabled()) {
            Path secretFile = Path.of(config.telegramSecretFile());
            telegramSecretService = new TelegramSecretService(secretFile);

            if (!telegramSecretService.ensureExists()) {
                logTelegramStartupAction(
                        "Secret file could not be created.",
                        "KONKIN kept startup in safe mode and did not start HTTP services.",
                        secretFile
                );
                return;
            }

            TelegramSecretService.TelegramSecret secret = telegramSecretService.readSecret();
            if (!telegramSecretService.hasConfiguredBotToken(secret)) {
                logTelegramStartupAction(
                        "Missing required key 'bot-token' in telegram secret file.",
                        "KONKIN kept startup in safe mode and did not start HTTP services.",
                        secretFile
                );
                return;
            }

            List<String> mergedChatIds = TelegramSecretService.mergeChatIds(config.telegramChatIds(), secret.chatIds());
            telegramService = new TelegramService(
                    config.telegramApiBaseUrl(),
                    secret.botToken(),
                    mergedChatIds
            );

            if (mergedChatIds.isEmpty()) {
                log.info("Telegram initialized with bot token but no approved chat IDs yet. Use /telegram to approve chats.");
            } else {
                log.info("Telegram initialized with {} approved chat id(s).", mergedChatIds.size());
            }
        }

        if (config.landingEnabled()) {
            landingTemplateDirectory = Path.of(config.landingTemplateDirectory()).toAbsolutePath().normalize();
            landingStaticDirectory = Path.of(config.landingStaticDirectory()).toAbsolutePath().normalize();

            PasswordFileManager landingPasswordFileManager = null;
            if (config.landingPasswordProtectionEnabled()) {
                landingPasswordFileManager = PasswordFileManager.bootstrap(Path.of(config.landingPasswordFile()));
            }

            landingPageService = new LandingPageService(
                    landingTemplateDirectory,
                    config.landingTemplateName(),
                    config.landingStaticHostedPath(),
                    config.landingAutoReloadEnabled(),
                    config.telegramEnabled() && config.landingEnabled()
            );

            landingPageController = new LandingPageController(
                    landingPageService,
                    config.landingPasswordProtectionEnabled(),
                    landingPasswordFileManager,
                    config.telegramEnabled(),
                    config.telegramChatIds(),
                    telegramService,
                    telegramSecretService
            );
        }

        Path staticDirectoryFinal = landingStaticDirectory;
        app = Javalin.create(javalinConfig -> {
            javalinConfig.showJavalinBanner = false;
            javalinConfig.jetty.modifyServer(server -> server.setStopTimeout(3_000));

            if (config.landingEnabled()) {
                javalinConfig.staticFiles.add(staticFileConfig -> {
                    staticFileConfig.hostedPath = config.landingStaticHostedPath();
                    staticFileConfig.directory = staticDirectoryFinal.toString();
                    staticFileConfig.location = Location.EXTERNAL;
                    staticFileConfig.precompress = false;
                });
            }
        });

        app.get("/api/v1/health", healthController::handle);

        if (config.authQueueEnabled()) {
            app.get("/api/v1/auth_queue", authQueueController::handle);
        }

        LandingPageController landingPageControllerFinal = landingPageController;
        LandingPageService landingPageServiceFinal = landingPageService;
        Path landingTemplateDirectoryFinal = landingTemplateDirectory;

        if (config.landingEnabled()) {
            app.get("/", landingPageControllerFinal::handleRoot);
            app.get("/log", landingPageControllerFinal::handleLog);
            app.get("/login", landingPageControllerFinal::handleLoginPage);
            app.post("/login", landingPageControllerFinal::handleLoginSubmit);
            app.post("/logout", landingPageControllerFinal::handleLogout);

            if (config.telegramEnabled()) {
                app.get("/telegram", landingPageControllerFinal::handleTelegramPage);
                app.post("/telegram/approve", landingPageControllerFinal::handleTelegramApprove);
                app.post("/telegram/send", landingPageControllerFinal::handleTelegramSubmit);
            }

            landingResourceWatcher = new LandingResourceWatcher(
                    config.landingAutoReloadEnabled(),
                    config.landingAssetsAutoReloadEnabled(),
                    landingTemplateDirectoryFinal,
                    staticDirectoryFinal,
                    landingPageServiceFinal::clearTemplateCache,
                    landingPageServiceFinal::markStaticAssetsChanged
            );
        }

        app.start(config.host(), config.port());
        running = true;

        if (landingResourceWatcher != null) {
            landingResourceWatcher.start();
        }

        log.info("KONKIN server running at http://{}:{}", config.host(), config.port());
        log.info("  /api/v1/health       — health check");
        if (config.authQueueEnabled()) {
            log.info("  /api/v1/auth_queue   — approval queue status (apiKeyProtected={})",
                    config.authQueuePasswordProtectionEnabled());
        } else {
            log.info("  /api/v1/auth_queue   — disabled via config");
        }

        if (config.landingEnabled()) {
            log.info("  /                    — landing page (passwordLoginProtected={})", config.landingPasswordProtectionEnabled());
            log.info("  /log                 — audit log page (passwordLoginProtected={})", config.landingPasswordProtectionEnabled());
            if (config.telegramEnabled()) {
                log.info("  /telegram            — telegram onboarding and manual send page");
                log.info("  /telegram/approve    — approve discovered telegram chat request");
                log.info("  /telegram/send       — telegram send endpoint");
            } else {
                log.info("  /telegram            — disabled via config");
                log.info("  /telegram/approve    — disabled via config");
                log.info("  /telegram/send       — disabled via config");
            }

            log.info("  {}/*             — static assets from {}",
                    config.landingStaticHostedPath(),
                    staticDirectoryFinal);
            log.info(
                    "Landing auto-reload — templates={}, assets={}",
                    config.landingAutoReloadEnabled() ? "enabled" : "disabled",
                    config.landingAssetsAutoReloadEnabled() ? "enabled" : "disabled"
            );
        } else {
            log.info("  /                    — disabled via config");
            log.info("  /log                 — disabled via config");
            log.info("  /telegram            — disabled via config (landing disabled)");
            log.info("  /telegram/approve    — disabled via config (landing disabled)");
            log.info("  /telegram/send       — disabled via config (landing disabled)");
        }
    }

    private void logTelegramStartupAction(String reason, String actionTaken, Path secretFile) {
        Path absolute = secretFile.toAbsolutePath().normalize();
        log.warn("");
        log.warn("################################################################################");
        log.warn("# KONKIN STARTUP ACTION REQUIRED — TELEGRAM SECRETS");
        log.warn("#");
        log.warn("# Reason      : {}", reason);
        log.warn("# KONKIN did  : {}", actionTaken);
        log.warn("# HTTP server : NOT STARTED (intentional safe stop)");
        log.warn("# Secret file : {}", absolute);
        log.warn("#");
        log.warn("# Required file content:");
        log.warn("#   bot-token=<telegram bot token>");
        log.warn("#   chat-ids=<id1,id2,...>");
        log.warn("#");
        log.warn("# Discovery note:");
        log.warn("#   Each target chat must send at least one message (e.g. /start) to your bot");
        log.warn("#   before it appears in /telegram approval requests.");
        log.warn("#");
        log.warn("# Next step   : Edit the file, replace placeholders, restart KONKIN.");
        log.warn("################################################################################");
        log.warn("");
    }

    public void stop() {
        if (landingResourceWatcher != null) {
            landingResourceWatcher.stop();
            landingResourceWatcher = null;
        }

        if (app != null) {
            app.stop();
            app = null;
        }

        running = false;
    }

    public boolean isRunning() {
        return running;
    }
}
