package io.konkin.web.service;

import java.util.Map;

/**
 * Health business logic.
 */
public class HealthService {
    private final String version;

    public HealthService(String version) {
        this.version = version;
    }

    public Map<String, Object> currentStatus() {
        return Map.of(
                "status", "healthy",
                "version", version,
                "database", "connected"
        );
    }
}
