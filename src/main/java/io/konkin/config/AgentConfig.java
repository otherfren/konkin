package io.konkin.config;

public record AgentConfig(
        boolean enabled,
        String bind,
        int port,
        String secretFile
) {
}
