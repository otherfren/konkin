package io.konkin.agent;

import io.javalin.Javalin;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.HttpStatus;
import io.javalin.http.UnauthorizedResponse;
import io.javalin.http.sse.SseClient;
import io.konkin.agent.primary.PrimaryAgentConfigRequirementsService;
import io.konkin.agent.primary.contract.PrimaryAgentContracts.SendCoinActionAcceptedResponse;
import io.konkin.agent.primary.contract.PrimaryAgentContracts.SendCoinActionRequest;
import io.konkin.config.KonkinConfig;
import io.konkin.config.KonkinConfig.AgentConfig;
import io.konkin.db.AuthQueueStore;
import io.konkin.db.entity.ApprovalRequestRow;
import io.konkin.db.entity.StateTransitionRow;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
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
    private final PrimaryAgentConfigRequirementsService primaryConfigRequirementsService;
    private final AuthQueueStore authQueueStore;
    private final KonkinConfig runtimeConfig;

    private Javalin app;

    public AgentEndpointServer(
            String agentName,
            String agentType,
            AgentConfig config,
            AgentTokenStore tokenStore,
            PrimaryAgentConfigRequirementsService primaryConfigRequirementsService,
            AuthQueueStore authQueueStore,
            KonkinConfig runtimeConfig
    ) {
        this.agentName = Objects.requireNonNull(agentName, "agentName");
        this.agentType = Objects.requireNonNull(agentType, "agentType");
        this.config = Objects.requireNonNull(config, "config");
        this.tokenStore = Objects.requireNonNull(tokenStore, "tokenStore");
        this.primaryConfigRequirementsService = primaryConfigRequirementsService;
        this.authQueueStore = authQueueStore;
        this.runtimeConfig = runtimeConfig;
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

        if ("primary".equals(agentType)) {
            app.get("/runtime/config/requirements", this::handleRuntimeConfigRequirements);
            app.post("/coins/{coin}/actions/send", this::handleSendCoinAction);
        }

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

    private void handleRuntimeConfigRequirements(io.javalin.http.Context ctx) {
        if (primaryConfigRequirementsService == null) {
            throw new IllegalStateException("Primary runtime config requirements service is not configured.");
        }

        String coin = ctx.queryParam("coin");
        ctx.json(primaryConfigRequirementsService.evaluate(coin));
    }

    private void handleSendCoinAction(io.javalin.http.Context ctx) {
        if (authQueueStore == null || runtimeConfig == null) {
            throw new IllegalStateException("Primary send-action service is not configured.");
        }

        String coin = normalizeCoin(ctx.pathParam("coin"));
        if (!"bitcoin".equals(coin)) {
            throw new BadRequestResponse("Only 'bitcoin' coin is currently supported for send action.");
        }

        SendCoinActionRequest request = ctx.bodyAsClass(SendCoinActionRequest.class);
        if (request == null) {
            throw new BadRequestResponse("Request body is required.");
        }

        String toAddress = requireNonBlank(request.toAddress(), "toAddress is required");
        String amountNative = requireNonBlank(request.amountNative(), "amountNative is required");
        String feePolicy = optionalTrim(request.feePolicy());
        String feeCapNative = optionalTrim(request.feeCapNative());
        String memo = optionalTrim(request.memo());

        Instant now = Instant.now();
        String requestId = "req-" + UUID.randomUUID();
        String nonceUuid = UUID.randomUUID().toString();
        String payloadHash = sha256Hex(String.join("|",
                coin,
                toAddress,
                amountNative,
                Objects.toString(feePolicy, ""),
                Objects.toString(feeCapNative, ""),
                Objects.toString(memo, "")
        ));
        String nonceComposite = coin + "|" + nonceUuid + "|" + payloadHash;

        int minApprovalsRequired = runtimeConfig.bitcoin().auth().minApprovalsRequired();

        ApprovalRequestRow row = new ApprovalRequestRow(
                requestId,
                coin,
                "wallet_send",
                optionalTrim(ctx.header("X-Request-Session-Id")),
                nonceUuid,
                payloadHash,
                nonceComposite,
                toAddress,
                amountNative,
                feePolicy,
                feeCapNative,
                memo,
                now,
                now.plus(30, ChronoUnit.MINUTES),
                "QUEUED",
                "queued_for_approval",
                "Request accepted and queued for approval",
                minApprovalsRequired,
                0,
                0,
                "manual",
                now,
                now,
                null
        );

        authQueueStore.insertApprovalRequest(row);
        authQueueStore.insertStateTransition(new StateTransitionRow(
                0L,
                requestId,
                null,
                "QUEUED",
                "primary_agent",
                agentName,
                "queued_for_approval",
                now
        ));

        ctx.status(HttpStatus.ACCEPTED);
        ctx.json(new SendCoinActionAcceptedResponse(
                "accepted",
                requestId,
                coin,
                "send",
                "QUEUED"
        ));
    }

    private static String normalizeCoin(String coin) {
        if (coin == null || coin.isBlank()) {
            throw new BadRequestResponse("coin path parameter is required");
        }
        return coin.trim().toLowerCase();
    }

    private static String requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new BadRequestResponse(message);
        }
        return value.trim();
    }

    private static String optionalTrim(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm is not available", e);
        }
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
