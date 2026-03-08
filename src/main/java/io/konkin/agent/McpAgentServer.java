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

package io.konkin.agent;

import io.konkin.agent.mcp.auth.ApprovalDetailsResource;
import io.konkin.agent.mcp.auth.AuthApprovalPrompt;
import io.konkin.agent.mcp.auth.ApprovalNotificationPoller;
import io.konkin.agent.mcp.auth.ListEligibleRequestsTool;
import io.konkin.agent.mcp.auth.PendingApprovalsResource;
import io.konkin.agent.mcp.auth.VoteOnApprovalTool;
import io.konkin.agent.mcp.driver.ConfigRequirementsResource;
import io.konkin.agent.mcp.driver.DecisionNotificationPoller;
import io.konkin.agent.mcp.driver.DecisionStatusResource;
import io.konkin.agent.mcp.driver.DepositAddressTool;
import io.konkin.agent.mcp.driver.DriverReadinessPrompt;
import io.konkin.agent.mcp.driver.PendingTransactionsTool;
import io.konkin.agent.mcp.driver.SendCoinTool;
import io.konkin.agent.mcp.driver.SignMessageTool;
import io.konkin.agent.mcp.driver.VerifyMessageTool;
import io.konkin.agent.mcp.driver.WalletBalanceTool;
import io.konkin.agent.mcp.driver.WalletStatusTool;
import io.konkin.agent.primary.PrimaryAgentConfigRequirementsService;
import io.konkin.config.AgentConfig;
import io.konkin.config.KonkinConfig;
import io.konkin.crypto.Coin;
import io.konkin.crypto.WalletSupervisor;
import io.konkin.web.service.TelegramApprovalNotifier;
import io.konkin.db.ApprovalRequestRepository;
import io.konkin.db.ChannelRepository;
import io.konkin.db.HistoryRepository;
import io.konkin.db.RequestDependencyLoader;
import io.konkin.db.VoteRepository;
import io.konkin.db.VoteService;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncResourceSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;

