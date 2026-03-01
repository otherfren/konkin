package io.konkin.agent;

import io.konkin.agent.mcp.auth.ApprovalDetailsResource;
import io.konkin.agent.mcp.auth.AuthApprovalPrompt;
import io.konkin.agent.mcp.auth.ApprovalNotificationPoller;
import io.konkin.agent.mcp.auth.PendingApprovalsResource;
import io.konkin.agent.mcp.auth.VoteOnApprovalTool;
import io.konkin.agent.mcp.driver.ConfigRequirementsResource;
import io.konkin.agent.mcp.driver.DecisionNotificationPoller;
import io.konkin.agent.mcp.driver.DecisionStatusResource;
import io.konkin.agent.mcp.driver.DriverReadinessPrompt;
import io.konkin.agent.mcp.driver.SendCoinTool;
import io.konkin.agent.primary.PrimaryAgentConfigRequirementsService;
import io.konkin.config.KonkinConfig;
import io.konkin.config.KonkinConfig.AgentConfig;
import io.konkin.db.AuthQueueStore;
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
import java.util.Objects;

public class McpAgentServer {

    private final String agentName;
    private final String agentType;
    private final AgentConfig config;
    private final AgentTokenStore tokenStore;
    private final PrimaryAgentConfigRequirementsService primaryConfigRequirementsService;
    private final AuthQueueStore authQueueStore;
    private final KonkinConfig runtimeConfig;

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
        if (authQueueStore != null && runtimeConfig != null) {
            mcpSyncServer.addTool(SendCoinTool.create(agentName, authQueueStore, runtimeConfig));
        }
        if (authQueueStore != null) {
            mcpSyncServer.addResourceTemplate(DecisionStatusResource.template(authQueueStore));
            decisionNotificationPoller = new DecisionNotificationPoller(authQueueStore, mcpSyncServer);
            decisionNotificationPoller.start();
        }
        mcpSyncServer.addPrompt(DriverReadinessPrompt.create());
    }

    private void registerAuthPrimitives() {
        if (authQueueStore != null && runtimeConfig != null) {
            mcpSyncServer.addTool(VoteOnApprovalTool.create(agentName, authQueueStore, runtimeConfig));
            mcpSyncServer.addResource(PendingApprovalsResource.resource(agentName, authQueueStore, runtimeConfig));
            mcpSyncServer.addResourceTemplate(ApprovalDetailsResource.template(agentName, authQueueStore, runtimeConfig));

            approvalNotificationPoller = new ApprovalNotificationPoller(agentName, authQueueStore, runtimeConfig, mcpSyncServer);
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

        tokenStore.revokeAll();
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
}
