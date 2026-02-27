package io.konkin.api;

import io.javalin.http.Context;
import io.konkin.web.service.HealthService;

/**
 * HTTP controller for /api/v1/health.
 */
public class HealthController {

    private final HealthService healthService;

    public HealthController(HealthService healthService) {
        this.healthService = healthService;
    }

    public void handle(Context ctx) {
        ctx.json(healthService.currentStatus());
    }
}
