package io.konkin.web.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.javalin.http.Context;
import io.javalin.http.Cookie;
import io.javalin.http.SameSite;
import io.konkin.config.KonkinConfig;
import io.konkin.db.AuthQueueStore;
import io.konkin.security.PasswordFileManager;
import io.konkin.web.service.LandingPageService;
import io.konkin.web.service.TelegramSecretService;
import io.konkin.web.service.TelegramService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HTTP controller for landing page '/' with explicit password-only login.
 */
public class LandingPageController {

    private static final Logger log = LoggerFactory.getLogger(LandingPageController.class);

    private static final String SESSION_COOKIE_NAME = "konkin_landing_session";
    private static final Duration SESSION_TTL = Duration.ofHours(12);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final DateTimeFormatter TS_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter TS_MINUTE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter TS_LOG_MINUTE_FORMAT = DateTimeFormatter.ofPattern("yyyy MM dd HH:mm").withZone(ZoneOffset.UTC);
    private static final Set<String> SUPPORTED_COIN_ICONS = Set.of("bitcoin", "ethereum", "monero", "litecoin");

    private final LandingPageService landingPageService;
    private final boolean passwordProtectionEnabled;
    private final PasswordFileManager passwordFileManager;
    private final boolean telegramEnabled;
    private final List<String> configuredTelegramChatIds;
    private final TelegramService telegramService;
    private final TelegramSecretService telegramSecretService;
    private final AuthQueueStore authQueueStore;
    private final KonkinConfig config;

    private final Map<String, Instant> activeSessions = new ConcurrentHashMap<>();

    public LandingPageController(
            LandingPageService landingPageService,
            boolean passwordProtectionEnabled,
            PasswordFileManager passwordFileManager,
            boolean telegramEnabled,
            List<String> configuredTelegramChatIds,
            TelegramService telegramService,
            TelegramSecretService telegramSecretService,
            AuthQueueStore authQueueStore,
            KonkinConfig config
    ) {
        if (passwordProtectionEnabled && passwordFileManager == null) {
            throw new IllegalArgumentException("passwordFileManager is required when password protection is enabled");
        }

        if (telegramEnabled && (telegramService == null || telegramSecretService == null)) {
            throw new IllegalArgumentException("telegramService and telegramSecretService are required when telegram is enabled");
        }

        if (config == null) {
            throw new IllegalArgumentException("config is required");
        }

        this.landingPageService = landingPageService;
        this.passwordProtectionEnabled = passwordProtectionEnabled;
        this.passwordFileManager = passwordFileManager;
        this.telegramEnabled = telegramEnabled;
        this.configuredTelegramChatIds = configuredTelegramChatIds == null ? List.of() : List.copyOf(configuredTelegramChatIds);
        this.telegramService = telegramService;
        this.telegramSecretService = telegramSecretService;
        this.authQueueStore = authQueueStore;
        this.config = config;
    }

    public void handleRoot(Context ctx) {
        renderLandingForPage(ctx, "queue");
    }

    public void handleLog(Context ctx) {
        renderLandingForPage(ctx, "log");
    }

    public void handleDetailsPage(Context ctx) {
        if (authQueueStore == null) {
            ctx.status(404);
            return;
        }

        if (passwordProtectionEnabled && !hasValidSession(ctx)) {
            showLogin(ctx, false);
            return;
        }

        String requestId = defaultIfBlank(ctx.queryParam("id"), "").trim();
        if (requestId.isEmpty()) {
            ctx.status(400);
            ctx.contentType("text/plain; charset=UTF-8");
            ctx.result("Missing required query parameter: id");
            return;
        }

        AuthQueueStore.ApprovalRequestRow row = authQueueStore.findApprovalRequestById(requestId);
        if (row == null) {
            ctx.status(404);
            ctx.contentType("text/plain; charset=UTF-8");
            ctx.result("No approval request found for id: " + requestId);
            return;
        }

        Map<String, AuthQueueStore.RequestDependencies> dependenciesByRequestId =
                authQueueStore.loadRequestDependencies(List.of(requestId));

        AuthQueueStore.RequestDependencies dependencies = dependenciesByRequestId.getOrDefault(
                requestId,
                new AuthQueueStore.RequestDependencies(List.of(), List.of(), List.of(), List.of())
        );

        ctx.contentType("text/plain; charset=UTF-8");
        ctx.result(toPrettyJson(buildDetailsObject(row, dependencies)));
    }

