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

package io.konkin.web;

import io.konkin.config.AgentConfig;
import io.konkin.config.ApprovalCriteria;
import io.konkin.config.ApprovalRule;
import io.konkin.config.CoinAuthConfig;
import io.konkin.config.CoinConfig;
import io.konkin.config.ConfigManager;
import io.konkin.config.KonkinConfig;
import io.konkin.crypto.Transaction;
import io.konkin.crypto.WalletConnectionConfig;
import io.konkin.crypto.WalletSecretLoader;
import io.konkin.crypto.WalletSnapshot;
import io.konkin.crypto.WalletStatus;
import io.konkin.crypto.Coin;
import io.konkin.crypto.WalletSupervisor;
import io.konkin.crypto.monero.MoneroExtras;
import io.konkin.db.AgentTokenStore;
import io.konkin.db.KvStore;
import io.konkin.db.entity.ApprovalChannelRow;
import io.konkin.db.entity.ApprovalRequestRow;
import io.konkin.db.entity.ExecutionAttemptDetail;
import io.konkin.db.entity.PageResult;
import io.konkin.db.entity.RequestChannelDetail;
import io.konkin.db.entity.RequestDependencies;
import io.konkin.db.entity.StateTransitionDetail;
import io.konkin.db.entity.StateTransitionRow;
import io.konkin.db.entity.VoteDetail;
import io.konkin.telegram.TelegramSecretService;
import io.konkin.telegram.TelegramService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static io.konkin.web.UiFormattingUtils.*;

public class LandingPageMapper {

    private static final Logger log = LoggerFactory.getLogger(LandingPageMapper.class);

    private static final String KV_DEPOSIT_ADDRESS_PREFIX = "deposit-address:";

    private final ConfigManager configManager;
    private final Map<Coin, WalletSupervisor> walletSupervisors;
    private final KvStore kvStore;
    private final AtomicReference<String> activeApiKey;
    private volatile AgentTokenStore agentTokenStore;

    /** Convenience constructor for tests. */
    public LandingPageMapper(KonkinConfig config, Map<Coin, WalletSupervisor> walletSupervisors) {
        this(new ConfigManager(config), walletSupervisors, null, null);
    }

    /** Convenience constructor for tests. */
    public LandingPageMapper(KonkinConfig config, Map<Coin, WalletSupervisor> walletSupervisors, KvStore kvStore) {
        this(new ConfigManager(config), walletSupervisors, kvStore, null);
    }

    public LandingPageMapper(ConfigManager configManager, Map<Coin, WalletSupervisor> walletSupervisors, KvStore kvStore, AtomicReference<String> activeApiKey) {
        this.configManager = configManager;
        this.walletSupervisors = walletSupervisors != null ? walletSupervisors : Map.of();
        this.kvStore = kvStore;
        this.activeApiKey = activeApiKey != null ? activeApiKey : new AtomicReference<>();
    }

    public void setAgentTokenStore(AgentTokenStore agentTokenStore) {
        this.agentTokenStore = agentTokenStore;
    }

    private KonkinConfig config() {
        return configManager.get();
    }

    // ── Approval page data (queue + log-queue shared row mapping) ───────────

    public TablePageData mapApprovalPageData(
            PageResult<ApprovalRequestRow> result,
            Map<String, RequestDependencies> dependenciesByRequestId
    ) {
        Instant now = Instant.now();

        List<Map<String, Object>> rows = new ArrayList<>();
        for (ApprovalRequestRow row : result.rows()) {
            Map<String, Object> mapped = new LinkedHashMap<>();
            String state = normalizeState(row.state());
            String stateLower = state.toLowerCase(Locale.ROOT);
            int approvalsGranted = row.approvalsGranted();
            int minApprovalsRequired = row.minApprovalsRequired();

            RequestDependencies dependencies = dependenciesByRequestId.getOrDefault(
                    row.id(),
                    new RequestDependencies(List.of(), List.of(), List.of(), List.of())
            );

            Set<String> deciders = new LinkedHashSet<>();
            for (VoteDetail vote : dependencies.votes()) {
                if (vote.decidedBy() != null && !vote.decidedBy().isBlank()) {
                    deciders.add(vote.decidedBy().trim());
                }
            }

            mapped.put("id", safe(row.id()));
            mapped.put("idShort", abbreviateId(row.id()));
            mapped.put("idFirst5", firstFive(row.id()));
            mapped.put("coin", safe(row.coin()));
            mapped.put("coinIconName", coinIconName(row.coin()));
            mapped.put("toolName", safe(row.toolName()));
            mapped.put("amountNative", safe(row.amountNative()));
            mapped.put("toAddress", safe(row.toAddress()));
            mapped.put("requestedAt", formatInstantMinute(row.requestedAt()));
            mapped.put("expiresIn", formatRemaining(row.expiresAt(), now));
            mapped.put("state", state);
            mapped.put("stateLower", stateLower);
            mapped.put("statusClass", toStatusClass(stateLower));
            mapped.put("minApprovalsRequired", minApprovalsRequired);
            mapped.put("approvalsGranted", approvalsGranted);
            mapped.put("approvalsDenied", row.approvalsDenied());
            mapped.put("quorumLabel", "pending " + approvalsGranted + "-of-" + minApprovalsRequired);
            mapped.put("lastActionAt", formatLogMinute(row.updatedAt()));
            mapped.put("reason", safe(row.reason()));
            mapped.put("deciders", deciders.isEmpty() ? "-" : String.join(", ", deciders));
            mapped.put("detailsJson", toPrettyJson(buildDetailsObject(row, dependencies)));

            // Check if the web-ui channel has already voted on this request
            String webUiVoteDecision = "";
            for (VoteDetail vote : dependencies.votes()) {
                if ("web-ui".equals(vote.channelId())) {
                    webUiVoteDecision = vote.decision() != null ? vote.decision() : "unknown";
                    break;
                }
            }
            mapped.put("votedByWebUi", !webUiVoteDecision.isEmpty());
            mapped.put("webUiVoteDecision", webUiVoteDecision);

            rows.add(Map.copyOf(mapped));
        }

        return new TablePageData(List.copyOf(rows), WebUtils.pageMetaFrom(result));
    }

    // ── Details object (full request + history + dependencies) ──────────────

