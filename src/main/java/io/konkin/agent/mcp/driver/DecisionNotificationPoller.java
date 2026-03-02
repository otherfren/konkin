package io.konkin.agent.mcp.driver;

import io.konkin.agent.mcp.entity.McpDataContracts.DecisionStatusResponse;
import io.konkin.db.ApprovalRequestRepository;
import io.konkin.db.RequestDependencyLoader;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema.ResourcesUpdatedNotification;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class DecisionNotificationPoller {

    private final ApprovalRequestRepository requestRepo;
    private final RequestDependencyLoader depLoader;
    private final McpSyncServer mcpSyncServer;
    private final Set<String> subscribedRequestIds = ConcurrentHashMap.newKeySet();
    private final Map<String, String> lastKnownStates = new ConcurrentHashMap<>();
    private ScheduledExecutorService scheduler;

    public DecisionNotificationPoller(
            ApprovalRequestRepository requestRepo,
            RequestDependencyLoader depLoader,
            McpSyncServer mcpSyncServer
    ) {
        this.requestRepo = Objects.requireNonNull(requestRepo);
        this.depLoader = Objects.requireNonNull(depLoader);
        this.mcpSyncServer = Objects.requireNonNull(mcpSyncServer);
    }

    public void subscribe(String requestId) {
        subscribedRequestIds.add(requestId);
    }

    public void unsubscribe(String requestId) {
        subscribedRequestIds.remove(requestId);
        lastKnownStates.remove(requestId);
    }

    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "decision-notification-poller");
            t.setDaemon(true);
            return t;
        });

        scheduler.scheduleAtFixedRate(this::pollSubscriptions, 1, 1, TimeUnit.SECONDS);
    }

    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        subscribedRequestIds.clear();
        lastKnownStates.clear();
    }

    private void pollSubscriptions() {
        for (String requestId : subscribedRequestIds) {
            try {
                DecisionStatusResponse status = DecisionStatusResource.loadDecisionStatus(requestRepo, depLoader, requestId);
                if (status == null) {
                    continue;
                }

                String previousState = lastKnownStates.put(requestId, status.state());
                if (previousState != null && !Objects.equals(previousState, status.state())) {
                    String uri = "konkin://decisions/" + requestId;
                    mcpSyncServer.notifyResourcesUpdated(new ResourcesUpdatedNotification(uri));
                }

                if (status.terminal()) {
                    subscribedRequestIds.remove(requestId);
                }
            } catch (Exception ignored) {
                // keep polling on transient failures
            }
        }
    }
}
