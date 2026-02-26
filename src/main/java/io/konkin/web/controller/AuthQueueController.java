package io.konkin.web.controller;

import io.javalin.http.Context;
import io.konkin.security.PasswordFileManager;
import io.konkin.web.service.AuthQueueService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * HTTP controller for /api/v1/auth_queue.
 */
public class AuthQueueController {

    private static final Logger log = LoggerFactory.getLogger(AuthQueueController.class);

    private final AuthQueueService authQueueService;
    private final boolean passwordProtectionEnabled;
    private final PasswordFileManager passwordFileManager;

    public AuthQueueController(
            AuthQueueService authQueueService,
            boolean passwordProtectionEnabled,
            PasswordFileManager passwordFileManager
    ) {
        if (passwordProtectionEnabled && passwordFileManager == null) {
            throw new IllegalArgumentException("passwordFileManager is required when password protection is enabled");
        }
        this.authQueueService = authQueueService;
        this.passwordProtectionEnabled = passwordProtectionEnabled;
        this.passwordFileManager = passwordFileManager;
    }

    public void handle(Context ctx) {
        if (passwordProtectionEnabled && !isAuthorized(ctx)) {
            log.warn("Unauthorized /api/v1/auth_queue request from {}", ctx.ip());
            unauthorized(ctx);
            return;
        }

        ctx.json(authQueueService.readQueueStatus());
    }

    private boolean isAuthorized(Context ctx) {
        String apiKey = ctx.header("X-Api-Key");
        if (apiKey == null || apiKey.isBlank()) {
            return false;
        }
        return passwordFileManager.verifyPassword(apiKey);
    }

    private void unauthorized(Context ctx) {
        ctx.status(401);
        ctx.json(Map.of("error", "unauthorized", "message", "X-Api-Key required for /api/v1/auth_queue"));
    }
}
