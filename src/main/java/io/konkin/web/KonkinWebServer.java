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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

public class KonkinWebServer {

    private static final Logger log = LoggerFactory.getLogger(KonkinWebServer.class);

    private final KonkinConfig config;
    private final String version;

    private Javalin app;
    private LandingResourceWatcher landingResourceWatcher;

    public KonkinWebServer(KonkinConfig config, String version) {
        this.config = config;
        this.version = version;
    }

    public void start() {
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
                    config.landingAutoReloadEnabled()
            );

            landingPageController = new LandingPageController(
                    landingPageService,
                    config.landingPasswordProtectionEnabled(),
                    landingPasswordFileManager
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
        }
    }

    public void stop() {
        if (landingResourceWatcher != null) {
            landingResourceWatcher.stop();
            landingResourceWatcher = null;
        }

        if (app != null) {
            app.stop();
        }
    }
}
