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

package io.konkin.agent.mcp.auth;

import io.konkin.config.KonkinConfig;
import io.konkin.db.ApprovalRequestRepository;
import io.konkin.db.entity.ApprovalRequestRow;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema.ResourcesUpdatedNotification;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ApprovalNotificationPoller {

    private final String agentName;
    private final ApprovalRequestRepository requestRepo;
    private final KonkinConfig runtimeConfig;
    private final McpSyncServer mcpSyncServer;
    private Set<String> lastKnownPendingIds = Set.of();
    private ScheduledExecutorService scheduler;

    public ApprovalNotificationPoller(
            String agentName,
            ApprovalRequestRepository requestRepo,
            KonkinConfig runtimeConfig,
            McpSyncServer mcpSyncServer
    ) {
        this.agentName = Objects.requireNonNull(agentName);
        this.requestRepo = Objects.requireNonNull(requestRepo);
        this.runtimeConfig = Objects.requireNonNull(runtimeConfig);
        this.mcpSyncServer = Objects.requireNonNull(mcpSyncServer);
    }

    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "approval-notification-poller-" + agentName);
            t.setDaemon(true);
            return t;
        });

        scheduler.scheduleAtFixedRate(this::poll, 1, 1, TimeUnit.SECONDS);
    }

    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    private void poll() {
        try {
            List<ApprovalRequestRow> assigned = PendingApprovalsResource.loadAssignedPendingRequests(
                    agentName, requestRepo, runtimeConfig
            );

            Set<String> currentIds = new LinkedHashSet<>();
            for (ApprovalRequestRow row : assigned) {
                currentIds.add(row.id());
            }

            if (!currentIds.equals(lastKnownPendingIds)) {
                lastKnownPendingIds = Set.copyOf(currentIds);
                mcpSyncServer.notifyResourcesUpdated(
                        new ResourcesUpdatedNotification("konkin://approvals/pending")
                );
            }
        } catch (Exception ignored) {
            // keep polling on transient failures
        }
    }
}