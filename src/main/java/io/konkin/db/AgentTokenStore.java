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

package io.konkin.db;

import org.jdbi.v3.core.Jdbi;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Persists agent bearer tokens in the H2 database so they survive server restarts.
 * <p>
 * [L-1] Tokens are stored as SHA-256 hashes. The raw token is returned to the caller
 * on {@link #issueToken(String)} and never persisted. On validation, the provided
 * token is hashed and compared against the stored hash.
 * <p>
 * Tokens never expire — they are only removed when:
 * <ul>
 *   <li>a new token is issued and the per-agent limit (2) is exceeded (oldest evicted)</li>
 *   <li>{@link #revokeAll()} is called (server shutdown cleanup)</li>
 *   <li>the user manually deletes rows from the {@code agent_tokens} table</li>
 * </ul>
 */
public class AgentTokenStore {

    private static final int TOKEN_BYTE_LENGTH = 32;
    private static final int MAX_ACTIVE_TOKENS_PER_AGENT = 2;

    // [L-1] Queries now use token_hash column instead of token
    private static final String INSERT_TOKEN =
            "INSERT INTO agent_tokens (token_hash, agent_name, created_at) VALUES (:tokenHash, :agentName, :createdAt)";

    private static final String SELECT_BY_TOKEN_HASH =
            "SELECT agent_name FROM agent_tokens WHERE token_hash = :tokenHash";

    private static final String SELECT_TOKEN_HASHES_FOR_AGENT =
            "SELECT token_hash FROM agent_tokens WHERE agent_name = :agentName ORDER BY created_at ASC";

    private static final String COUNT_TOKENS_FOR_AGENT =
            "SELECT COUNT(*) FROM agent_tokens WHERE agent_name = :agentName";

    private static final String DELETE_TOKEN_HASH =
            "DELETE FROM agent_tokens WHERE token_hash = :tokenHash";

    private static final String DELETE_BY_AGENT =
            "DELETE FROM agent_tokens WHERE agent_name = :agentName";

    private static final String DELETE_ALL =
            "DELETE FROM agent_tokens";

    private final SecureRandom secureRandom = new SecureRandom();
    private final Jdbi jdbi;
    private final ConcurrentMap<String, Instant> lastActivityByAgent = new ConcurrentHashMap<>();

    public AgentTokenStore(DataSource dataSource) {
        this.jdbi = JdbiFactory.create(dataSource);
    }

    public String issueToken(String agentName) {
        String token = generateToken();
        String tokenHash = sha256Hex(token);
        Instant now = Instant.now();

        jdbi.useHandle(h -> {
            h.createUpdate(INSERT_TOKEN)
                    .bind("tokenHash", tokenHash)
                    .bind("agentName", agentName)
                    .bind("createdAt", now)
                    .execute();
        });

        // Evict oldest tokens beyond the per-agent limit
        evictOldTokens(agentName);

        return token;
    }

    public Optional<String> validateToken(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }

        String tokenHash = sha256Hex(token);
        Optional<String> agentName = jdbi.withHandle(h ->
                h.createQuery(SELECT_BY_TOKEN_HASH)
                        .bind("tokenHash", tokenHash)
                        .mapTo(String.class)
                        .findOne()
        );
        return agentName;
    }

    /**
     * Records an authenticated request for the given agent.
     * Must be called only after confirming the token belongs to this agent.
     */
    public void recordActivity(String agentName) {
        lastActivityByAgent.put(agentName, Instant.now());
    }

    /**
     * Returns the last time an MCP request was authenticated for the given agent,
     * or empty if no activity has been recorded since server start.
     */
    public Optional<Instant> lastActivity(String agentName) {
        return Optional.ofNullable(lastActivityByAgent.get(agentName));
    }

    public boolean hasTokens(String agentName) {
        return jdbi.withHandle(h ->
                h.createQuery(COUNT_TOKENS_FOR_AGENT)
                        .bind("agentName", agentName)
                        .mapTo(Long.class)
                        .one() > 0
        );
    }

    public void revokeByAgent(String agentName) {
        jdbi.useHandle(h -> h.createUpdate(DELETE_BY_AGENT)
                .bind("agentName", agentName)
                .execute());
    }

    public void revokeAll() {
        jdbi.useHandle(h -> h.createUpdate(DELETE_ALL).execute());
    }

    private void evictOldTokens(String agentName) {
        jdbi.useHandle(h -> {
            List<String> tokenHashes = h.createQuery(SELECT_TOKEN_HASHES_FOR_AGENT)
                    .bind("agentName", agentName)
                    .mapTo(String.class)
                    .list();

            while (tokenHashes.size() > MAX_ACTIVE_TOKENS_PER_AGENT) {
                String oldest = tokenHashes.remove(0);
                h.createUpdate(DELETE_TOKEN_HASH)
                        .bind("tokenHash", oldest)
                        .execute();
            }
        });
    }

    private String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTE_LENGTH];
        secureRandom.nextBytes(bytes);

        char[] chars = new char[TOKEN_BYTE_LENGTH * 2];
        final char[] hex = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xFF;
            chars[i * 2] = hex[value >>> 4];
            chars[i * 2 + 1] = hex[value & 0x0F];
        }
        return new String(chars);
    }

    /**
     * [L-1] SHA-256 hash of the token, returned as lowercase hex.
     */
    private static String sha256Hex(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            char[] hexChars = new char[hash.length * 2];
            final char[] hexArray = "0123456789abcdef".toCharArray();
            for (int i = 0; i < hash.length; i++) {
                int v = hash[i] & 0xFF;
                hexChars[i * 2] = hexArray[v >>> 4];
                hexChars[i * 2 + 1] = hexArray[v & 0x0F];
            }
            return new String(hexChars);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
