package io.konkin.web.service;

import java.util.Map;

/**
 * Authorization queue business logic.
 */
public class AuthQueueService {

    public Map<String, Object> readQueueStatus() {
        return Map.of(
                "pending", 0,
                "lockdown_active", false,
                "message", "Authorization queue is empty. No pending approvals."
        );
    }
}