    public void handleTelegramPage(Context ctx) {
        if (!telegramEnabled) {
            ctx.status(404);
            return;
        }

        renderLandingForPage(ctx, "telegram");
    }

    public void handleAuthDefinitionsPage(Context ctx) {
        if (passwordProtectionEnabled && !hasValidSession(ctx)) {
            showLogin(ctx, false);
            return;
        }

        ctx.contentType("text/html; charset=UTF-8");
        ctx.result(landingPageService.renderAuthDefinitions(
                passwordProtectionEnabled,
                buildAuthDefinitionsModel()
        ));
    }

    public void handleLoginPage(Context ctx) {
        if (!passwordProtectionEnabled) {
            ctx.redirect("/");
            return;
        }

        if (hasValidSession(ctx)) {
            ctx.redirect("/");
            return;
        }

        showLogin(ctx, false);
    }

    public void handleLoginSubmit(Context ctx) {
        if (!passwordProtectionEnabled) {
            ctx.redirect("/");
            return;
        }

        String password = ctx.formParam("password");
        if (password == null || password.isBlank() || !passwordFileManager.verifyPassword(password)) {
            log.warn("Failed landing page login from {}", ctx.ip());
            showLogin(ctx, true);
            return;
        }

        String sessionToken = newSessionToken();
        activeSessions.put(sessionToken, Instant.now().plus(SESSION_TTL));

        Cookie sessionCookie = new Cookie(SESSION_COOKIE_NAME, sessionToken);
        sessionCookie.setPath("/");
        sessionCookie.setHttpOnly(true);
        sessionCookie.setSecure(false);
        sessionCookie.setMaxAge((int) SESSION_TTL.toSeconds());
        sessionCookie.setSameSite(SameSite.STRICT);
        ctx.cookie(sessionCookie);
        ctx.redirect("/");
    }

    public void handleLogout(Context ctx) {
        String token = ctx.cookie(SESSION_COOKIE_NAME);
        if (token != null) {
            activeSessions.remove(token);
        }
        ctx.removeCookie(SESSION_COOKIE_NAME, "/");
        ctx.redirect("/");
    }

    public void handleTelegramApprove(Context ctx) {
        if (!telegramEnabled) {
            ctx.status(404);
            return;
        }

        if (passwordProtectionEnabled && !hasValidSession(ctx)) {
            showLogin(ctx, false);
            return;
        }

        String chatIdInput = ctx.formParam("chat_id");
        String chatId = chatIdInput == null ? "" : chatIdInput.trim();
        if (chatId.isEmpty()) {
            renderLandingForPage(ctx, "telegram", "Chat ID cannot be empty.", true, "");
            return;
        }

        if (telegramSecretService.approveChatId(chatId)) {
            renderLandingForPage(ctx, "telegram", "Approved Telegram chat ID " + chatId + ".", false, "");
            return;
        }

        log.warn("Failed Telegram chat ID approval from {} for chatId={}", ctx.ip(), chatId);
        renderLandingForPage(ctx, "telegram", "Failed to approve Telegram chat ID.", true, "");
    }

    public void handleTelegramSubmit(Context ctx) {
        if (!telegramEnabled) {
            ctx.status(404);
            return;
        }

        if (passwordProtectionEnabled && !hasValidSession(ctx)) {
            showLogin(ctx, false);
            return;
        }

        String messageInput = ctx.formParam("telegram_message");
        String messageText = messageInput == null ? "" : messageInput.trim();

        if (messageText.isEmpty()) {
            renderLandingForPage(ctx, "telegram", "Message cannot be empty.", true, messageInput == null ? "" : messageInput);
            return;
        }

        if (messageText.length() > 4096) {
            renderLandingForPage(ctx, "telegram", "Message is too long (max 4096 characters).", true, messageInput);
            return;
        }

        List<String> targetChatIds = approvedChatIds();
        TelegramService.SendResult result = telegramService.sendMessage(messageText, targetChatIds);
        if (result.success()) {
            renderLandingForPage(ctx, "telegram", "Telegram message sent.", false, "");
            return;
        }

        String detail = result.detail() == null || result.detail().isBlank() ? "unknown error" : result.detail();
        log.warn("Telegram send failed from {}: {}", ctx.ip(), detail);
        renderLandingForPage(ctx, "telegram", "Telegram send failed: " + detail, true, messageInput);
    }

    private void showLogin(Context ctx, boolean invalidPassword) {
        ctx.status(invalidPassword ? 401 : 200);
        ctx.contentType("text/html; charset=UTF-8");
        ctx.result(landingPageService.renderLogin(invalidPassword));
    }