import jakarta.servlet.DispatcherType;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class McpAgentServer {

    private final String agentName;
    private final String agentType;
    private final AgentConfig config;
    private final AgentTokenStore tokenStore;
    private final PrimaryAgentConfigRequirementsService primaryConfigRequirementsService;
    private final ApprovalRequestRepository requestRepo;
    private final VoteRepository voteRepo;
    private final ChannelRepository channelRepo;
    private final HistoryRepository historyRepo;
    private final RequestDependencyLoader depLoader;
    private final KonkinConfig runtimeConfig;
    private final Map<Coin, WalletSupervisor> walletSupervisors;
    private final TelegramApprovalNotifier telegramNotifier;
    private final VoteService voteService;

    private Server jettyServer;
    private McpSyncServer mcpSyncServer;
    private HttpServletSseServerTransportProvider transportProvider;
    private DecisionNotificationPoller decisionNotificationPoller;
    private ApprovalNotificationPoller approvalNotificationPoller;

    public McpAgentServer(
            String agentName,
            String agentType,
            AgentConfig config,
            AgentTokenStore tokenStore,
            PrimaryAgentConfigRequirementsService primaryConfigRequirementsService,
            ApprovalRequestRepository requestRepo,
            VoteRepository voteRepo,
            ChannelRepository channelRepo,
            HistoryRepository historyRepo,
            RequestDependencyLoader depLoader,
            KonkinConfig runtimeConfig,
            Map<Coin, WalletSupervisor> walletSupervisors,
            TelegramApprovalNotifier telegramNotifier,
            VoteService voteService
    ) {
        this.agentName = Objects.requireNonNull(agentName, "agentName");
        this.agentType = Objects.requireNonNull(agentType, "agentType");
        this.config = Objects.requireNonNull(config, "config");
        this.tokenStore = Objects.requireNonNull(tokenStore, "tokenStore");
        this.primaryConfigRequirementsService = primaryConfigRequirementsService;
        this.requestRepo = requestRepo;
        this.voteRepo = voteRepo;
        this.channelRepo = channelRepo;
        this.historyRepo = historyRepo;
        this.depLoader = depLoader;
        this.runtimeConfig = runtimeConfig;
        this.walletSupervisors = walletSupervisors;
        this.telegramNotifier = telegramNotifier;
        this.voteService = voteService;
    }

    public void start() throws Exception {
        transportProvider = HttpServletSseServerTransportProvider.builder()
                .messageEndpoint("/mcp")
                .sseEndpoint("/sse")
                .build();

        McpSchema.ServerCapabilities capabilities = McpSchema.ServerCapabilities.builder()
                .resources(true, true)
                .tools(true)
                .prompts(true)
                .build();

        mcpSyncServer = McpServer.sync(transportProvider)
                .serverInfo("konkin-agent", "0.1.0")
                .capabilities(capabilities)
                .build();

        // Health resource (all agent types)
        String healthJson = "{\"status\":\"healthy\",\"agent\":\"" + agentName + "\",\"type\":\"" + agentType + "\"}";
        mcpSyncServer.addResource(new SyncResourceSpecification(
                new McpSchema.Resource("konkin://health", "health", null, "Agent health status", "application/json", null, null, null),
                (exchange, request) -> new ReadResourceResult(List.of(
                        new TextResourceContents(request.uri(), "application/json", healthJson)
                ))
        ));

        if ("driver".equals(agentType)) {
            registerDriverPrimitives();
        }

        if ("auth".equals(agentType)) {
            registerAuthPrimitives();
        }

        // Set up Jetty with auth filter + MCP servlet
        AgentOAuthHandler oauthHandler = new AgentOAuthHandler(agentName, Path.of(config.secretFile()), tokenStore);
        McpAuthServletFilter authFilter = new McpAuthServletFilter(agentName, tokenStore, oauthHandler);

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");
        context.addFilter(new FilterHolder(authFilter), "/*", EnumSet.allOf(DispatcherType.class));
        context.addServlet(new ServletHolder(transportProvider), "/*");

        jettyServer = new Server(new InetSocketAddress(config.bind(), config.port()));
        jettyServer.setHandler(context);
        jettyServer.setStopTimeout(3_000);
        jettyServer.start();
    }

    private void registerDriverPrimitives() {
        if (primaryConfigRequirementsService != null) {
            mcpSyncServer.addResource(ConfigRequirementsResource.serverResource(primaryConfigRequirementsService));
            mcpSyncServer.addResourceTemplate(ConfigRequirementsResource.coinTemplate(primaryConfigRequirementsService));
        }
        if (requestRepo != null && historyRepo != null && runtimeConfig != null) {
            mcpSyncServer.addTool(SendCoinTool.create(agentName, requestRepo, historyRepo, runtimeConfig, telegramNotifier));
        }
        if (walletSupervisors != null && !walletSupervisors.isEmpty() && runtimeConfig != null) {
            mcpSyncServer.addTool(WalletStatusTool.create(walletSupervisors, runtimeConfig));
            mcpSyncServer.addTool(WalletBalanceTool.create(walletSupervisors, runtimeConfig));
            mcpSyncServer.addTool(DepositAddressTool.create(walletSupervisors, runtimeConfig));
            mcpSyncServer.addTool(PendingTransactionsTool.create(walletSupervisors, runtimeConfig));
            mcpSyncServer.addTool(SignMessageTool.create(walletSupervisors, runtimeConfig));
            mcpSyncServer.addTool(VerifyMessageTool.create(walletSupervisors, runtimeConfig));
        }
        if (requestRepo != null && depLoader != null) {
            mcpSyncServer.addResourceTemplate(DecisionStatusResource.template(requestRepo, depLoader));
            decisionNotificationPoller = new DecisionNotificationPoller(requestRepo, depLoader, mcpSyncServer);
            decisionNotificationPoller.start();
        }
        mcpSyncServer.addPrompt(DriverReadinessPrompt.create());
    }

    private void registerAuthPrimitives() {
        if (requestRepo != null && voteRepo != null && channelRepo != null && historyRepo != null && runtimeConfig != null) {
            mcpSyncServer.addTool(VoteOnApprovalTool.create(agentName, requestRepo, voteService, channelRepo, runtimeConfig));
            mcpSyncServer.addTool(ListEligibleRequestsTool.create(agentName, requestRepo, voteRepo, channelRepo, runtimeConfig));
            mcpSyncServer.addResource(PendingApprovalsResource.resource(agentName, requestRepo, runtimeConfig));
            mcpSyncServer.addResourceTemplate(ApprovalDetailsResource.template(agentName, requestRepo, voteRepo, runtimeConfig));

            approvalNotificationPoller = new ApprovalNotificationPoller(agentName, requestRepo, runtimeConfig, mcpSyncServer);
            approvalNotificationPoller.start();
        }
        mcpSyncServer.addPrompt(AuthApprovalPrompt.create());
    }

    public void stop() {
        if (decisionNotificationPoller != null) {
            decisionNotificationPoller.stop();
            decisionNotificationPoller = null;
        }

        if (approvalNotificationPoller != null) {
            approvalNotificationPoller.stop();
            approvalNotificationPoller = null;
        }

        if (mcpSyncServer != null) {
            try {
                mcpSyncServer.closeGracefully();
            } catch (Exception ignored) {
            }
            mcpSyncServer = null;
        }

        if (jettyServer != null) {
            try {
                jettyServer.stop();
            } catch (Exception ignored) {
            }
            jettyServer = null;
        }
        // Tokens are persisted in H2 — do NOT revoke on shutdown so they survive restarts.
    }

    public McpSyncServer mcpSyncServer() {
        return mcpSyncServer;
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

    public boolean isRunning() {
        return jettyServer != null && jettyServer.isRunning();
    }
}