    public Map<String, Object> buildDetailsObject(
            ApprovalRequestRow row,
            RequestDependencies dependencies
    ) {
        Map<String, Object> root = new LinkedHashMap<>();

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("id", safe(row.id()));
        request.put("coin", safe(row.coin()));
        request.put("toolName", safe(row.toolName()));
        request.put("requestSessionId", safe(row.requestSessionId()));
        request.put("nonceUuid", safe(row.nonceUuid()));
        request.put("payloadHashSha256", safe(row.payloadHashSha256()));
        request.put("nonceComposite", safe(row.nonceComposite()));
        request.put("toAddress", safe(row.toAddress()));
        request.put("amountNative", safe(row.amountNative()));
        request.put("feePolicy", safe(row.feePolicy()));
        request.put("feeCapNative", safe(row.feeCapNative()));
        request.put("memo", safe(row.memo()));
        request.put("reason", safe(row.reason()));
        request.put("requestedAt", formatInstant(row.requestedAt()));
        request.put("expiresAt", formatInstant(row.expiresAt()));
        request.put("state", safe(row.state()));
        request.put("stateReasonCode", safe(row.stateReasonCode()));
        request.put("stateReasonText", safe(row.stateReasonText()));
        request.put("minApprovalsRequired", row.minApprovalsRequired());
        request.put("approvalsGranted", row.approvalsGranted());
        request.put("approvalsDenied", row.approvalsDenied());
        request.put("policyActionAtCreation", safe(row.policyActionAtCreation()));
        request.put("createdAt", formatInstant(row.createdAt()));
        request.put("updatedAt", formatInstant(row.updatedAt()));
        request.put("resolvedAt", formatInstant(row.resolvedAt()));
        root.put("request", Map.copyOf(request));

        List<Map<String, Object>> history = new ArrayList<>();
        for (StateTransitionDetail transition : dependencies.transitions()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", transition.id());
            item.put("requestId", safe(transition.requestId()));
            item.put("fromState", safe(transition.fromState()));
            item.put("toState", safe(transition.toState()));
            item.put("actorType", safe(transition.actorType()));
            item.put("actorId", safe(transition.actorId()));
            item.put("reasonCode", safe(transition.reasonCode()));
            item.put("reasonText", safe(transition.reasonText()));
            item.put("metadataJson", safe(transition.metadataJson()));
            item.put("createdAt", formatInstant(transition.createdAt()));
            history.add(Map.copyOf(item));
        }
        root.put("history", List.copyOf(history));

        Map<String, Object> allDependencies = new LinkedHashMap<>();

        List<Map<String, Object>> channels = new ArrayList<>();
        for (RequestChannelDetail channel : dependencies.channels()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", channel.id());
            item.put("requestId", safe(channel.requestId()));
            item.put("channelId", safe(channel.channelId()));
            item.put("deliveryState", safe(channel.deliveryState()));
            item.put("firstSentAt", formatInstant(channel.firstSentAt()));
            item.put("lastAttemptAt", formatInstant(channel.lastAttemptAt()));
            item.put("attemptCount", channel.attemptCount());
            item.put("lastError", safe(channel.lastError()));
            item.put("createdAt", formatInstant(channel.createdAt()));
            channels.add(Map.copyOf(item));
        }
        allDependencies.put("channels", List.copyOf(channels));

        List<Map<String, Object>> votes = new ArrayList<>();
        for (VoteDetail vote : dependencies.votes()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", vote.id());
            item.put("requestId", safe(vote.requestId()));
            item.put("channelId", safe(vote.channelId()));
            item.put("decision", safe(vote.decision()));
            item.put("decisionReason", safe(vote.decisionReason()));
            item.put("decidedBy", safe(vote.decidedBy()));
            item.put("decidedAt", formatInstant(vote.decidedAt()));
            votes.add(Map.copyOf(item));
        }
        allDependencies.put("votes", List.copyOf(votes));

        List<Map<String, Object>> executionAttempts = new ArrayList<>();
        for (ExecutionAttemptDetail execution : dependencies.executionAttempts()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", execution.id());
            item.put("requestId", safe(execution.requestId()));
            item.put("attemptNo", execution.attemptNo());
            item.put("startedAt", formatInstant(execution.startedAt()));
            item.put("finishedAt", formatInstant(execution.finishedAt()));
            item.put("result", safe(execution.result()));
            item.put("errorClass", safe(execution.errorClass()));
            item.put("errorMessage", safe(execution.errorMessage()));
            item.put("txid", safe(execution.txid()));
            item.put("daemonFeeNative", safe(execution.daemonFeeNative()));
            executionAttempts.add(Map.copyOf(item));
        }
        allDependencies.put("executionAttempts", List.copyOf(executionAttempts));

        root.put("dependencies", Map.copyOf(allDependencies));
        return Map.copyOf(root);
    }

    // ── Audit page data ────────────────────────────────────────────────────

    public TablePageData mapAuditPageData(PageResult<StateTransitionRow> result) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (StateTransitionRow row : result.rows()) {
            Map<String, Object> mapped = new LinkedHashMap<>();
            mapped.put("id", row.id());
            mapped.put("requestId", row.requestId());
            mapped.put("fromState", row.fromState() == null ? "-" : row.fromState());
            mapped.put("toState", row.toState());
            mapped.put("actorType", row.actorType());
            mapped.put("actorId", row.actorId() == null ? "-" : row.actorId());
            mapped.put("reasonCode", row.reasonCode() == null ? "-" : row.reasonCode());
            mapped.put("createdAt", formatInstant(row.createdAt()));
            rows.add(Map.copyOf(mapped));
        }