    private void renderLandingForPage(Context ctx, String activePage) {
        renderLandingForPage(ctx, activePage, "", false, "");
    }

    private void renderLandingForPage(
            Context ctx,
            String activePage,
            String telegramNotice,
            boolean telegramNoticeError,
            String telegramDraft
    ) {
        if (!passwordProtectionEnabled || hasValidSession(ctx)) {
            TelegramPageData telegramPageData = loadTelegramPageData();
            TablePageData queuePageData = loadQueuePageData(ctx);
            TablePageData auditPageData = loadAuditPageData(ctx);
            TablePageData logQueuePageData = loadLogQueuePageData(ctx);

            ctx.contentType("text/html; charset=UTF-8");
            ctx.result(landingPageService.renderLanding(
                    passwordProtectionEnabled,
                    activePage,
                    telegramNotice,
                    telegramNoticeError,
                    telegramDraft,
                    telegramPageData.chatRequests(),
                    telegramPageData.approvedChats(),
                    queuePageData.rows(),
                    queuePageData.pageMeta(),
                    auditPageData.rows(),
                    auditPageData.pageMeta(),
                    logQueuePageData.rows(),
                    logQueuePageData.pageMeta()));
            return;
        }

        showLogin(ctx, false);
    }

    private TelegramPageData loadTelegramPageData() {
        if (!telegramEnabled) {
            return new TelegramPageData(List.of(), List.of());
        }

        List<String> approvedChatIds = approvedChatIds();
        Set<String> approvedSet = new HashSet<>(approvedChatIds);

        Map<String, String> usernameByChatId = new LinkedHashMap<>();
        List<Map<String, String>> requestRows = new ArrayList<>();
        for (TelegramService.ChatRequest request : telegramService.discoverChatRequests()) {
            if (request.chatId() == null || request.chatId().isBlank()) {
                continue;
            }

            String chatId = request.chatId().trim();
            String username = request.chatUsername() == null ? "" : request.chatUsername().trim();
            if (!username.isEmpty()) {
                usernameByChatId.put(chatId, username);
            }

            if (approvedSet.contains(chatId)) {
                continue;
            }

            Map<String, String> row = new LinkedHashMap<>();
            row.put("chatId", chatId);
            row.put("chatType", request.chatType() == null || request.chatType().isBlank() ? "unknown" : request.chatType().trim());
            row.put("chatTitle", request.chatTitle() == null || request.chatTitle().isBlank() ? "(no title)" : request.chatTitle().trim());
            requestRows.add(Map.copyOf(row));
        }

        List<Map<String, String>> approvedRows = new ArrayList<>();
        for (String chatId : approvedChatIds) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("chatId", chatId);
            row.put("chatUsername", usernameByChatId.getOrDefault(chatId, ""));
            approvedRows.add(Map.copyOf(row));
        }

