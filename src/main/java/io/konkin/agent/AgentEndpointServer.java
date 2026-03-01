package io.konkin.agent;

import io.javalin.Javalin;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.HttpStatus;
import io.javalin.http.UnauthorizedResponse;
import io.javalin.http.sse.SseClient;
import io.konkin.agent.primary.PrimaryAgentConfigRequirementsService;
import io.konkin.agent.primary.contract.PrimaryAgentContracts.DecisionEventResponse;
import io.konkin.agent.primary.contract.PrimaryAgentContracts.DecisionStatusResponse;
import io.konkin.agent.primary.contract.PrimaryAgentContracts.SendCoinActionAcceptedResponse;
import io.konkin.agent.primary.contract.PrimaryAgentContracts.SendCoinActionRequest;
import io.konkin.config.KonkinConfig;
import io.konkin.config.KonkinConfig.AgentConfig;
import io.konkin.db.AuthQueueStore;
import io.konkin.db.entity.ApprovalChannelRow;
import io.konkin.db.entity.ApprovalRequestRow;
import io.konkin.db.entity.ExecutionAttemptDetail;
import io.konkin.db.entity.PageResult;
import io.konkin.db.entity.RequestDependencies;
import io.konkin.db.entity.StateTransitionDetail;
import io.konkin.db.entity.StateTransitionRow;
import io.konkin.db.entity.VoteDetail;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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

        if ("driver".equals(agentType)) {
            app.get("/runtime/config/requirements", this::handleRuntimeConfigRequirements);
            app.post("/coins/{coin}/actions/send", this::handleSendCoinAction);
            app.get("/decisions/{requestId}", this::handleDecisionStatus);
            app.sse("/decisions/{requestId}/events", this::handleDecisionEvents);
        }

        if ("auth".equals(agentType)) {
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
        if (authQueueStore == null || runtimeConfig == null) {
            throw new IllegalStateException("Auth approvals stream service is not configured.");
        }

        client.keepAlive();

        Map<String, ApprovalRequestRow> knownPending = new LinkedHashMap<>();
        pollPendingApprovals(client, knownPending);
        sendHeartbeat(client);

        ScheduledFuture<?> heartbeatTask = heartbeatScheduler.scheduleAtFixedRate(
                () -> sendHeartbeat(client),
                30,
                30,
                TimeUnit.SECONDS
        );

        ScheduledFuture<?> pollTask = heartbeatScheduler.scheduleAtFixedRate(
                () -> {
                    try {
                        pollPendingApprovals(client, knownPending);
                    } catch (Exception ignored) {
                        // keep scheduler alive on transient stream/database failures
                    }
                },
                1,
                1,
                TimeUnit.SECONDS
        );

        client.onClose(() -> {
            heartbeatTask.cancel(true);
            pollTask.cancel(true);
        });
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
        if (authQueueStore == null || runtimeConfig == null) {
            throw new IllegalStateException("Auth vote service is not configured.");
        }

        VoteRequest voteRequest = ctx.bodyAsClass(VoteRequest.class);
        if (voteRequest == null || voteRequest.decision() == null) {
            throw new BadRequestResponse("decision is required");
        }

        String decision = voteRequest.decision().trim().toLowerCase();
        if (!"approve".equals(decision) && !"deny".equals(decision)) {
            throw new BadRequestResponse("decision must be 'approve' or 'deny'");
        }

        String requestId = requireNonBlank(ctx.pathParam("requestId"), "requestId path parameter is required");
        ApprovalRequestRow requestRow = authQueueStore.findApprovalRequestById(requestId);
        if (requestRow == null || !isVoteableState(requestRow.state())) {
            ctx.status(HttpStatus.NOT_FOUND);
            ctx.json(Map.of("error", "request_not_found_or_resolved"));
            return;
        }

        if (!isAgentAssignedToCoin(requestRow.coin())) {
            ctx.status(HttpStatus.FORBIDDEN);
            ctx.json(Map.of("error", "agent_not_assigned_to_coin"));
            return;
        }

        String channelId = ensureAuthChannelId();
        List<VoteDetail> existingVotes = authQueueStore.listVotesForRequest(requestId);
        boolean alreadyVoted = existingVotes.stream()
                .anyMatch(vote -> vote.channelId() != null && vote.channelId().equalsIgnoreCase(channelId));
        if (alreadyVoted) {
            ctx.status(HttpStatus.CONFLICT);
            ctx.json(Map.of("error", "already_voted"));
            return;
        }

        Instant now = Instant.now();
        authQueueStore.insertVote(new VoteDetail(
                0L,
                requestId,
                channelId,
                decision,
                optionalTrim(voteRequest.reason()),
                agentName,
                now
        ));

        List<VoteDetail> votes = authQueueStore.listVotesForRequest(requestId);
        int approvalsGranted = (int) votes.stream().filter(v -> "approve".equalsIgnoreCase(v.decision())).count();
        int approvalsDenied = (int) votes.stream().filter(v -> "deny".equalsIgnoreCase(v.decision())).count();

        String previousState = requestRow.state();
        String nextState = previousState;
        String reasonCode = requestRow.stateReasonCode();
        String reasonText = requestRow.stateReasonText();
        Instant resolvedAt = requestRow.resolvedAt();

        if (approvalsDenied > 0) {
            nextState = "DENIED";
            reasonCode = "vote_denied";
            reasonText = "Denied by auth approval vote";
            resolvedAt = now;
        } else if (approvalsGranted >= Math.max(1, requestRow.minApprovalsRequired())) {
            nextState = "APPROVED";
            reasonCode = "approval_threshold_met";
            reasonText = "Minimum approvals reached";
        } else if ("QUEUED".equalsIgnoreCase(previousState)) {
            nextState = "PENDING";
            reasonCode = "awaiting_more_votes";
            reasonText = "Awaiting additional approvals";
        }

        ApprovalRequestRow updated = new ApprovalRequestRow(
                requestRow.id(),
                requestRow.coin(),
                requestRow.toolName(),
                requestRow.requestSessionId(),
                requestRow.nonceUuid(),
                requestRow.payloadHashSha256(),
                requestRow.nonceComposite(),
                requestRow.toAddress(),
                requestRow.amountNative(),
                requestRow.feePolicy(),
                requestRow.feeCapNative(),
                requestRow.memo(),
                requestRow.requestedAt(),
                requestRow.expiresAt(),
                nextState,
                reasonCode,
                reasonText,
                requestRow.minApprovalsRequired(),
                approvalsGranted,
                approvalsDenied,
                requestRow.policyActionAtCreation(),
                requestRow.createdAt(),
                now,
                resolvedAt
        );
        authQueueStore.updateApprovalRequest(updated);

        if (!Objects.equals(previousState, nextState)) {
            authQueueStore.insertStateTransition(new StateTransitionRow(
                    0L,
                    requestId,
                    previousState,
                    nextState,
                    "agent",
                    agentName,
                    reasonCode,
                    now
            ));
        }

        ctx.json(Map.of(
                "status", "accepted",
                "requestId", requestId,
                "decision", decision
        ));
    }

    private void handleRuntimeConfigRequirements(io.javalin.http.Context ctx) {
        if (primaryConfigRequirementsService == null) {
            throw new IllegalStateException("Driver runtime config requirements service is not configured.");
        }

        String coin = ctx.queryParam("coin");
        ctx.json(primaryConfigRequirementsService.evaluate(coin));
    }

    private void handleSendCoinAction(io.javalin.http.Context ctx) {
        if (authQueueStore == null || runtimeConfig == null) {
            throw new IllegalStateException("Driver send-action service is not configured.");
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
                "driver_agent",
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

    private void handleDecisionStatus(io.javalin.http.Context ctx) {
        if (authQueueStore == null) {
            throw new IllegalStateException("Driver decision-status service is not configured.");
        }

        String requestId = requireNonBlank(ctx.pathParam("requestId"), "requestId path parameter is required");
        DecisionStatusResponse status = loadDecisionStatus(requestId);
        if (status == null) {
            ctx.status(HttpStatus.NOT_FOUND);
            return;
        }
        ctx.json(status);
    }

    private void handleDecisionEvents(SseClient client) {
        if (authQueueStore == null) {
            throw new IllegalStateException("Driver decision-events service is not configured.");
        }

        String requestId = requireNonBlank(client.ctx().pathParam("requestId"), "requestId path parameter is required");
        DecisionStatusResponse initial = loadDecisionStatus(requestId);
        if (initial == null) {
            sendDecisionEvent(client, "not_found", requestId, "UNKNOWN", Map.of("message", "request_not_found"));
            client.close();
            return;
        }

        client.keepAlive();

        AtomicReference<String> lastState = new AtomicReference<>(initial.state());
        sendDecisionEvent(client, "snapshot", initial.requestId(), initial.state(), toDecisionPayload(initial));
        if (initial.terminal()) {
            client.close();
            return;
        }

        ScheduledFuture<?> heartbeatTask = heartbeatScheduler.scheduleAtFixedRate(
                () -> sendHeartbeat(client),
                30,
                30,
                TimeUnit.SECONDS
        );

        ScheduledFuture<?> pollTask = heartbeatScheduler.scheduleAtFixedRate(() -> {
            if (client.terminated()) {
                return;
            }

            DecisionStatusResponse current = loadDecisionStatus(requestId);
            if (current == null) {
                sendDecisionEvent(client, "not_found", requestId, "UNKNOWN", Map.of("message", "request_not_found"));
                client.close();
                return;
            }

            String previousState = lastState.getAndSet(current.state());
            if (!Objects.equals(previousState, current.state())) {
                Map<String, Object> payload = new LinkedHashMap<>(toDecisionPayload(current));
                payload.put("fromState", previousState);
                payload.put("toState", current.state());
                sendDecisionEvent(client, "state_transition", current.requestId(), current.state(), payload);
            }

            if (current.terminal()) {
                client.close();
            }
        }, 1, 1, TimeUnit.SECONDS);

        client.onClose(() -> {
            heartbeatTask.cancel(true);
            pollTask.cancel(true);
        });
    }

    private DecisionStatusResponse loadDecisionStatus(String requestId) {
        ApprovalRequestRow row = authQueueStore.findApprovalRequestById(requestId);
        if (row == null) {
            return null;
        }

        String latestReasonCode = row.stateReasonCode();
        String latestReasonText = row.stateReasonText();
        String txid = null;

        RequestDependencies dependencies = authQueueStore.loadRequestDependencies(List.of(requestId)).get(requestId);
        if (dependencies != null) {
            for (StateTransitionDetail transition : dependencies.transitions()) {
                if (transition.reasonCode() != null && !transition.reasonCode().isBlank()) {
                    latestReasonCode = transition.reasonCode();
                }
                if (transition.reasonText() != null && !transition.reasonText().isBlank()) {
                    latestReasonText = transition.reasonText();
                }
            }
            for (ExecutionAttemptDetail attempt : dependencies.executionAttempts()) {
                if (attempt.txid() != null && !attempt.txid().isBlank()) {
                    txid = attempt.txid();
                }
            }
        }

        String state = row.state();
        return new DecisionStatusResponse(
                row.id(),
                row.coin(),
                state,
                isTerminalState(state),
                row.minApprovalsRequired(),
                row.approvalsGranted(),
                row.approvalsDenied(),
                latestReasonCode,
                latestReasonText,
                txid
        );
    }

    private static Map<String, Object> toDecisionPayload(DecisionStatusResponse status) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("terminal", status.terminal());
        payload.put("minApprovalsRequired", status.minApprovalsRequired());
        payload.put("approvalsGranted", status.approvalsGranted());
        payload.put("approvalsDenied", status.approvalsDenied());
        if (status.latestReasonCode() != null) {
            payload.put("latestReasonCode", status.latestReasonCode());
        }
        if (status.latestReasonText() != null) {
            payload.put("latestReasonText", status.latestReasonText());
        }
        if (status.txid() != null) {
            payload.put("txid", status.txid());
        }
        return Map.copyOf(payload);
    }

    private void sendDecisionEvent(
            SseClient client,
            String eventType,
            String requestId,
            String state,
            Map<String, Object> payload
    ) {
        if (client.terminated()) {
            return;
        }

        try {
            client.sendEvent("decision", new DecisionEventResponse(
                    eventType,
                    requestId,
                    state,
                    Instant.now(),
                    payload == null ? Map.of() : Map.copyOf(payload)
            ));
        } catch (Exception ignored) {
            // client likely disconnected between terminated() check and send
        }
    }

    private void pollPendingApprovals(SseClient client, Map<String, ApprovalRequestRow> knownPending) {
        if (client.terminated()) {
            return;
        }

        Map<String, ApprovalRequestRow> currentPending = new LinkedHashMap<>();
        List<ApprovalRequestRow> assignedPending = loadAssignedPendingRequests();
        for (ApprovalRequestRow row : assignedPending) {
            currentPending.put(row.id(), row);
            if (!knownPending.containsKey(row.id())) {
                sendApprovalRequestEvent(client, row);
            }
        }

        for (String previousRequestId : new ArrayList<>(knownPending.keySet())) {
            if (currentPending.containsKey(previousRequestId)) {
                continue;
            }

            ApprovalRequestRow latest = authQueueStore.findApprovalRequestById(previousRequestId);
            if (latest != null && isCancellationState(latest.state())) {
                sendApprovalCancelledEvent(client, latest);
            }
        }

        knownPending.clear();
        knownPending.putAll(currentPending);
    }

    private List<ApprovalRequestRow> loadAssignedPendingRequests() {
        PageResult<ApprovalRequestRow> page = authQueueStore.pagePendingApprovalRequests("requested_at", "asc", 1, 200);
        List<ApprovalRequestRow> assigned = new ArrayList<>();
        for (ApprovalRequestRow row : page.rows()) {
            if (isAgentAssignedToCoin(row.coin())) {
                assigned.add(row);
            }
        }
        return List.copyOf(assigned);
    }

    private void sendApprovalRequestEvent(SseClient client, ApprovalRequestRow row) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("requestId", row.id());
        payload.put("coin", row.coin());
        payload.put("type", toApprovalType(row.toolName()));
        if (row.toAddress() != null) {
            payload.put("to", row.toAddress());
        }
        if (row.amountNative() != null) {
            payload.put("amount", row.amountNative());
        }
        payload.put("nonce", row.nonceComposite());
        payload.put("requestedAt", row.requestedAt());
        payload.put("expiresAt", row.expiresAt());

        sendSseEvent(client, "approval_request", payload);
    }

    private void sendApprovalCancelledEvent(SseClient client, ApprovalRequestRow row) {
        sendSseEvent(client, "approval_cancelled", Map.of(
                "requestId", row.id(),
                "reason", cancellationReason(row)
        ));
    }

    private void sendSseEvent(SseClient client, String eventName, Object payload) {
        if (client.terminated()) {
            return;
        }

        try {
            client.sendEvent(eventName, payload);
        } catch (Exception ignored) {
            // client likely disconnected between terminated() check and send
        }
    }

    private String ensureAuthChannelId() {
        ApprovalChannelRow existing = authQueueStore.findChannelById(agentName);
        if (existing != null) {
            return existing.id();
        }

        try {
            authQueueStore.insertChannel(new ApprovalChannelRow(
                    agentName,
                    "mcp_agent",
                    agentName,
                    true,
                    "agent-endpoint",
                    Instant.now()
            ));
        } catch (RuntimeException ignored) {
            // another concurrent request may create the row first
        }

        ApprovalChannelRow reloaded = authQueueStore.findChannelById(agentName);
        if (reloaded == null) {
            throw new IllegalStateException("Failed to resolve approval channel for auth agent: " + agentName);
        }
        return reloaded.id();
    }

    private boolean isAgentAssignedToCoin(String coin) {
        if (coin == null || runtimeConfig == null) {
            return false;
        }

        String normalized = coin.trim().toLowerCase();
        if (normalized.isEmpty()) {
            return false;
        }

        return switch (normalized) {
            case "bitcoin" -> isAgentAssigned(runtimeConfig.bitcoin());
            case "litecoin" -> isAgentAssigned(runtimeConfig.litecoin());
            case "monero" -> isAgentAssigned(runtimeConfig.monero());
            default -> false;
        };
    }

    private boolean isAgentAssigned(KonkinConfig.CoinConfig coinConfig) {
        if (coinConfig == null || coinConfig.auth() == null || !coinConfig.enabled()) {
            return false;
        }
        for (String channel : coinConfig.auth().mcpAuthChannels()) {
            if (channel != null && channel.equalsIgnoreCase(agentName)) {
                return true;
            }
        }
        return false;
    }

    private static String toApprovalType(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return "unknown";
        }
        String normalized = toolName.trim().toLowerCase();
        if (normalized.endsWith("_send") || normalized.contains("send")) {
            return "send";
        }
        return normalized;
    }

    private static boolean isVoteableState(String state) {
        if (state == null) {
            return false;
        }
        String normalized = state.trim().toUpperCase();
        return "QUEUED".equals(normalized) || "PENDING".equals(normalized);
    }

    private static boolean isCancellationState(String state) {
        if (state == null) {
            return false;
        }
        String normalized = state.trim().toUpperCase();
        return "DENIED".equals(normalized)
                || "TIMED_OUT".equals(normalized)
                || "CANCELLED".equals(normalized)
                || "EXPIRED".equals(normalized);
    }

    private static String cancellationReason(ApprovalRequestRow row) {
        if (row.stateReasonCode() != null && !row.stateReasonCode().isBlank()) {
            String code = row.stateReasonCode().trim().toLowerCase();
            if (code.contains("timeout") || code.contains("expired")) {
                return "timeout";
            }
            return code;
        }

        if (row.state() == null) {
            return "cancelled";
        }

        return switch (row.state().trim().toUpperCase()) {
            case "TIMED_OUT", "EXPIRED" -> "timeout";
            case "DENIED" -> "denied";
            default -> "cancelled";
        };
    }

    private static boolean isTerminalState(String state) {
        return state != null && TERMINAL_STATES.contains(state.trim().toUpperCase());
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

    private static final Set<String> TERMINAL_STATES = Set.of(
            "DENIED",
            "TIMED_OUT",
            "CANCELLED",
            "COMPLETED",
            "FAILED",
            "REJECTED",
            "EXPIRED"
    );

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
