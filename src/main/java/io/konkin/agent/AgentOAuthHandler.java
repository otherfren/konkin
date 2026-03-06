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

package io.konkin.agent;

import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.javalin.http.UnauthorizedResponse;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Properties;

public class AgentOAuthHandler {

    private static final String GRANT_TYPE_CLIENT_CREDENTIALS = "client_credentials";

    private static final int MAX_FAILED_ATTEMPTS_PER_MINUTE = 5;
    private static final long FAILED_ATTEMPT_WINDOW_SECONDS = 60L;

    private final AgentTokenStore tokenStore;
    private final String agentName;
    private final String expectedClientId;
    private final String expectedClientSecret;
    private final Deque<Instant> failedAuthAttempts = new ArrayDeque<>();

    public AgentOAuthHandler(String agentName, Path secretFile, AgentTokenStore tokenStore) {
        this.tokenStore = tokenStore;
        this.agentName = agentName;

        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(secretFile, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to read agent secret file: " + secretFile.toAbsolutePath().normalize(),
                    e
            );
        }

        this.expectedClientId = properties.getProperty("client-id", "").trim();
        this.expectedClientSecret = properties.getProperty("client-secret", "").trim();

        if (expectedClientId.isEmpty() || expectedClientSecret.isEmpty()) {
            throw new IllegalStateException(
                    "Invalid agent secret file: missing non-empty 'client-id' or 'client-secret' in "
                            + secretFile.toAbsolutePath().normalize()
            );
        }
    }

    public void handleTokenExchange(Context ctx) {
        String grantType = normalize(ctx.formParam("grant_type"));
        if (!GRANT_TYPE_CLIENT_CREDENTIALS.equals(grantType)) {
            throw new BadRequestResponse("unsupported_grant_type");
        }

        String clientId = normalize(ctx.formParam("client_id"));
        String clientSecret = normalize(ctx.formParam("client_secret"));

        if (isRateLimited()) {
            ctx.status(429);
            ctx.json(Map.of(
                    "error", "rate_limited",
                    "error_description", "too_many_failed_attempts"
            ));
            return;
        }

        if (!constantTimeEquals(expectedClientId, clientId) || !constantTimeEquals(expectedClientSecret, clientSecret)) {
            recordFailedAttempt();
            throw new UnauthorizedResponse("invalid_client");
        }

        String accessToken = tokenStore.issueToken(agentName);
        ctx.json(Map.of(
                "access_token", accessToken,
                "token_type", "Bearer",
                "expires_in", 0
        ));
    }

    private synchronized boolean isRateLimited() {
        purgeFailedAttempts(Instant.now());
        return failedAuthAttempts.size() >= MAX_FAILED_ATTEMPTS_PER_MINUTE;
    }

    private synchronized void recordFailedAttempt() {
        Instant now = Instant.now();
        purgeFailedAttempts(now);
        failedAuthAttempts.addLast(now);
    }

    private synchronized void purgeFailedAttempts(Instant now) {
        Instant cutoff = now.minusSeconds(FAILED_ATTEMPT_WINDOW_SECONDS);
        while (!failedAuthAttempts.isEmpty() && !failedAuthAttempts.peekFirst().isAfter(cutoff)) {
            failedAuthAttempts.removeFirst();
        }
    }

    public boolean validateCredentials(String clientId, String clientSecret) {
        return constantTimeEquals(expectedClientId, clientId) && constantTimeEquals(expectedClientSecret, clientSecret);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * Constant-time string comparison to prevent timing side-channel attacks on secret values.
     */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return a == null && b == null;
        }
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8)
        );
    }
}