package io.konkin.web.service;

import io.konkin.db.AuthQueueStore;

import java.util.Map;

/**
 * Authorization queue business logic.
 */
public class AuthQueueService {

    private final AuthQueueStore authQueueStore;

    public AuthQueueService() {
        this(null);
    }

    public AuthQueueService(AuthQueueStore authQueueStore) {
        this.authQueueStore = authQueueStore;
    }

    public Map<String, Object> readQueueStatus() {
        int pending = 0;
        boolean lockdownActive = false;

        if (authQueueStore != null) {
            pending = authQueueStore.countOpenRequests();
            lockdownActive = authQueueStore.isLockdownActive();
        }

        String message;
        if (lockdownActive) {
            message = "Authorization queue is in lockdown. New approvals are temporarily paused.";
        } else if (pending <= 0) {
            message = "Authorization queue is empty. No pending approvals.";
        } else {
            message = "Authorization queue has " + pending + " pending approval(s).";
        }

        return Map.of(
                "pending", pending,
                "lockdown_active", lockdownActive,
                "message", message
        );
    }
}
