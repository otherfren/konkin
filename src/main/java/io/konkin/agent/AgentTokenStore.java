package io.konkin.agent;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class AgentTokenStore {

    private static final int TOKEN_BYTE_LENGTH = 32;
    private static final int MAX_ACTIVE_TOKENS_PER_AGENT = 2;

    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<String, TokenEntry> tokensByValue = new ConcurrentHashMap<>();
    private final Map<String, Deque<String>> tokensByAgent = new ConcurrentHashMap<>();

    public synchronized String issueToken(String agentName) {
        Instant now = Instant.now();
        purgeExpiredTokens(now);

        String token = generateToken();
        TokenEntry tokenEntry = new TokenEntry(agentName, now.plusSeconds(3600));
        tokensByValue.put(token, tokenEntry);

        Deque<String> agentTokens = tokensByAgent.computeIfAbsent(agentName, ignored -> new ArrayDeque<>());
        agentTokens.addLast(token);

        while (agentTokens.size() > MAX_ACTIVE_TOKENS_PER_AGENT) {
            String evicted = agentTokens.pollFirst();
            if (evicted != null) {
                tokensByValue.remove(evicted);
            }
        }

        return token;
    }

    public synchronized Optional<String> validateToken(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }

        TokenEntry entry = tokensByValue.get(token);
        if (entry == null) {
            return Optional.empty();
        }

        Instant now = Instant.now();
        if (!entry.expiresAt().isAfter(now)) {
            removeToken(token, entry.agentName());
            return Optional.empty();
        }

        return Optional.of(entry.agentName());
    }

    public synchronized void revokeAll() {
        tokensByValue.clear();
        tokensByAgent.clear();
    }

    private void purgeExpiredTokens(Instant now) {
        for (Map.Entry<String, TokenEntry> entry : tokensByValue.entrySet()) {
            if (!entry.getValue().expiresAt().isAfter(now)) {
                removeToken(entry.getKey(), entry.getValue().agentName());
            }
        }
    }

    private void removeToken(String token, String agentName) {
        tokensByValue.remove(token);
        Deque<String> agentTokens = tokensByAgent.get(agentName);
        if (agentTokens == null) {
            return;
        }

        agentTokens.remove(token);
        if (agentTokens.isEmpty()) {
            tokensByAgent.remove(agentName);
        }
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

    public record TokenEntry(String agentName, Instant expiresAt) {
    }
}
