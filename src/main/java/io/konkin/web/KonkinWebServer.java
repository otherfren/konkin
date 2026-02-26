package io.konkin.web;

import io.javalin.Javalin;
import io.konkin.config.KonkinConfig;
import io.konkin.security.PasswordFileManager;
import io.konkin.web.controller.AuthQueueController;
import io.konkin.web.controller.HealthController;
import io.konkin.web.service.AuthQueueService;
import io.konkin.web.service.HealthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

public class KonkinWebServer {

    private static final Logger log = LoggerFactory.getLogger(KonkinWebServer.class);

    private final KonkinConfig config;
    private final String version;

    private Javalin app;

    public KonkinWebServer(KonkinConfig config, String version) {
        this.config = config;
        this.version = version;
    }

    public void start() {
        HealthService healthService = new HealthService(version);
        HealthController healthController = new HealthController(healthService);

        AuthQueueService authQueueService = new AuthQueueService();
        PasswordFileManager passwordFileManager = null;
        if (config.authQueueEnabled() && config.authQueuePasswordProtectionEnabled()) {
            passwordFileManager = PasswordFileManager.bootstrap(Path.of(config.authQueuePasswordFile()));
        }
        AuthQueueController authQueueController = new AuthQueueController(
                authQueueService,
                config.authQueuePasswordProtectionEnabled(),
                passwordFileManager
        );

        app = Javalin.create(javalinConfig -> {
            javalinConfig.showJavalinBanner = false;
            javalinConfig.jetty.modifyServer(server -> server.setStopTimeout(3_000));
        });

        app.get("/api/v1/health", healthController::handle);

        if (config.authQueueEnabled()) {
            app.get("/api/v1/auth_queue", authQueueController::handle);
        }

        app.start(config.host(), config.port());

        log.info("KONKIN server running at http://{}:{}", config.host(), config.port());
        log.info("  /api/v1/health       — health check");
        if (config.authQueueEnabled()) {
            log.info("  /api/v1/auth_queue   — approval queue status (apiKeyProtected={})",
                    config.authQueuePasswordProtectionEnabled());
        } else {
            log.info("  /api/v1/auth_queue   — disabled via config");
        }
    }

    public void stop() {
        if (app != null) {
            app.stop();
        }
    }
}
