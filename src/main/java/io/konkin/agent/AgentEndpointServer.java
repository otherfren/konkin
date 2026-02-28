package io.konkin.agent;

import io.javalin.Javalin;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.UnauthorizedResponse;
import io.javalin.http.sse.SseClient;
import io.konkin.config.KonkinConfig.AgentConfig;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

public class AgentEndpointServer {

    private final String agentName;
    private final String agentType;
    private final AgentConfig config;
    private final AgentTokenStore tokenStore;
    private final ScheduledExecutorService heartbeatScheduler;

    private Javalin app;

    public AgentEndpointServer(String agentName, String agentType, AgentConfig config, AgentTokenStore tokenStore) {
        this.agentName = Objects.requireNonNull(agentName, "agentName");
        this.agentType = Objects.requireNonNull(agentType, "agentType");
        this.config = Objects.requireNonNull(config, "config");
        this.tokenStore = Objects.requireNonNull(tokenStore, "tokenStore");
        this.heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(new HeartbeatThreadFactory(agentName));
    }

    public void start() {
        AgentOAuthHandler oauthHandler = new AgentOAuthHandler(agentName, Path.of(config.secretFile()), tokenStore);

        app = Javalin.create(javalinConfig -> javalinConfig.showJavalinBanner = false);

        app.before(ctx -> {
            String path = ctx.path();
            if ("/oauth/token".equals(path) || "/health".equals(path)) {
                return;
            }

            String authorization = ctx.header("Authorization");
            if (authorization == null || !authorization.startsWith("Bearer ")) {
                throw new UnauthorizedResponse();
            }

            String token = authorization.substring("Bearer ".length()).trim();
            String tokenAgent = tokenStore.validateToken(token).orElseThrow(UnauthorizedResponse::new);
            if (!agentName.equals(tokenAgent)) {
                throw new UnauthorizedResponse();
            }
        });

        app.post("/oauth/token", oauthHandler::handleTokenExchange);
        app.get("/health", ctx -> ctx.json(Map.of("status", "healthy", "agent", agentName, "type", agentType)));

        if ("secondary".equals(agentType)) {
            app.sse("/approvals/pending", this::handlePendingApprovals);
            app.post("/approvals/{requestId}/vote", this::handleVote);
        }

        app.start(config.bind(), config.port());
    }

    public void stop() {
        if (app != null) {
            app.stop();
            app = null;
        }
        heartbeatScheduler.shutdownNow();
        tokenStore.revokeAll();
    }

    private void handlePendingApprovals(SseClient client) {
        sendHeartbeat(client);

        ScheduledFuture<?> heartbeatTask = heartbeatScheduler.scheduleAtFixedRate(
                () -> sendHeartbeat(client),
                30,
                30,
                TimeUnit.SECONDS
        );

        client.onClose(() -> heartbeatTask.cancel(true));
    }

    private void sendHeartbeat(SseClient client) {
        if (client.terminated()) {
            return;
        }

        try {
            client.sendEvent("heartbeat", Map.of("timestamp", Instant.now().toString()));
        } catch (Exception ignored) {
            // client likely disconnected between terminated() check and send
        }
    }

    private void handleVote(io.javalin.http.Context ctx) {
        VoteRequest voteRequest = ctx.bodyAsClass(VoteRequest.class);
        if (voteRequest == null || voteRequest.decision() == null) {
            throw new BadRequestResponse("decision is required");
        }

        String decision = voteRequest.decision().trim().toLowerCase();
        if (!"approve".equals(decision) && !"deny".equals(decision)) {
            throw new BadRequestResponse("decision must be 'approve' or 'deny'");
        }

        String requestId = ctx.pathParam("requestId");
        ctx.json(Map.of(
                "status", "accepted",
                "requestId", requestId,
                "decision", decision
        ));
    }

    public String agentName() {
        return agentName;
    }

    public String agentType() {
        return agentType;
    }

    public String bind() {
        return config.bind();
    }

    public int port() {
        return config.port();
    }

    private record VoteRequest(String decision, String reason) {
    }

    private static final class HeartbeatThreadFactory implements ThreadFactory {
        private final String agentName;

        private HeartbeatThreadFactory(String agentName) {
            this.agentName = agentName;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "agent-heartbeat-" + agentName);
            thread.setDaemon(true);
            return thread;
        }
    }
}
