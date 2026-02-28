package io.konkin.agent;

import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.javalin.http.UnauthorizedResponse;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

public class AgentOAuthHandler {

    private static final String GRANT_TYPE_CLIENT_CREDENTIALS = "client_credentials";

    private final AgentTokenStore tokenStore;
    private final String agentName;
    private final String expectedClientId;
    private final String expectedClientSecret;

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

        if (!expectedClientId.equals(clientId) || !expectedClientSecret.equals(clientSecret)) {
            throw new UnauthorizedResponse("invalid_client");
        }

        String accessToken = tokenStore.issueToken(agentName);
        ctx.json(Map.of(
                "access_token", accessToken,
                "token_type", "Bearer",
                "expires_in", 3600
        ));
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
