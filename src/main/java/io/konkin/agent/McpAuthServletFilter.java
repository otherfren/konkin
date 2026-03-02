package io.konkin.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Objects;

public class McpAuthServletFilter implements Filter {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String GRANT_TYPE_CLIENT_CREDENTIALS = "client_credentials";
    private static final int MAX_FAILED_ATTEMPTS_PER_MINUTE = 5;
    private static final long FAILED_ATTEMPT_WINDOW_SECONDS = 60L;

    private final String agentName;
    private final AgentTokenStore tokenStore;
    private final AgentOAuthHandler oauthHandler;
    private final Deque<Instant> failedAuthAttempts = new ArrayDeque<>();

    public McpAuthServletFilter(String agentName, AgentTokenStore tokenStore, AgentOAuthHandler oauthHandler) {
        this.agentName = Objects.requireNonNull(agentName);
        this.tokenStore = Objects.requireNonNull(tokenStore);
        this.oauthHandler = Objects.requireNonNull(oauthHandler);
    }

    @Override
    public void init(FilterConfig filterConfig) {
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest request = (HttpServletRequest) servletRequest;
        HttpServletResponse response = (HttpServletResponse) servletResponse;

        String path = request.getRequestURI();
        String method = request.getMethod();

        // Handle OAuth token exchange
        if ("POST".equalsIgnoreCase(method) && "/oauth/token".equals(path)) {
            handleOAuthToken(request, response);
            return;
        }

        // All other paths require bearer token
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            sendUnauthorized(response);
            return;
        }

        String token = authorization.substring("Bearer ".length()).trim();
        var validAgent = tokenStore.validateToken(token);
        if (validAgent.isEmpty() || !agentName.equals(validAgent.get())) {
            sendUnauthorized(response);
            return;
        }

        chain.doFilter(servletRequest, servletResponse);
    }

    private void handleOAuthToken(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String grantType = normalize(request.getParameter("grant_type"));
        if (!GRANT_TYPE_CLIENT_CREDENTIALS.equals(grantType)) {
            sendJsonError(response, HttpServletResponse.SC_BAD_REQUEST,
                    Map.of("error", "unsupported_grant_type"));
            return;
        }

        String clientId = normalize(request.getParameter("client_id"));
        String clientSecret = normalize(request.getParameter("client_secret"));

        if (isRateLimited()) {
            sendJsonError(response, 429,
                    Map.of("error", "rate_limited", "error_description", "too_many_failed_attempts"));
            return;
        }

        if (!oauthHandler.validateCredentials(clientId, clientSecret)) {
            recordFailedAttempt();
            sendUnauthorized(response);
            return;
        }

        String accessToken = tokenStore.issueToken(agentName);
        sendJson(response, HttpServletResponse.SC_OK,
                Map.of("access_token", accessToken, "token_type", "Bearer", "expires_in", 0));
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

    private static void sendUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"error\":\"invalid_client\"}");
    }

    private static void sendJson(HttpServletResponse response, int status, Object payload) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        JSON.writeValue(response.getWriter(), payload);
    }

    private static void sendJsonError(HttpServletResponse response, int status, Object payload) throws IOException {
        sendJson(response, status, payload);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    @Override
    public void destroy() {
    }
}