        return new TelegramPageData(List.copyOf(requestRows), List.copyOf(approvedRows));
    }

    private List<String> approvedChatIds() {
        if (!telegramEnabled) {
            return List.of();
        }

        TelegramSecretService.TelegramSecret secret = telegramSecretService.readSecret();
        return TelegramSecretService.mergeChatIds(configuredTelegramChatIds, secret.chatIds());
    }

    private boolean hasValidSession(Context ctx) {
        String token = ctx.cookie(SESSION_COOKIE_NAME);
        if (token == null || token.isBlank()) {
            return false;
        }

        Instant expiry = activeSessions.get(token);
        if (expiry == null) {
            return false;
        }

        if (expiry.isBefore(Instant.now())) {
            activeSessions.remove(token);
            ctx.removeCookie(SESSION_COOKIE_NAME);
            return false;
        }

        return true;
    }

    private String newSessionToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private TablePageData loadQueuePageData(Context ctx) {
        if (authQueueStore == null) {
            return emptyPageData("expires_at", "asc");
        }

        String sortBy = defaultIfBlank(ctx.queryParam("queue_sort"), "expires_at");
        String sortDir = defaultIfBlank(ctx.queryParam("queue_dir"), "asc");
        int page = parsePositiveInt(ctx.queryParam("queue_page"), 1);
        int pageSize = parsePositiveInt(ctx.queryParam("queue_page_size"), 25);

        AuthQueueStore.PageResult<AuthQueueStore.ApprovalRequestRow> result =
                authQueueStore.pagePendingApprovalRequests(sortBy, sortDir, page, pageSize);

        return mapApprovalPageData(result);
    }

    private TablePageData loadLogQueuePageData(Context ctx) {
        if (authQueueStore == null) {
            TablePageData empty = emptyPageData("updated_at", "desc");
            Map<String, Object> pageMeta = new LinkedHashMap<>(empty.pageMeta());
            pageMeta.put("filterQuery", "");
            pageMeta.put("filterText", "");
            pageMeta.put("filterCoin", "");
            pageMeta.put("filterTool", "");
            pageMeta.put("filterState", "");
            pageMeta.put("filterCoins", List.of());
            pageMeta.put("filterTools", List.of());
            pageMeta.put("filterStates", List.of());
            return new TablePageData(empty.rows(), Map.copyOf(pageMeta));
        }

        String sortBy = defaultIfBlank(ctx.queryParam("log_queue_sort"), "updated_at");
        String sortDir = defaultIfBlank(ctx.queryParam("log_queue_dir"), "desc");
        int page = parsePositiveInt(ctx.queryParam("log_queue_page"), 1);
        int pageSize = parsePositiveInt(ctx.queryParam("log_queue_page_size"), 25);

        String filterText = defaultIfBlank(ctx.queryParam("log_queue_filter"), "").trim();
        String filterCoin = defaultIfBlank(ctx.queryParam("log_queue_coin"), "").trim();
        String filterTool = defaultIfBlank(ctx.queryParam("log_queue_tool"), "").trim();
        String filterState = defaultIfBlank(ctx.queryParam("log_queue_state"), "").trim();

        AuthQueueStore.PageResult<AuthQueueStore.ApprovalRequestRow> result =
                authQueueStore.pageNonPendingApprovalRequests(
                        sortBy,
                        sortDir,
                        page,
                        pageSize,
                        filterCoin,
                        filterTool,
                        filterState,
                        filterText
                );

        AuthQueueStore.LogQueueFilterOptions options = authQueueStore.loadNonPendingFilterOptions();

        TablePageData mapped = mapApprovalPageData(result);
        Map<String, Object> pageMeta = new LinkedHashMap<>(mapped.pageMeta());
        pageMeta.put("filterQuery", filterText);
        pageMeta.put("filterText", filterText);
        pageMeta.put("filterCoin", filterCoin);
        pageMeta.put("filterTool", filterTool);
        pageMeta.put("filterState", filterState);
        pageMeta.put("filterCoins", options.coins());
        pageMeta.put("filterTools", options.tools());
        pageMeta.put("filterStates", options.states());
        return new TablePageData(mapped.rows(), Map.copyOf(pageMeta));
    }

    private TablePageData mapApprovalPageData(AuthQueueStore.PageResult<AuthQueueStore.ApprovalRequestRow> result) {
        List<String> requestIds = new ArrayList<>();
        for (AuthQueueStore.ApprovalRequestRow row : result.rows()) {
            if (row.id() != null && !row.id().isBlank()) {
                requestIds.add(row.id());
            }
        }

        Map<String, AuthQueueStore.RequestDependencies> dependenciesByRequestId = authQueueStore.loadRequestDependencies(requestIds);
        Instant now = Instant.now();

        List<Map<String, Object>> rows = new ArrayList<>();
        for (AuthQueueStore.ApprovalRequestRow row : result.rows()) {
            Map<String, Object> mapped = new LinkedHashMap<>();
            String state = normalizeState(row.state());
            String stateLower = state.toLowerCase(Locale.ROOT);
            int approvalsGranted = row.approvalsGranted();
            int minApprovalsRequired = row.minApprovalsRequired();

            AuthQueueStore.RequestDependencies dependencies = dependenciesByRequestId.getOrDefault(
                    row.id(),
                    new AuthQueueStore.RequestDependencies(List.of(), List.of(), List.of(), List.of())
            );

            Set<String> deciders = new LinkedHashSet<>();
            for (AuthQueueStore.VoteDetail vote : dependencies.votes()) {
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
            mapped.put("deciders", deciders.isEmpty() ? "-" : String.join(", ", deciders));
            mapped.put("detailsJson", toPrettyJson(buildDetailsObject(row, dependencies)));
            rows.add(Map.copyOf(mapped));
        }

        return new TablePageData(List.copyOf(rows), pageMetaFrom(result));
    }

    private Map<String, Object> buildDetailsObject(
            AuthQueueStore.ApprovalRequestRow row,
            AuthQueueStore.RequestDependencies dependencies
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
        for (AuthQueueStore.StateTransitionDetail transition : dependencies.transitions()) {
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
        for (AuthQueueStore.RequestChannelDetail channel : dependencies.channels()) {
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
        for (AuthQueueStore.VoteDetail vote : dependencies.votes()) {
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
        for (AuthQueueStore.ExecutionAttemptDetail execution : dependencies.executionAttempts()) {
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

    private TablePageData loadAuditPageData(Context ctx) {
        if (authQueueStore == null) {
            return emptyPageData("created_at", "desc");
        }

        String sortBy = defaultIfBlank(ctx.queryParam("audit_sort"), "created_at");
        String sortDir = defaultIfBlank(ctx.queryParam("audit_dir"), "desc");
        int page = parsePositiveInt(ctx.queryParam("audit_page"), 1);
        int pageSize = parsePositiveInt(ctx.queryParam("audit_page_size"), 25);

        AuthQueueStore.PageResult<AuthQueueStore.StateTransitionRow> result =
                authQueueStore.pageStateTransitions(sortBy, sortDir, page, pageSize);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (AuthQueueStore.StateTransitionRow row : result.rows()) {
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

        return new TablePageData(List.copyOf(rows), pageMetaFrom(result));
    }

    private static TablePageData emptyPageData(String sortBy, String sortDir) {
        Map<String, Object> pageMeta = new LinkedHashMap<>();
        pageMeta.put("page", 1);
        pageMeta.put("pageSize", 25);
        pageMeta.put("totalRows", 0L);
        pageMeta.put("totalPages", 0);
        pageMeta.put("sortBy", sortBy);
        pageMeta.put("sortDir", sortDir);
        pageMeta.put("hasPrev", false);
        pageMeta.put("hasNext", false);
        pageMeta.put("prevPage", 1);
        pageMeta.put("nextPage", 1);
        return new TablePageData(List.of(), Map.copyOf(pageMeta));
    }

    private static Map<String, Object> pageMetaFrom(AuthQueueStore.PageResult<?> result) {
        Map<String, Object> pageMeta = new LinkedHashMap<>();
        pageMeta.put("page", result.page());
        pageMeta.put("pageSize", result.pageSize());
        pageMeta.put("totalRows", result.totalRows());
        pageMeta.put("totalPages", result.totalPages());
        pageMeta.put("sortBy", result.sortBy());
        pageMeta.put("sortDir", result.sortDir());
        pageMeta.put("hasPrev", result.page() > 1);
        pageMeta.put("hasNext", result.totalPages() > 0 && result.page() < result.totalPages());
        pageMeta.put("prevPage", Math.max(1, result.page() - 1));
        pageMeta.put("nextPage", result.totalPages() <= 0 ? 1 : Math.min(result.totalPages(), result.page() + 1));
        return Map.copyOf(pageMeta);
    }

    private static int parsePositiveInt(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw);
            return value > 0 ? value : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String defaultIfBlank(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static String normalizeState(String state) {
        if (state == null || state.isBlank()) {
            return "UNKNOWN";
        }
        return state.trim().toUpperCase(Locale.ROOT);
    }

    private static String abbreviateId(String id) {
        if (id == null || id.isBlank()) {
            return "-";
        }
        String trimmed = id.trim();
        if (trimmed.length() <= 5) {
            return trimmed;
        }
        return trimmed.substring(0, 5) + "...";
    }

    private static String firstFive(String id) {
        if (id == null || id.isBlank()) {
            return "-";
        }
        String trimmed = id.trim();
        return trimmed.length() <= 5 ? trimmed : trimmed.substring(0, 5);
    }

    private static String coinIconName(String coin) {
        if (coin == null || coin.isBlank()) {
            return "";
        }
        String normalized = coin.trim().toLowerCase(Locale.ROOT);
        return SUPPORTED_COIN_ICONS.contains(normalized) ? normalized : "";
    }

    private static String toStatusClass(String stateLower) {
        if ("completed".equals(stateLower) || "approved".equals(stateLower)) {
            return "approved";
        }
        if (
                "failed".equals(stateLower)
                        || "denied".equals(stateLower)
                        || "cancelled".equals(stateLower)
                        || "timed_out".equals(stateLower)
                        || "rejected".equals(stateLower)
                        || "expired".equals(stateLower)
        ) {
            return "cancelled";
        }
        return "pending";
    }

    private static String formatInstantMinute(Instant instant) {
        return instant == null ? "-" : TS_MINUTE_FORMAT.format(instant);
    }

    private static String formatLogMinute(Instant instant) {
        return instant == null ? "-" : TS_LOG_MINUTE_FORMAT.format(instant);
    }

    private static String formatInstant(Instant instant) {
        return instant == null ? "-" : TS_FORMAT.format(instant);
    }

    private static String formatRemaining(Instant expiresAt, Instant now) {
        if (expiresAt == null) {
            return "-";
        }

        long seconds = Duration.between(now, expiresAt).getSeconds();
        if (seconds <= 0) {
            return "expired";
        }
        if (seconds < 60) {
            return "in " + seconds + "sec";
        }

        long minutes = Math.max(1L, (seconds + 59) / 60);
        if (minutes < 60) {
            return "in " + minutes + "min";
        }

        long hours = Math.max(1L, (minutes + 59) / 60);
        if (hours < 48) {
            return "in " + hours + "h";
        }

        long days = Math.max(1L, (hours + 23) / 24);
        return "in " + days + "d";
    }

    private Map<String, Object> buildAuthDefinitionsModel() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("webUiEnabled", config.landingEnabled());
        root.put("telegramEnabled", config.telegramEnabled());

        List<Map<String, Object>> coins = new ArrayList<>();
        coins.add(buildCoinAuthDefinition("bitcoin", config.bitcoin()));
        coins.add(buildCoinAuthDefinition("litecoin", config.litecoin()));
        coins.add(buildCoinAuthDefinition("monero", config.monero()));
        root.put("coins", List.copyOf(coins));
        return Map.copyOf(root);
    }

    private Map<String, Object> buildCoinAuthDefinition(String coinId, KonkinConfig.CoinConfig coinConfig) {
        Map<String, Object> coin = new LinkedHashMap<>();
        KonkinConfig.CoinAuthConfig auth = coinConfig.auth();

        Map<String, Object> channels = new LinkedHashMap<>();
        channels.put("webUi", auth.webUi());
        channels.put("restApi", auth.restApi());
        channels.put("telegram", auth.telegram());

        List<String> warnings = new ArrayList<>();
        if (auth.webUi() && !config.landingEnabled()) {
            warnings.add("web-ui channel is configured, but web-ui is globally disabled.");
        }
        if (auth.telegram() && !config.telegramEnabled()) {
            warnings.add("telegram channel is configured, but telegram is globally disabled.");
        }

        coin.put("coin", coinId);
        coin.put("coinIconName", coinIconName(coinId));
        coin.put("enabled", coinConfig.enabled());
        coin.put("mcp", safe(auth.mcp()));
        coin.put("channels", Map.copyOf(channels));
        coin.put("channelWarnings", List.copyOf(warnings));
        coin.put("autoAcceptRules", mapApprovalRules(auth.autoAccept()));
        coin.put("autoDenyRules", mapApprovalRules(auth.autoDeny()));
        return Map.copyOf(coin);
    }

    private List<Map<String, Object>> mapApprovalRules(List<KonkinConfig.ApprovalRule> rules) {
        List<Map<String, Object>> mappedRules = new ArrayList<>();
        if (rules == null || rules.isEmpty()) {
            return List.of();
        }

        int index = 1;
        for (KonkinConfig.ApprovalRule rule : rules) {
            KonkinConfig.ApprovalCriteria criteria = rule == null ? null : rule.criteria();

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
            mappedRules.add(Map.copyOf(entry));
        }

        return List.copyOf(mappedRules);
    }

    private static String formatDurationFriendly(Duration duration) {
        if (duration == null) {
            return "-";
        }

        long totalSeconds = duration.getSeconds();
        long days = totalSeconds / 86_400;
        totalSeconds %= 86_400;
        long hours = totalSeconds / 3_600;
        totalSeconds %= 3_600;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;

        List<String> parts = new ArrayList<>();
        if (days > 0) {
            parts.add(days + "d");
        }
        if (hours > 0) {
            parts.add(hours + "h");
        }
        if (minutes > 0) {
            parts.add(minutes + "m");
        }
        if (seconds > 0) {
            parts.add(seconds + "s");
        }

        if (parts.isEmpty()) {
            return "0s";
        }

        return String.join(" ", parts);
    }

    private static String toPrettyJson(Map<String, Object> source) {
        try {
            return JSON.writeValueAsString(source);
        } catch (JsonProcessingException e) {
            return "{\n  \"error\": \"failed to render details\"\n}";
        }
    }

    private record TelegramPageData(List<Map<String, String>> chatRequests, List<Map<String, String>> approvedChats) {
    }

    private record TablePageData(List<Map<String, Object>> rows, Map<String, Object> pageMeta) {
    }
}