        return new TablePageData(List.copyOf(rows), WebUtils.pageMetaFrom(result));
    }

    // ── Auth channels model ────────────────────────────────────────────────

    public Map<String, Object> buildWebUiChannelModel() {
        Map<String, Object> webUi = new LinkedHashMap<>();
        webUi.put("enabled", config().landingEnabled());
        webUi.put("passwordProtectionEnabled", config().landingPasswordProtectionEnabled());
        webUi.put("passwordFile", safe(config().landingPasswordFile()));
        return Map.copyOf(webUi);
    }

    public Map<String, Object> buildRestApiChannelModel() {
        Map<String, Object> restApi = new LinkedHashMap<>();
        boolean restApiEnabled = config().restApiEnabled();
        boolean restApiOperational = restApiEnabled && activeApiKey.get() != null;
        restApi.put("enabled", restApiOperational);
        restApi.put("healthPath", "/api/v1/health");
        restApi.put("apiKeyHeader", "X-API-Key");
        restApi.put("protectedScope", "/api/v1/* (except /api/v1/health)");
        restApi.put("apiKeyProtectionEnabled", restApiEnabled);
        restApi.put("secretFile", restApiEnabled ? safe(config().restApiSecretFile()) : "-");
        return Map.copyOf(restApi);
    }

    public Map<String, Object> buildAuthChannelsModel(
            List<TelegramService.ChatRequest> discoveredRequests,
            TelegramSecretService.TelegramSecret secret,
            List<String> configuredTelegramChatIds,
            boolean telegramEnabled
    ) {
        Map<String, Object> root = new LinkedHashMap<>();

        root.put("configuredAuthChannels", buildConfiguredAuthChannels());
        root.put("telegramEnabled", telegramEnabled);
        root.put("telegramUsers", buildTelegramChannelUsers(discoveredRequests, secret, configuredTelegramChatIds));
        root.put("authAgents", buildAuthAgentChannels());

        return Map.copyOf(root);
    }

    // ── Telegram channel users ─────────────────────────────────────────────

    public List<Map<String, Object>> buildTelegramChannelUsers(
            List<TelegramService.ChatRequest> discoveredRequests,
            TelegramSecretService.TelegramSecret secret,
            List<String> configuredTelegramChatIds
    ) {
        if (secret == null) {
            return List.of();
        }

        Map<String, TelegramSecretService.ChatMeta> metadataByChatId = secret.chatMetaById();
        List<String> approvedChatIds = TelegramSecretService.mergeChatIds(configuredTelegramChatIds, secret.chatIds());
        Set<String> approvedSet = new HashSet<>(approvedChatIds);

        LinkedHashSet<String> orderedChatIds = new LinkedHashSet<>();
        Map<String, TelegramService.ChatRequest> discoveredByChatId = new LinkedHashMap<>();

        for (String approvedChatId : approvedChatIds) {
            if (approvedChatId == null || approvedChatId.isBlank()) {
                continue;
            }
            orderedChatIds.add(approvedChatId.trim());
        }

        for (String chatId : metadataByChatId.keySet()) {
            if (chatId == null || chatId.isBlank()) {
                continue;
            }
            orderedChatIds.add(chatId.trim());
        }

        for (TelegramService.ChatRequest request : discoveredRequests) {
            if (request == null || request.chatId() == null || request.chatId().isBlank()) {
                continue;
            }

            String chatId = request.chatId().trim();
            orderedChatIds.add(chatId);
            discoveredByChatId.put(chatId, request);
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (String chatId : orderedChatIds) {
            TelegramService.ChatRequest discovered = discoveredByChatId.get(chatId);
            TelegramSecretService.ChatMeta persisted = metadataByChatId.get(chatId);

            String chatType = WebUtils.firstNonBlank(
                    persisted == null ? null : persisted.chatType(),
                    discovered == null ? null : discovered.chatType(),
                    "unknown"
            );
            String chatTitle = WebUtils.firstNonBlank(
                    persisted == null ? null : persisted.chatTitle(),
                    discovered == null ? null : discovered.chatTitle(),
                    "(no title)"
            );
            String chatUsername = WebUtils.firstNonBlank(
                    persisted == null ? null : persisted.chatUsername(),
                    discovered == null ? null : discovered.chatUsername(),
                    ""
            );
            String displayName = WebUtils.firstNonBlank(
                    persisted == null ? null : persisted.displayName(),
                    discovered == null
                            ? null
                            : WebUtils.firstNonBlank(
                            discovered.chatTitle(),
                            discovered.chatUsername() == null || discovered.chatUsername().isBlank()
                                    ? ""
                                    : "@" + discovered.chatUsername().trim()
                    ),
                    chatUsername.isEmpty() ? "" : "@" + chatUsername,
                    chatId
            );

            boolean approved = approvedSet.contains(chatId);
            boolean discoveredUser = discovered != null || persisted != null;

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("chatId", chatId);
            row.put("chatType", chatType);
            row.put("chatTitle", chatTitle);
            row.put("chatUsername", chatUsername);
            row.put("chatDisplayName", displayName);
            row.put("approved", approved);
            row.put("discovered", discoveredUser);
            row.put("canApprove", !approved && discoveredUser);
            rows.add(Map.copyOf(row));
        }

        return List.copyOf(rows);
    }

    // ── Driver agent model ─────────────────────────────────────────────────

    public Map<String, Object> buildDriverAgentModel() {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> driverAgent = buildDriverAgentChannel();
        root.put("driverAgent", driverAgent);
        root.put("authMethod", buildDriverAgentAuthMethod(driverAgent));
        root.put("mcpRegistration", buildDriverAgentMcpRegistration(driverAgent));
        return Map.copyOf(root);
    }

    public Map<String, Object> buildDriverAgentSettingsModel() {
        AgentConfig driverAgent = config().primaryAgent();
        if (driverAgent == null) return null;
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("bind", safe(driverAgent.bind()));
        s.put("port", driverAgent.port());
        return Map.copyOf(s);
    }

    private Map<String, Object> buildDriverAgentAuthMethod(Map<String, Object> driverAgent) {
        boolean configured = Boolean.TRUE.equals(driverAgent.get("configured"));

        String tokenEndpoint = driverAgent.get("oauthTokenPath") instanceof String value ? value : "-";
        String secretFile = driverAgent.get("secretFile") instanceof String value ? value : "-";

        Map<String, Object> authMethod = new LinkedHashMap<>();
        authMethod.put("configured", configured);
        authMethod.put("enabled", configured);
        authMethod.put("method", "OAuth 2.0 Client Credentials");
        authMethod.put("clientId", "konkin-primary");
        authMethod.put("tokenEndpoint", tokenEndpoint);
        authMethod.put("authorizationHeader", "Authorization: Bearer <access_token>");
        authMethod.put("secretFile", secretFile);
        return Map.copyOf(authMethod);
    }

    private Map<String, Object> buildDriverAgentMcpRegistration(Map<String, Object> driverAgent) {
        boolean configured = Boolean.TRUE.equals(driverAgent.get("configured"));

        String tokenEndpoint = driverAgent.get("oauthTokenPath") instanceof String value ? value : "-";
        String sseEndpoint = driverAgent.get("ssePath") instanceof String value ? value : "-";

        String tokenCommand = configured && !"-".equals(tokenEndpoint)
                ? """
                curl -s -X POST "%s" \\
                  -d "grant_type=client_credentials" \\
                  -d "client_id=konkin-primary" \\
                  -d "client_secret=YOUR_SECRET"
                """.strip().formatted(tokenEndpoint)
                : "-";

        Map<String, Object> mcpRegistration = new LinkedHashMap<>();
        mcpRegistration.put("configured", configured);
        mcpRegistration.put("enabled", configured);
        mcpRegistration.put("sseEndpoint", sseEndpoint);
        mcpRegistration.put("tokenEndpoint", tokenEndpoint);
        mcpRegistration.put("agentCommands", buildAgentCommands(configured, sseEndpoint, "konkin"));
        mcpRegistration.put("tokenCommand", tokenCommand);
        mcpRegistration.put("skillPath", "documents/SKILL-driver-agent.md");
        return Map.copyOf(mcpRegistration);
    }

    private List<Map<String, Object>> buildAgentCommands(boolean enabled, String sseEndpoint, String mcpServerName) {
        if (!enabled || "-".equals(sseEndpoint)) {
            return List.of();
        }

        List<Map<String, Object>> agents = new ArrayList<>();

        Map<String, Object> claudeCode = new LinkedHashMap<>();
        claudeCode.put("id", "claude-code");
        claudeCode.put("label", "Claude Code");
        claudeCode.put("registerCommand", """
                claude mcp add --transport sse \\
                  -H "Authorization: Bearer YOUR_BEARER_TOKEN" \\
                  -s project \\
                  %s "%s"\
                """.strip().formatted(mcpServerName, sseEndpoint));
        claudeCode.put("verifyCommand", "claude mcp list");
        agents.add(Map.copyOf(claudeCode));

        Map<String, Object> claudeDesktop = new LinkedHashMap<>();
        claudeDesktop.put("id", "claude-desktop");
        claudeDesktop.put("label", "Claude Desktop");
        claudeDesktop.put("registerCommand", """
                Add to claude_desktop_config.json:
                  macOS  ~/Library/Application Support/Claude/
                  Win    %%APPDATA%%\\Claude\\

                {
                  "mcpServers": {
                    "%s": {
                      "url": "%s",
                      "headers": {
                        "Authorization": "Bearer YOUR_BEARER_TOKEN"
                      }
                    }
                  }
                }\
                """.strip().formatted(mcpServerName, sseEndpoint));
        claudeDesktop.put("verifyCommand", "Restart Claude Desktop, check MCP server icon");
        agents.add(Map.copyOf(claudeDesktop));

        Map<String, Object> cursor = new LinkedHashMap<>();
        cursor.put("id", "cursor");
        cursor.put("label", "Cursor");
        cursor.put("registerCommand", """
                Add to .cursor/mcp.json in your project root:

                {
                  "mcpServers": {
                    "%s": {
                      "url": "%s",
                      "headers": {
                        "Authorization": "Bearer YOUR_BEARER_TOKEN"
                      }
                    }
                  }
                }\
                """.strip().formatted(mcpServerName, sseEndpoint));
        cursor.put("verifyCommand", "Open Cursor Settings > MCP, check " + mcpServerName + " status");
        agents.add(Map.copyOf(cursor));

        Map<String, Object> windsurf = new LinkedHashMap<>();
        windsurf.put("id", "windsurf");
        windsurf.put("label", "Windsurf");
        windsurf.put("registerCommand", """
                Add to ~/.codeium/windsurf/mcp_config.json:

                {
                  "mcpServers": {
                    "%s": {
                      "serverUrl": "%s",
                      "headers": {
                        "Authorization": "Bearer YOUR_BEARER_TOKEN"
                      }
                    }
                  }
                }\
                """.strip().formatted(mcpServerName, sseEndpoint));
        windsurf.put("verifyCommand", "Check Windsurf Cascade panel for MCP tools");
        agents.add(Map.copyOf(windsurf));

        return List.copyOf(agents);
    }

    private Map<String, Object> buildDriverAgentChannel() {
        AgentConfig driverAgent = config().primaryAgent();
        if (driverAgent == null) {
            return Map.of(
                    "configured", false,
                    "name", "driver",
                    "type", "driver",
                    "enabled", false,
                    "bind", "-",
                    "port", "-",
                    "healthPath", "-",
                    "oauthTokenPath", "-",
                    "ssePath", "-",
                    "secretFile", "-"
            );
        }

        String bind = safe(driverAgent.bind());
        int port = driverAgent.port();
        String endpointBase = "http://" + displayBind(bind) + ":" + port;

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("configured", true);
        row.put("name", "driver");
        row.put("type", "driver");
        row.put("bind", bind);
        row.put("port", port > 0 ? Integer.toString(port) : "-");
        row.put("healthPath", endpointBase + "/health");
        row.put("oauthTokenPath", endpointBase + "/oauth/token");
        row.put("ssePath", endpointBase + "/sse");
        row.put("secretFile", safe(driverAgent.secretFile()));
        return Map.copyOf(row);
    }

    // ── Auth agent channels ────────────────────────────────────────────────

    public List<Map<String, Object>> buildAuthAgentChannels() {
        Map<String, AgentConfig> authAgents = config().secondaryAgents();
        if (authAgents == null || authAgents.isEmpty()) {
            return List.of();
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, AgentConfig> entry : authAgents.entrySet()) {
            AgentConfig agentConfig = entry.getValue();
            if (agentConfig == null || !agentConfig.visible()) {
                continue;
            }
            String agentName = safe(entry.getKey());

            String bind = agentConfig == null ? "-" : safe(agentConfig.bind());
            int port = agentConfig == null ? 0 : agentConfig.port();
            String endpointBase = port > 0 ? "http://" + displayBind(bind) + ":" + port : "-";

            boolean connected = agentTokenStore != null && agentTokenStore.hasTokens(agentName);
            String lastActivity = agentTokenStore != null
                    ? agentTokenStore.lastActivity(agentName).map(UiFormattingUtils::formatInstantMinute).orElse("")
                    : "";

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", agentName);
            row.put("authChannelId", "verification-agent:" + agentName);
            row.put("connected", connected);
            row.put("lastActivity", lastActivity);
            row.put("bind", bind);
            row.put("port", port > 0 ? Integer.toString(port) : "-");
            row.put("healthPath", port > 0 ? endpointBase + "/health" : "-");
            row.put("oauthTokenPath", port > 0 ? endpointBase + "/oauth/token" : "-");
            row.put("secretFile", agentConfig == null ? "-" : safe(agentConfig.secretFile()));
            rows.add(Map.copyOf(row));
        }

        return List.copyOf(rows);
    }

    // ── Auth agent MCP registrations ────────────────────────────────────────

    // ── Wallets model ──────────────────────────────────────────────────────

    public Map<String, Object> buildWalletsModel() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("configuredAuthChannels", buildConfiguredAuthChannels());

        List<Map<String, Object>> coins = new ArrayList<>();
        for (String coinId : getAllKnownCoinIds()) {
            CoinConfig coinConfig = config().resolveCoinConfig(coinId);
            if (coinConfig != null) {
                String configSection = "coins." + coinId;
                String daemonConfigKey = configSection + ".secret-files." + coinId + "-daemon-config-file";
                String walletConfigKey;
                if ("monero".equals(coinId)) {
                    walletConfigKey = configSection + ".secret-files.monero-wallet-rpc-config-file";
                } else {
                    walletConfigKey = configSection + ".secret-files." + coinId + "-wallet-config-file";
                }
                coins.add(buildWalletOverviewEntry(coinId, coinConfig, configSection, daemonConfigKey, walletConfigKey));
            } else {
                coins.add(buildUnconfiguredCoinEntry(coinId));
            }
        }
        coins.sort((a, b) -> Integer.compare(coinSortOrder(a), coinSortOrder(b)));
        root.put("coins", List.copyOf(coins));
        return Map.copyOf(root);
    }

    public List<String> getAllKnownCoinIds() {
        return List.of("bitcoin", "litecoin", "monero");
    }

    private static int coinSortOrder(Map<String, Object> coin) {
        boolean configured = Boolean.TRUE.equals(coin.get("configured"));
        boolean enabled = Boolean.TRUE.equals(coin.get("enabled"));
        boolean disconnected = Boolean.TRUE.equals(coin.get("disconnected"));
        if (configured && enabled && !disconnected) return 0;
        if (configured && enabled) return 1;
        if (!configured) return 3;
        return 2;
    }

    private Map<String, Object> buildUnconfiguredCoinEntry(String coinId) {
        Map<String, Object> coin = new LinkedHashMap<>();
        coin.put("coin", coinId);
        coin.put("coinIconName", coinIconName(coinId));
        coin.put("enabled", false);
        coin.put("configured", false);
        coin.put("maskedConfig", null);
        coin.put("connectionStatus", "not configured");
        coin.put("lastLifeSign", "n/a");
        coin.put("disconnected", true);
        coin.put("channels", Map.of());
        return Collections.unmodifiableMap(coin);
    }

    private Map<String, Object> buildWalletOverviewEntry(
            String coinId, CoinConfig coinConfig, String configSection,
            String daemonConfigKey, String walletConfigKey) {
        Map<String, Object> coin = new LinkedHashMap<>();
        coin.put("coin", coinId);
        coin.put("coinIconName", coinIconName(coinId));
        coin.put("enabled", coinConfig.enabled());
        coin.put("configSection", configSection);
        coin.put("daemonConfigKey", daemonConfigKey);
        coin.put("walletConfigKey", walletConfigKey);
        coin.put("daemonSecretFile", safe(coinConfig.daemonConfigSecretFile()));
        coin.put("walletSecretFile", safe(coinConfig.walletConfigSecretFile()));

        // Determine configured status and masked config
        boolean configured = false;
        Map<String, String> maskedConfig = null;
        try {
            maskedConfig = buildMaskedConfig(coinId, coinConfig);
            configured = maskedConfig != null;
        } catch (Exception e) {
            log.debug("Failed to load masked config for {}: {}", coinId, e.getMessage());
        }
        coin.put("configured", configured);
        coin.put("maskedConfig", maskedConfig);

        // Raw auth config for inline editing
        CoinAuthConfig auth = coinConfig.auth();
        if (auth != null) {
            coin.put("authWebUi", auth.webUi());
            coin.put("authRestApi", auth.restApi());
            coin.put("authTelegram", auth.telegram());
            coin.put("minApprovalsRequired", auth.minApprovalsRequired());
        }

        if (coinConfig.enabled()) {
            boolean restApiOperational = auth != null && auth.restApi() && config().restApiEnabled() && activeApiKey.get() != null;
            Map<String, Object> channels = new LinkedHashMap<>();
            channels.put("webUi", auth != null && auth.webUi());
            channels.put("restApi", restApiOperational);
            channels.put("telegram", auth != null && auth.telegram());
            coin.put("channels", Map.copyOf(channels));

            WalletSupervisor walletSupervisor = resolveSupervisor(coinId);
            if (walletSupervisor != null) {
                WalletSnapshot snap = walletSupervisor.snapshot();
                boolean available = snap.status() == WalletStatus.AVAILABLE;
                coin.put("connectionStatus", snap.status().name().toLowerCase());
                coin.put("lastLifeSign", snap.lastHeartbeat() == null ? "never" : formatInstant(snap.lastHeartbeat()));
                coin.put("disconnected", !available);
                coin.put("connected", available || snap.status() == WalletStatus.SYNCING);
                coin.put("readable", available);
                coin.put("writable", available);
                if (snap.totalBalance() != null) {
                    coin.put("balanceValue", snap.totalBalance().toPlainString());
                } else {
                    coin.put("balanceValue", "-");
                }
            } else {
                coin.put("connectionStatus", configured ? "not connected" : "not configured");
                coin.put("lastLifeSign", "n/a");
                coin.put("disconnected", true);
                coin.put("connected", false);
                coin.put("readable", false);
                coin.put("writable", false);
                coin.put("balanceValue", "-");
            }
        } else {
            coin.put("channels", Map.of());
            coin.put("connectionStatus", configured ? "disabled" : "not configured");
            coin.put("lastLifeSign", "n/a");
            coin.put("disconnected", true);
            coin.put("connected", false);
            coin.put("readable", false);
            coin.put("writable", false);
            coin.put("balanceValue", "-");
        }
        return Collections.unmodifiableMap(coin);
    }

    private Map<String, String> buildMaskedConfig(String coinId, CoinConfig coinConfig) {
        if (coinConfig == null) return null;

        String daemonPath = coinConfig.daemonConfigSecretFile();
        String walletPath = coinConfig.walletConfigSecretFile();
        if (daemonPath == null || daemonPath.isBlank() || walletPath == null || walletPath.isBlank()) {
            return null;
        }

        // Check that secret files exist
        if (!Files.isReadable(Path.of(daemonPath)) || !Files.isReadable(Path.of(walletPath))) {
            return null;
        }

        try {
            return switch (coinId.toLowerCase(Locale.ROOT)) {
                case "bitcoin" -> {
                    WalletConnectionConfig wcc = WalletSecretLoader.loadBitcoin(daemonPath, walletPath);
                    Map<String, String> m = new LinkedHashMap<>();
                    // Extract host:port from rpcUrl (strip "http://")
                    String endpoint = wcc.rpcUrl().replaceFirst("^https?://", "");
                    m.put("rpcEndpoint", endpoint);
                    m.put("rpcUser", wcc.username() != null ? wcc.username() : "");
                    String walletName = wcc.extras().getOrDefault("walletName", "");
                    m.put("walletName", walletName);
                    yield Map.copyOf(m);
                }
                case "litecoin" -> {
                    WalletConnectionConfig wcc = WalletSecretLoader.loadLitecoin(daemonPath, walletPath);
                    Map<String, String> m = new LinkedHashMap<>();
                    String endpoint = wcc.rpcUrl().replaceFirst("^https?://", "");
                    m.put("rpcEndpoint", endpoint);
                    m.put("rpcUser", wcc.username() != null ? wcc.username() : "");
                    String walletName = wcc.extras().getOrDefault("walletName", "");
                    m.put("walletName", walletName);
                    yield Map.copyOf(m);
                }
                case "monero" -> {
                    WalletConnectionConfig wcc = WalletSecretLoader.loadMonero(daemonPath, walletPath);
                    Map<String, String> m = new LinkedHashMap<>();
                    String daemonEndpoint = wcc.extras().getOrDefault(MoneroExtras.DAEMON_RPC_URL, "");
                    daemonEndpoint = daemonEndpoint.replaceFirst("^https?://", "");
                    m.put("daemonEndpoint", daemonEndpoint);
                    String walletRpcEndpoint = wcc.rpcUrl().replaceFirst("^https?://", "");
                    m.put("walletRpcEndpoint", walletRpcEndpoint);
                    m.put("walletRpcUser", wcc.username() != null ? wcc.username() : "");
                    yield Map.copyOf(m);
                }
                default -> null;
            };
        } catch (Exception e) {
            log.debug("Failed to read secret files for {}: {}", coinId, e.getMessage());
            return null;
        }
    }

    public List<String> getEnabledCoinIds() {
        List<String> coins = new ArrayList<>();
        if (config().bitcoin().enabled()) coins.add("bitcoin");
        if (config().litecoin().enabled()) coins.add("litecoin");
        if (config().monero().enabled()) coins.add("monero");
        return List.copyOf(coins);
    }

    public Map<String, Object> buildSingleCoinWalletModel(String coinId) {
        CoinConfig coinConfig = config().resolveCoinConfig(coinId);
        if (coinConfig == null || !coinConfig.enabled()) {
            // Return an unconfigured model so the wizard can be shown
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("configuredAuthChannels", buildConfiguredAuthChannels());
            root.put("coin", buildUnconfiguredCoinEntry(coinId));
            return Map.copyOf(root);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("configuredAuthChannels", buildConfiguredAuthChannels());
        root.put("coin", buildCoinAuthDefinition(coinId, coinConfig));
        return Map.copyOf(root);
    }

    // ── Configured auth channels ───────────────────────────────────────────

    public List<Map<String, Object>> buildConfiguredAuthChannels() {
        List<Map<String, Object>> channels = new ArrayList<>();

        Map<String, Object> webUi = new LinkedHashMap<>();
        webUi.put("name", "web-ui");
        webUi.put("enabled", config().landingEnabled());
        channels.add(Map.copyOf(webUi));

        if (config().restApiEnabled()) {
            Map<String, Object> restApi = new LinkedHashMap<>();
            restApi.put("name", "rest-api");
            restApi.put("enabled", activeApiKey.get() != null);
            channels.add(Map.copyOf(restApi));
        }

        Map<String, Object> telegram = new LinkedHashMap<>();
        telegram.put("name", "telegram");
        telegram.put("enabled", config().telegramEnabled());
        channels.add(Map.copyOf(telegram));

        Map<String, AgentConfig> authAgents = config().secondaryAgents();
        if (authAgents != null && !authAgents.isEmpty()) {
            for (Map.Entry<String, AgentConfig> entry : authAgents.entrySet()) {
                AgentConfig agentConfig = entry.getValue();
                if (agentConfig == null || !agentConfig.visible()) {
                    continue;
                }

                Map<String, Object> authAgent = new LinkedHashMap<>();
                authAgent.put("name", "verification-agent:" + safe(entry.getKey()));
                authAgent.put("enabled", true);
                channels.add(Map.copyOf(authAgent));
            }
        }

        return List.copyOf(channels);
    }

    // ── Coin auth definition ───────────────────────────────────────────────

    public Map<String, Object> buildCoinAuthDefinition(String coinId, CoinConfig coinConfig) {
        Map<String, Object> coin = new LinkedHashMap<>();
        CoinAuthConfig auth = coinConfig.auth();

        boolean restApiOperational = auth.restApi() && config().restApiEnabled() && activeApiKey.get() != null;

        Map<String, Object> channels = new LinkedHashMap<>();
        channels.put("webUi", auth.webUi());
        channels.put("restApi", restApiOperational);
        channels.put("telegram", auth.telegram());

        List<String> warnings = new ArrayList<>();
        if (auth.webUi() && !config().landingEnabled()) {
            warnings.add("web-ui channel is configured, but web-ui is globally disabled.");
        }
        if (auth.restApi() && !config().restApiEnabled()) {
            warnings.add("rest-api channel is configured, but rest-api is globally disabled.");
        }
        if (auth.restApi() && config().restApiEnabled() && activeApiKey.get() == null) {
            warnings.add("rest-api channel is configured, but no API key has been created yet.");
        }
        if (auth.telegram() && !config().telegramEnabled()) {
            warnings.add("telegram channel is configured, but telegram is globally disabled.");
        }

        List<Map<String, Object>> verificationAgents = new ArrayList<>();
        Map<String, AgentConfig> authAgents = config().secondaryAgents();
        for (String channelName : auth.mcpAuthChannels()) {
            String safeChannelName = safe(channelName);
            AgentConfig agentConfig = authAgents.get(channelName);

            boolean configured = agentConfig != null;
            String bind = agentConfig == null ? "unknown" : safe(agentConfig.bind());
            String connectUrl = (configured && !"-".equals(bind)) ? "http://" + bind : "unknown";
            String port = (agentConfig != null && agentConfig.port() > 0) ? Integer.toString(agentConfig.port()) : "unknown";

            Map<String, Object> verificationAgent = new LinkedHashMap<>();
            verificationAgent.put("name", safeChannelName);
            verificationAgent.put("enabled", configured);
            verificationAgent.put("connectUrl", connectUrl);
            verificationAgent.put("port", port);
            verificationAgent.put("status", configured ? "reachable (config)" : "unknown");
            verificationAgents.add(Map.copyOf(verificationAgent));
        }

        List<String> vetoChannels = auth.vetoChannels() == null ? List.of() : auth.vetoChannels();

        coin.put("coin", coinId);
        coin.put("coinIconName", coinIconName(coinId));
        coin.put("enabled", coinConfig.enabled());
        coin.put("daemonSecretFile", safe(coinConfig.daemonConfigSecretFile()));
        coin.put("walletSecretFile", safe(coinConfig.walletConfigSecretFile()));

        // Masked config for connection editing wizard
        boolean configured = false;
        Map<String, String> maskedConfig = null;
        try {
            maskedConfig = buildMaskedConfig(coinId, coinConfig);
            configured = maskedConfig != null;
        } catch (Exception e) {
            log.debug("Failed to load masked config for {}: {}", coinId, e.getMessage());
        }
        coin.put("configured", configured);
        coin.put("maskedConfig", maskedConfig);

        WalletSupervisor walletSupervisor = resolveSupervisor(coinId);
        if (walletSupervisor != null) {
            WalletSnapshot snap = walletSupervisor.snapshot();
            coin.put("connectionStatus", snap.status().name().toLowerCase());
            coin.put("lastLifeSign", snap.lastHeartbeat() == null ? "never" : formatInstant(snap.lastHeartbeat()));
            coin.put("maskedBalance", snap.totalBalance() == null ? "unknown" : snap.totalBalance().toPlainString());
            coin.put("disconnected", snap.status() != WalletStatus.AVAILABLE);

            if (snap.status() != WalletStatus.OFFLINE) {
                coin.put("transactions", loadTransactions(walletSupervisor));
            } else {
                coin.put("transactions", List.of());
            }

            if (snap.lastError() != null) {
                coin.put("connectionError", safe(snap.lastError()));
            }
        } else {
            coin.put("connectionStatus", coinConfig.enabled() ? "not connected" : "disabled");
            coin.put("lastLifeSign", "n/a");
            coin.put("maskedBalance", "n/a");
            coin.put("disconnected", coinConfig.enabled());
        }
        coin.put("channels", Map.copyOf(channels));
        coin.put("channelWarnings", List.copyOf(warnings));
        coin.put("verificationAgents", List.copyOf(verificationAgents));
        coin.put("quorumLine", auth.minApprovalsRequired() + "-of-N");
        coin.put("vetoChannelsLine", vetoChannels.isEmpty() ? "none" : String.join(", ", vetoChannels));
        coin.put("autoAcceptRules", mapApprovalRules(auth.autoAccept()));
        coin.put("autoDenyRules", mapApprovalRules(auth.autoDeny()));
        coin.put("authWebUi", auth.webUi());
        coin.put("authRestApi", auth.restApi());
        coin.put("authTelegram", auth.telegram());
        coin.put("minApprovalsRequired", auth.minApprovalsRequired());
        coin.put("vetoChannels", vetoChannels);

        List<String> vetoChannelOptions = new ArrayList<>();
        vetoChannelOptions.add("web-ui");
        vetoChannelOptions.add("rest-api");
        vetoChannelOptions.add("telegram");
        for (Map.Entry<String, AgentConfig> entry : authAgents.entrySet()) {
            if (entry.getValue() != null && entry.getValue().visible()) {
                vetoChannelOptions.add(entry.getKey());
            }
        }
        coin.put("vetoChannelOptions", vetoChannelOptions);

        List<String> mcpAuthChannels = auth.mcpAuthChannels() == null ? List.of() : auth.mcpAuthChannels();
        coin.put("mcpAuthChannels", mcpAuthChannels);

        List<String> mcpAuthChannelOptions = new ArrayList<>();
        for (Map.Entry<String, AgentConfig> entry : authAgents.entrySet()) {
            if (entry.getValue() != null && entry.getValue().visible()) {
                mcpAuthChannelOptions.add(entry.getKey());
            }
        }
        coin.put("mcpAuthChannelOptions", mcpAuthChannelOptions);

        // Deposit address from KvStore
        String lastDepositAddress = readLastDepositAddress(coinId);
        coin.put("lastDepositAddress", lastDepositAddress == null ? "" : lastDepositAddress);

        if (!coin.containsKey("transactions")) {
            coin.put("transactions", List.of());
        }

        return Map.copyOf(coin);
    }

    private static final int MAX_TRANSACTIONS = 100;

    private List<Map<String, Object>> loadTransactions(WalletSupervisor supervisor) {
        if (supervisor == null) {
            return List.of();
        }
        try {
            List<Transaction> incoming = supervisor.execute(wallet -> wallet.recentIncoming());
            List<Transaction> outgoing = supervisor.execute(wallet -> wallet.recentOutgoing());

            List<Transaction> merged = new ArrayList<>();
            if (incoming != null) merged.addAll(incoming);
            if (outgoing != null) merged.addAll(outgoing);

            // Sort by timestamp descending (most recent first)
            merged.sort((a, b) -> b.timestamp().compareTo(a.timestamp()));

            // Cap at maximum
            if (merged.size() > MAX_TRANSACTIONS) {
                merged = merged.subList(0, MAX_TRANSACTIONS);
            }

            List<Map<String, Object>> result = new ArrayList<>();
            for (Transaction tx : merged) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("txId", tx.txId());
                row.put("txIdShort", abbreviateId(tx.txId()));
                row.put("direction", tx.direction().name().toLowerCase());
                row.put("address", safe(tx.address()));
                row.put("amount", tx.amount().toPlainString());
                row.put("confirmations", Integer.toString(tx.confirmations()));
                row.put("confirmed", tx.confirmed());
                row.put("timestamp", formatInstant(tx.timestamp()));
                row.put("epochMillis", tx.timestamp().toEpochMilli());
                result.add(Map.copyOf(row));
            }
            return List.copyOf(result);
        } catch (Exception e) {
            log.warn("Failed to load transactions: {}", e.getMessage(), e);
            return List.of();
        }
    }

    // ── Deposit address persistence ─────────────────────────────────────────

    public String readLastDepositAddress(String coinId) {
        if (kvStore == null) {
            return null;
        }
        return kvStore.get(KV_DEPOSIT_ADDRESS_PREFIX + coinId).orElse(null);
    }

    public void persistDepositAddress(String coinId, String address) {
        if (kvStore == null) {
            return;
        }
        kvStore.put(KV_DEPOSIT_ADDRESS_PREFIX + coinId, address);
    }

    // ── Approval rules ─────────────────────────────────────────────────────

    private List<Map<String, Object>> mapApprovalRules(List<ApprovalRule> rules) {
        List<Map<String, Object>> mappedRules = new ArrayList<>();
        if (rules == null || rules.isEmpty()) {
            return List.of();
        }

        int index = 1;
        for (ApprovalRule rule : rules) {
            ApprovalCriteria criteria = rule == null ? null : rule.criteria();

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("index", index++);
            entry.put("type", criteria == null ? "-" : criteria.type().tomlValue());
            entry.put("typeLabel", criteria == null ? "-" : switch (criteria.type()) {
                case VALUE_GT -> "single amount >";
                case VALUE_LT -> "single amount <";
                case CUMULATED_VALUE_GT -> "sum in window >";
                case CUMULATED_VALUE_LT -> "sum in window <";
            });
            entry.put("value", criteria == null ? "-" : Double.toString(criteria.value()));
            entry.put("period", criteria == null || criteria.period() == null ? "-" : formatDurationFriendly(criteria.period()));
            entry.put("requiresPeriod", criteria != null && criteria.type().requiresPeriod());
            String[] periodParts = splitPeriodAmountUnit(criteria == null ? null : criteria.period());
            entry.put("periodAmount", periodParts[0]);
            entry.put("periodUnit", periodParts[1]);
            mappedRules.add(Map.copyOf(entry));
        }

        return List.copyOf(mappedRules);
    }

    /**
     * Splits a Duration into a [amount, unit] pair for the period dropdown.
     * Returns the best-fit whole number for weeks/days/hours.
     */
    private static String[] splitPeriodAmountUnit(java.time.Duration period) {
        if (period == null) return new String[]{"", "h"};
        long totalSeconds = period.getSeconds();
        if (totalSeconds <= 0) return new String[]{"", "h"};

        long weeks = totalSeconds / (7 * 24 * 3600);
        if (weeks > 0 && totalSeconds % (7 * 24 * 3600) == 0) {
            return new String[]{String.valueOf(weeks), "w"};
        }
        long days = totalSeconds / (24 * 3600);
        if (days > 0 && totalSeconds % (24 * 3600) == 0) {
            return new String[]{String.valueOf(days), "d"};
        }
        long hours = totalSeconds / 3600;
        if (hours > 0 && totalSeconds % 3600 == 0) {
            return new String[]{String.valueOf(hours), "h"};
        }
        // Fall back to hours (rounded up)
        return new String[]{String.valueOf(Math.max(1, (totalSeconds + 3599) / 3600)), "h"};
    }

    // ── Coin → supervisor lookup ────────────────────────────────────────────

    private WalletSupervisor resolveSupervisor(String coinId) {
        if (coinId == null) return null;
        Coin coin = switch (coinId.toLowerCase(Locale.ROOT)) {
            case "bitcoin" -> Coin.BTC;
            case "litecoin" -> Coin.LTC;
            case "monero" -> Coin.XMR;
            default -> null;
        };
        return coin != null ? walletSupervisors.get(coin) : null;
    }

    // ── Settings model ────────────────────────────────────────────────────

    public Map<String, Object> buildWebUiSettingsModel() {
        KonkinConfig c = config();
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("passwordProtectionEnabled", c.landingPasswordProtectionEnabled());
        s.put("autoReloadEnabled", c.landingAutoReloadEnabled());
        s.put("assetsAutoReloadEnabled", c.landingAssetsAutoReloadEnabled());
        return Map.copyOf(s);
    }

    public Map<String, Object> buildRestApiSettingsModel() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("restApiEnabled", config().restApiEnabled());
        return Map.copyOf(s);
    }

    public Map<String, Object> buildTelegramSettingsModel() {
        return buildTelegramSettingsModel(null);
    }

    public Map<String, Object> buildTelegramSettingsModel(io.konkin.telegram.TelegramSecretService telegramSecretService) {
        KonkinConfig c = config();
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("telegramEnabled", c.telegramEnabled());
        s.put("telegramApiBaseUrl", safe(c.telegramApiBaseUrl()));
        s.put("telegramAutoDenyTimeout", c.telegramAutoDenyTimeout() != null ? formatDurationFriendly(c.telegramAutoDenyTimeout()) : "");

        if (telegramSecretService != null) {
            io.konkin.telegram.TelegramSecretService.TelegramSecret secret = telegramSecretService.readSecret();
            boolean hasToken = telegramSecretService.hasConfiguredBotToken(secret);
            s.put("telegramBotTokenConfigured", hasToken);
            s.put("telegramBotTokenMasked", io.konkin.telegram.TelegramWebController.maskBotToken(hasToken ? secret.botToken() : ""));
        } else {
            s.put("telegramBotTokenConfigured", false);
            s.put("telegramBotTokenMasked", "not configured");
        }

        return Map.copyOf(s);
    }

    public Map<String, Object> buildAgentDetailModel(String agentName) {
        AgentConfig agentConfig = config().secondaryAgents().get(agentName);
        if (agentConfig == null) return null;

        String bind = safe(agentConfig.bind());
        int port = agentConfig.port();
        String endpointBase = "http://" + displayBind(bind) + ":" + port;

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", safe(agentName));
        m.put("authChannelId", "verification-agent:" + agentName);
        m.put("bind", bind);
        m.put("port", port > 0 ? Integer.toString(port) : "-");
        m.put("healthPath", endpointBase + "/health");
        m.put("oauthTokenPath", endpointBase + "/oauth/token");
        m.put("ssePath", endpointBase + "/sse");
        m.put("secretFile", safe(agentConfig.secretFile()));
        return Map.copyOf(m);
    }

    public Map<String, Object> buildAgentSettingsModel(String agentName) {
        AgentConfig ac = config().secondaryAgents().get(agentName);
        if (ac == null) return null;
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("visible", ac.visible());
        s.put("bind", safe(ac.bind()));
        s.put("port", ac.port());
        return Map.copyOf(s);
    }

    public Map<String, Object> buildAgentMcpRegistration(String agentName) {
        AgentConfig agentConfig = config().secondaryAgents().get(agentName);
        if (agentConfig == null) return null;

        String bind = safe(agentConfig.bind());
        int port = agentConfig.port();
        String endpointBase = port > 0 ? "http://" + displayBind(bind) + ":" + port : "-";

        String tokenEndpoint = port > 0 ? endpointBase + "/oauth/token" : "-";
        String sseEndpoint = port > 0 ? endpointBase + "/sse" : "-";

        String tokenCommand = port > 0
                ? """
                curl -s -X POST "%s" \\
                  -d "grant_type=client_credentials" \\
                  -d "client_id=%s" \\
                  -d "client_secret=YOUR_SECRET"
                """.strip().formatted(tokenEndpoint, agentName)
                : "-";

        Map<String, Object> reg = new LinkedHashMap<>();
        reg.put("agentName", safe(agentName));
        reg.put("sseEndpoint", sseEndpoint);
        reg.put("tokenEndpoint", tokenEndpoint);
        reg.put("tokenCommand", tokenCommand);
        reg.put("agentCommands", buildAgentCommands(port > 0, sseEndpoint, "konkin-" + agentName));
        reg.put("skillPath", "documents/SKILL-auth-agent.md");
        return Map.copyOf(reg);
    }

    public Map<String, Object> buildSettingsModel() {
        KonkinConfig c = config();
        Map<String, Object> s = new LinkedHashMap<>();

        // Server
        s.put("serverHost", safe(c.host()));
        s.put("serverPort", c.port());
        s.put("logLevel", safe(c.logLevel()));
        s.put("logFile", safe(c.logFile()));
        s.put("logRotateMaxSizeMb", c.logRotateMaxSizeMb());
        s.put("secretsDir", safe(c.secretsDir()));

        // Database
        s.put("dbUrl", safe(c.dbUrl()));
        s.put("dbUser", safe(c.dbUser()));
        s.put("dbPassword", safe(c.dbPassword()));
        s.put("dbPoolSize", c.dbPoolSize());

        // Web UI
        s.put("passwordProtectionEnabled", c.landingPasswordProtectionEnabled());
        s.put("autoReloadEnabled", c.landingAutoReloadEnabled());
        s.put("assetsAutoReloadEnabled", c.landingAssetsAutoReloadEnabled());

        // REST API
        s.put("restApiEnabled", c.restApiEnabled());

        // Telegram
        s.put("telegramEnabled", c.telegramEnabled());
        s.put("telegramApiBaseUrl", safe(c.telegramApiBaseUrl()));
        s.put("telegramAutoDenyTimeout", c.telegramAutoDenyTimeout() != null ? formatDurationFriendly(c.telegramAutoDenyTimeout()) : "");

        // Debug
        s.put("debugEnabled", c.debugEnabled());
        s.put("debugSeedFakeData", c.debugSeedFakeData());

        // Secondary agents
        Map<String, Object> agents = new LinkedHashMap<>();
        for (Map.Entry<String, AgentConfig> entry : c.secondaryAgents().entrySet()) {
            AgentConfig ac = entry.getValue();
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("visible", ac.visible());
            a.put("bind", safe(ac.bind()));
            a.put("port", ac.port());
            agents.put(entry.getKey(), Map.copyOf(a));
        }
        s.put("secondaryAgents", Map.copyOf(agents));

        return Map.copyOf(s);
    }

    // ── Shared record ──────────────────────────────────────────────────────

    public record TablePageData(List<Map<String, Object>> rows, Map<String, Object> pageMeta) {
    }
}