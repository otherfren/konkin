package io.konkin.agent;

import io.konkin.db.JdbiFactory;
import org.jdbi.v3.core.Jdbi;

import javax.sql.DataSource;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persists agent bearer tokens in the H2 database so they survive server restarts.
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

    private static final String INSERT_TOKEN =
            "INSERT INTO agent_tokens (token, agent_name, created_at) VALUES (:token, :agentName, :createdAt)";

    private static final String SELECT_BY_TOKEN =
            "SELECT agent_name FROM agent_tokens WHERE token = :token";

    private static final String SELECT_TOKENS_FOR_AGENT =
            "SELECT token FROM agent_tokens WHERE agent_name = :agentName ORDER BY created_at ASC";

    private static final String DELETE_TOKEN =
            "DELETE FROM agent_tokens WHERE token = :token";

    private static final String DELETE_ALL =
            "DELETE FROM agent_tokens";

    private final SecureRandom secureRandom = new SecureRandom();
    private final Jdbi jdbi;

    public AgentTokenStore(DataSource dataSource) {
        this.jdbi = JdbiFactory.create(dataSource);
    }

    public String issueToken(String agentName) {
        String token = generateToken();
        Instant now = Instant.now();

        jdbi.useHandle(h -> {
            h.createUpdate(INSERT_TOKEN)
                    .bind("token", token)
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

        return jdbi.withHandle(h ->
                h.createQuery(SELECT_BY_TOKEN)
                        .bind("token", token)
                        .mapTo(String.class)
                        .findOne()
        );
    }

    public void revokeAll() {
        jdbi.useHandle(h -> h.createUpdate(DELETE_ALL).execute());
    }

    private void evictOldTokens(String agentName) {
        jdbi.useHandle(h -> {
            List<String> tokens = h.createQuery(SELECT_TOKENS_FOR_AGENT)
                    .bind("agentName", agentName)
                    .mapTo(String.class)
                    .list();

            while (tokens.size() > MAX_ACTIVE_TOKENS_PER_AGENT) {
                String oldest = tokens.remove(0);
                h.createUpdate(DELETE_TOKEN)
                        .bind("token", oldest)
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
}
