package io.konkin.web.controller;

import io.javalin.http.Context;
import io.javalin.http.Cookie;
import io.javalin.http.SameSite;
import io.konkin.config.KonkinConfig;
import io.konkin.db.AuthQueueStore;
import io.konkin.db.entity.ApprovalChannelRow;
import io.konkin.db.entity.ApprovalRequestRow;
import io.konkin.db.entity.LogQueueFilterOptions;
import io.konkin.db.entity.PageResult;
import io.konkin.db.entity.RequestDependencies;
import io.konkin.db.entity.StateTransitionRow;
import io.konkin.db.entity.VoteDetail;
import io.konkin.security.PasswordFileManager;
import io.konkin.web.LandingPageMapper;
import io.konkin.web.LandingPageMapper.TablePageData;
import io.konkin.web.service.LandingPageService;
import io.konkin.web.service.TelegramSecretService;
import io.konkin.web.service.TelegramService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import static io.konkin.web.UiFormattingUtils.*;
import static io.konkin.web.WebUtils.*;

/**
 * HTTP controller for landing page '/' with explicit password-only login.
 */
public class LandingPageController {

    private static final Logger log = LoggerFactory.getLogger(LandingPageController.class);

    private static final String SESSION_COOKIE_NAME = "konkin_landing_session";
    private static final Duration SESSION_TTL = Duration.ofHours(12);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String WEB_UI_CHANNEL_ID = "web-ui";

    private final LandingPageService landingPageService;
    private final boolean passwordProtectionEnabled;
    private final PasswordFileManager passwordFileManager;
    private final boolean telegramEnabled;
    private final List<String> configuredTelegramChatIds;
    private final TelegramService telegramService;
    private final TelegramSecretService telegramSecretService;
    private final AuthQueueStore authQueueStore;
    private final KonkinConfig config;
    private final LandingPageMapper mapper;

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
            KonkinConfig config,
            LandingPageMapper mapper
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
        this.mapper = mapper;
    }

    // ── Page handlers ──────────────────────────────────────────────────────

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

        ApprovalRequestRow row = authQueueStore.findApprovalRequestById(requestId);
        if (row == null) {
            ctx.status(404);
            ctx.contentType("text/plain; charset=UTF-8");
            ctx.result("No approval request found for id: " + requestId);
            return;
        }

        Map<String, RequestDependencies> dependenciesByRequestId =
                authQueueStore.loadRequestDependencies(List.of(requestId));

        RequestDependencies dependencies = dependenciesByRequestId.getOrDefault(
                requestId,
                new RequestDependencies(List.of(), List.of(), List.of(), List.of())
        );

        ctx.contentType("text/plain; charset=UTF-8");
        ctx.result(toPrettyJson(mapper.buildDetailsObject(row, dependencies)));
    }

    public void handleTelegramPage(Context ctx) {
        if (!telegramEnabled) {
            ctx.status(404);
            return;
        }

        renderLandingForPage(ctx, "telegram");
    }

    public void handleWalletsPage(Context ctx) {
        if (passwordProtectionEnabled && !hasValidSession(ctx)) {
            showLogin(ctx, false);
            return;
        }

        ctx.contentType("text/html; charset=UTF-8");
        ctx.result(landingPageService.renderWallets(
                passwordProtectionEnabled,
                mapper.buildWalletsModel()
        ));
    }

    public void handleAuthChannelsPage(Context ctx) {
        if (passwordProtectionEnabled && !hasValidSession(ctx)) {
            showLogin(ctx, false);
            return;
        }

        ctx.contentType("text/html; charset=UTF-8");
        ctx.result(landingPageService.renderAuthChannels(
                passwordProtectionEnabled,
                buildAuthChannelsModel()
        ));
    }

    public void handleDriverAgentPage(Context ctx) {
        if (passwordProtectionEnabled && !hasValidSession(ctx)) {
            showLogin(ctx, false);
            return;
        }

        ctx.contentType("text/html; charset=UTF-8");
        ctx.result(landingPageService.renderDriverAgent(
                passwordProtectionEnabled,
                mapper.buildDriverAgentModel()
        ));
    }

    // ── Login / logout ─────────────────────────────────────────────────────

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

    // ── Queue decision handlers ────────────────────────────────────────────

    public void handleQueueApprove(Context ctx) {
        handleQueueDecision(ctx, "approve");
    }

    public void handleQueueDeny(Context ctx) {
        handleQueueDecision(ctx, "deny");
    }

    // ── Telegram handlers ──────────────────────────────────────────────────

    public void handleTelegramApprove(Context ctx) {
        if (!telegramEnabled) {
            ctx.status(404);
            return;
        }

        if (passwordProtectionEnabled && !hasValidSession(ctx)) {
            showLogin(ctx, false);
            return;
        }

        String sourcePage = defaultIfBlank(ctx.formParam("source_page"), "").trim();
        boolean sourceAuthChannels = "auth_channels".equalsIgnoreCase(sourcePage);

        String chatIdInput = ctx.formParam("chat_id");
        String chatId = chatIdInput == null ? "" : chatIdInput.trim();
        if (chatId.isEmpty()) {
            if (sourceAuthChannels) {
                ctx.redirect("/auth_channels");
                return;
            }
            renderLandingForPage(ctx, "telegram", "Chat ID cannot be empty.", true, "");
            return;
        }

        String chatType = defaultIfBlank(ctx.formParam("chat_type"), "").trim();
        String chatTitle = defaultIfBlank(ctx.formParam("chat_title"), "").trim();
        String chatUsername = defaultIfBlank(ctx.formParam("chat_username"), "").trim();

        if (telegramSecretService.approveChat(chatId, chatType, chatTitle, chatUsername)) {
            if (sourceAuthChannels) {
                ctx.redirect("/auth_channels");
            } else {
                renderLandingForPage(ctx, "telegram", "Approved Telegram chat " + abbreviateId(chatId) + ".", false, "");
            }
            return;
        }

        log.warn("Failed Telegram chat approval from {} for chatId={}", ctx.ip(), chatId);
        if (sourceAuthChannels) {
            ctx.redirect("/auth_channels");
            return;
        }
        renderLandingForPage(ctx, "telegram", "Failed to approve Telegram chat.", true, "");
    }

    public void handleTelegramUnapprove(Context ctx) {
        if (!telegramEnabled) {
            ctx.status(404);
            return;
        }

        if (passwordProtectionEnabled && !hasValidSession(ctx)) {
            showLogin(ctx, false);
            return;
        }

        String chatId = defaultIfBlank(ctx.formParam("chat_id"), "").trim();
        if (chatId.isEmpty()) {
            renderLandingForPage(ctx, "telegram", "Missing Telegram chat ID.", true, "");
            return;
        }

        String confirm = defaultIfBlank(ctx.formParam("confirm"), "").trim();
        if (!"yes".equalsIgnoreCase(confirm)) {
            renderLandingForPage(
                    ctx,
                    "telegram",
                    "Please confirm to unapprove chat " + abbreviateId(chatId) + ".",
                    false,
                    "",
                    "",
                    false,
                    null,
                    new TelegramConfirmData("unapprove", chatId)
            );
            return;
        }

        if (telegramSecretService.unapproveChatId(chatId)) {
            renderLandingForPage(ctx, "telegram", "Unapproved Telegram chat " + abbreviateId(chatId) + ".", false, "");
            return;
        }

        log.warn("Failed Telegram chat unapproval from {} for chatId={}", ctx.ip(), chatId);
        renderLandingForPage(ctx, "telegram", "Failed to unapprove Telegram chat.", true, "");
    }

    public void handleTelegramReset(Context ctx) {
        if (!telegramEnabled) {
            ctx.status(404);
            return;
        }

        if (passwordProtectionEnabled && !hasValidSession(ctx)) {
            showLogin(ctx, false);
            return;
        }

        String confirm = defaultIfBlank(ctx.formParam("confirm"), "").trim();
        if (!"yes".equalsIgnoreCase(confirm)) {
            renderLandingForPage(
                    ctx,
                    "telegram",
                    "Please confirm reset of all approved Telegram chats.",
                    false,
                    "",
                    "",
                    false,
                    null,
                    new TelegramConfirmData("reset", "")
            );
            return;
        }

        if (telegramSecretService.resetApprovedChatIds()) {
            renderLandingForPage(ctx, "telegram", "Reset persisted approved Telegram chats.", false, "");
            return;
        }

        log.warn("Failed Telegram approved-chat reset from {}", ctx.ip());
        renderLandingForPage(ctx, "telegram", "Failed to reset approved Telegram chats.", true, "");
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

    // ── Internal: session management ───────────────────────────────────────

    private void showLogin(Context ctx, boolean invalidPassword) {
        ctx.status(invalidPassword ? 401 : 200);
        ctx.contentType("text/html; charset=UTF-8");
        ctx.result(landingPageService.renderLogin(invalidPassword));
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

    // ── Internal: render landing page ──────────────────────────────────────

    private void renderLandingForPage(Context ctx, String activePage) {
        renderLandingForPage(ctx, activePage, "", false, "", "", false, null, null);
    }

    private void renderLandingForPage(
            Context ctx,
            String activePage,
            String telegramNotice,
            boolean telegramNoticeError,
            String telegramDraft
    ) {
        renderLandingForPage(ctx, activePage, telegramNotice, telegramNoticeError, telegramDraft, "", false, null, null);
    }

    private void renderLandingForPage(
            Context ctx,
            String activePage,
            String telegramNotice,
            boolean telegramNoticeError,
            String telegramDraft,
            String queueNotice,
            boolean queueNoticeError,
            QueueConfirmData queueConfirmData
    ) {
        renderLandingForPage(
                ctx,
                activePage,
                telegramNotice,
                telegramNoticeError,
                telegramDraft,
                queueNotice,
                queueNoticeError,
                queueConfirmData,
                null
        );
    }

    private void renderLandingForPage(
            Context ctx,
            String activePage,
            String telegramNotice,
            boolean telegramNoticeError,
            String telegramDraft,
            String queueNotice,
            boolean queueNoticeError,
            QueueConfirmData queueConfirmData,
            TelegramConfirmData telegramConfirmData
    ) {
        if (!passwordProtectionEnabled || hasValidSession(ctx)) {
            TelegramPageData telegramPageData = loadTelegramPageData();
            TablePageData queuePageData = loadQueuePageData(ctx);
            queuePageData = applyQueueUiState(queuePageData, queueNotice, queueNoticeError, queueConfirmData);
            TablePageData auditPageData = loadAuditPageData(ctx);
            TablePageData logQueuePageData = loadLogQueuePageData(ctx);

            boolean telegramConfirmRequired = false;
            String telegramConfirmMode = "";
            String telegramConfirmChatId = "";
            String telegramConfirmChatIdShort = "-";
            String telegramConfirmActionPath = "";

            if (telegramConfirmData != null) {
                String confirmMode = "reset".equalsIgnoreCase(telegramConfirmData.mode()) ? "reset" : "unapprove";
                telegramConfirmRequired = true;
                telegramConfirmMode = confirmMode;
                telegramConfirmChatId = defaultIfBlank(telegramConfirmData.chatId(), "").trim();
                telegramConfirmChatIdShort = "reset".equals(confirmMode) ? "-" : abbreviateId(telegramConfirmChatId);
                telegramConfirmActionPath = "reset".equals(confirmMode) ? "/telegram/reset" : "/telegram/unapprove";
            }

            ctx.contentType("text/html; charset=UTF-8");
            ctx.result(landingPageService.renderLanding(
                    passwordProtectionEnabled,
                    activePage,
                    telegramNotice,
                    telegramNoticeError,
                    telegramDraft,
                    telegramPageData.chatRequests(),
                    telegramPageData.approvedChats(),
                    telegramConfirmRequired,
                    telegramConfirmMode,
                    telegramConfirmChatId,
                    telegramConfirmChatIdShort,
                    telegramConfirmActionPath,
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

    // ── Internal: data loading ─────────────────────────────────────────────

    private TelegramPageData loadTelegramPageData() {
        if (!telegramEnabled) {
            return new TelegramPageData(List.of(), List.of());
        }

        List<TelegramService.ChatRequest> discoveredRequests = telegramService.discoverChatRequests();
        if (!telegramSecretService.rememberDiscoveredChats(discoveredRequests)) {
            log.warn("Failed to persist discovered Telegram chat metadata");
        }

        TelegramSecretService.TelegramSecret secret = telegramSecretService.readSecret();
        Map<String, TelegramSecretService.ChatMeta> metadataByChatId = secret.chatMetaById();

        List<String> approvedChatIds = TelegramSecretService.mergeChatIds(configuredTelegramChatIds, secret.chatIds());
        java.util.Set<String> approvedSet = new HashSet<>(approvedChatIds);

        Map<String, TelegramService.ChatRequest> discoveredByChatId = new LinkedHashMap<>();
        List<Map<String, String>> requestRows = new ArrayList<>();
        for (TelegramService.ChatRequest request : discoveredRequests) {
            if (request == null || request.chatId() == null || request.chatId().isBlank()) {
                continue;
            }

            String chatId = request.chatId().trim();
            discoveredByChatId.put(chatId, request);

            if (approvedSet.contains(chatId)) {
                continue;
            }

            Map<String, String> row = new LinkedHashMap<>();
            row.put("chatId", chatId);
            row.put("chatType", firstNonBlank(request.chatType(), "unknown"));
            row.put("chatTitle", firstNonBlank(request.chatTitle(), "(no title)"));
            row.put("chatUsername", firstNonBlank(request.chatUsername(), ""));
            requestRows.add(Map.copyOf(row));
        }

        List<Map<String, String>> approvedRows = new ArrayList<>();
        for (String chatId : approvedChatIds) {
            TelegramSecretService.ChatMeta persisted = metadataByChatId.get(chatId);
            TelegramService.ChatRequest discovered = discoveredByChatId.get(chatId);

            String chatType = firstNonBlank(
                    persisted == null ? null : persisted.chatType(),
                    discovered == null ? null : discovered.chatType(),
                    "unknown"
            );
            String chatTitle = firstNonBlank(
                    persisted == null ? null : persisted.chatTitle(),
                    discovered == null ? null : discovered.chatTitle(),
                    "(no title)"
            );
            String chatUsername = firstNonBlank(
                    persisted == null ? null : persisted.chatUsername(),
                    discovered == null ? null : discovered.chatUsername(),
                    ""
            );
            String displayName = firstNonBlank(
                    persisted == null ? null : persisted.displayName(),
                    discovered == null
                            ? null
                            : firstNonBlank(
                            discovered.chatTitle(),
                            discovered.chatUsername() == null || discovered.chatUsername().isBlank()
                                    ? ""
                                    : "@" + discovered.chatUsername().trim()
                    ),
                    chatUsername.isEmpty() ? "" : "@" + chatUsername,
                    chatId
            );

            Map<String, String> row = new LinkedHashMap<>();
            row.put("chatId", chatId);
            row.put("chatType", chatType);
            row.put("chatTitle", chatTitle);
            row.put("chatUsername", chatUsername);
            row.put("chatDisplayName", displayName);
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

    private Map<String, Object> buildAuthChannelsModel() {
        List<TelegramService.ChatRequest> discoveredRequests = List.of();
        TelegramSecretService.TelegramSecret secret = null;

        if (telegramEnabled) {
            discoveredRequests = telegramService.discoverChatRequests();
            if (!telegramSecretService.rememberDiscoveredChats(discoveredRequests)) {
                log.warn("Failed to persist discovered Telegram chat metadata for auth_channels page");
            }
            secret = telegramSecretService.readSecret();
        }

        return mapper.buildAuthChannelsModel(discoveredRequests, secret, configuredTelegramChatIds, telegramEnabled);
    }

    private TablePageData loadQueuePageData(Context ctx) {
        if (authQueueStore == null) {
            return emptyPageData("expires_at", "asc");
        }

        String sortBy = defaultIfBlank(ctx.queryParam("queue_sort"), "expires_at");
        String sortDir = defaultIfBlank(ctx.queryParam("queue_dir"), "asc");
        int page = parsePositiveInt(ctx.queryParam("queue_page"), 1);
        int pageSize = parsePositiveInt(ctx.queryParam("queue_page_size"), 25);

        PageResult<ApprovalRequestRow> result =
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

        PageResult<ApprovalRequestRow> result =
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

        LogQueueFilterOptions options = authQueueStore.loadNonPendingFilterOptions();

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

    private TablePageData mapApprovalPageData(PageResult<ApprovalRequestRow> result) {
        List<String> requestIds = new ArrayList<>();
        for (ApprovalRequestRow row : result.rows()) {
            if (row.id() != null && !row.id().isBlank()) {
                requestIds.add(row.id());
            }
        }

        Map<String, RequestDependencies> dependenciesByRequestId = authQueueStore.loadRequestDependencies(requestIds);
        return mapper.mapApprovalPageData(result, dependenciesByRequestId);
    }

    private TablePageData loadAuditPageData(Context ctx) {
        if (authQueueStore == null) {
            return emptyPageData("created_at", "desc");
        }

        String sortBy = defaultIfBlank(ctx.queryParam("audit_sort"), "created_at");
        String sortDir = defaultIfBlank(ctx.queryParam("audit_dir"), "desc");
        int page = parsePositiveInt(ctx.queryParam("audit_page"), 1);
        int pageSize = parsePositiveInt(ctx.queryParam("audit_page_size"), 25);

        PageResult<StateTransitionRow> result =
                authQueueStore.pageStateTransitions(sortBy, sortDir, page, pageSize);

        return mapper.mapAuditPageData(result);
    }

    private static TablePageData applyQueueUiState(
            TablePageData source,
            String queueNotice,
            boolean queueNoticeError,
            QueueConfirmData queueConfirmData
    ) {
        Map<String, Object> pageMeta = new LinkedHashMap<>(source.pageMeta());
        pageMeta.put("queueNotice", queueNotice == null ? "" : queueNotice);
        pageMeta.put("queueNoticeError", queueNoticeError);

        if (queueConfirmData == null) {
            pageMeta.put("queueConfirmRequired", false);
            pageMeta.put("queueConfirmDecision", "");
            pageMeta.put("queueConfirmRequestId", "");
            pageMeta.put("queueConfirmRequestIdShort", "");
            pageMeta.put("queueConfirmActionPath", "");
        } else {
            String decision = "deny".equalsIgnoreCase(queueConfirmData.decision()) ? "deny" : "approve";
            pageMeta.put("queueConfirmRequired", true);
            pageMeta.put("queueConfirmDecision", decision);
            pageMeta.put("queueConfirmRequestId", queueConfirmData.requestId());
            pageMeta.put("queueConfirmRequestIdShort", abbreviateId(queueConfirmData.requestId()));
            pageMeta.put("queueConfirmActionPath", "deny".equals(decision) ? "/queue/deny" : "/queue/approve");
        }

        return new TablePageData(source.rows(), Map.copyOf(pageMeta));
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

    // ── Internal: queue decision logic ─────────────────────────────────────

    private void handleQueueDecision(Context ctx, String decision) {
        if (authQueueStore == null) {
            ctx.status(404);
            return;
        }

        if (passwordProtectionEnabled && !hasValidSession(ctx)) {
            showLogin(ctx, false);
            return;
        }

        String normalizedDecision = "deny".equalsIgnoreCase(decision) ? "deny" : "approve";
        String requestId = defaultIfBlank(ctx.formParam("request_id"), "").trim();
        if (requestId.isEmpty()) {
            renderLandingForPage(ctx, "queue", "", false, "", "Missing approval request id.", true, null);
            return;
        }

        String confirm = defaultIfBlank(ctx.formParam("confirm"), "").trim();
        if (!"yes".equalsIgnoreCase(confirm)) {
            String actionLabel = "deny".equals(normalizedDecision) ? "deny" : "approve";
            renderLandingForPage(
                    ctx,
                    "queue",
                    "",
                    false,
                    "",
                    "Please confirm to " + actionLabel + " request " + abbreviateId(requestId) + ".",
                    false,
                    new QueueConfirmData(actionLabel, requestId)
            );
            return;
        }

        applyQueueDecision(ctx, requestId, normalizedDecision);
    }

    private void applyQueueDecision(Context ctx, String requestId, String decision) {
        ApprovalRequestRow requestRow = authQueueStore.findApprovalRequestById(requestId);
        if (requestRow == null || !isVoteableState(requestRow.state())) {
            renderLandingForPage(ctx, "queue", "", false, "", "Request not found or already resolved.", true, null);
            return;
        }

        String channelId;
        try {
            channelId = ensureWebUiChannelId();
        } catch (RuntimeException e) {
            log.warn("Failed to resolve web-ui approval channel for request {}: {}", requestId, e.getMessage());
            renderLandingForPage(ctx, "queue", "", false, "", "Failed to resolve web-ui approval channel.", true, null);
            return;
        }

        List<VoteDetail> existingVotes = authQueueStore.listVotesForRequest(requestId);
        boolean alreadyVoted = existingVotes.stream()
                .anyMatch(vote -> vote.channelId() != null && vote.channelId().equalsIgnoreCase(channelId));
        if (alreadyVoted) {
            renderLandingForPage(ctx, "queue", "", false, "", "This web-ui session already voted on this request.", true, null);
            return;
        }

        Instant now = Instant.now();

        try {
            authQueueStore.insertVote(new VoteDetail(
                    0L,
                    requestId,
                    channelId,
                    decision,
                    null,
                    WEB_UI_CHANNEL_ID,
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
                reasonText = "Denied by web-ui approval vote";
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
                        "web_ui",
                        WEB_UI_CHANNEL_ID,
                        reasonCode,
                        now
                ));
            }
        } catch (RuntimeException e) {
            log.warn("Queue decision {} failed from {} for requestId={}: {}", decision, ctx.ip(), requestId, e.getMessage());
            renderLandingForPage(ctx, "queue", "", false, "", "Failed to persist queue decision.", true, null);
            return;
        }

        String message = "deny".equals(decision)
                ? "Deny vote recorded for request " + abbreviateId(requestId) + "."
                : "Approval vote recorded for request " + abbreviateId(requestId) + ".";

        renderLandingForPage(ctx, "queue", "", false, "", message, false, null);
    }

    private static boolean isVoteableState(String state) {
        if (state == null || state.isBlank()) {
            return false;
        }

        String normalized = state.trim().toUpperCase(Locale.ROOT);
        return "QUEUED".equals(normalized) || "PENDING".equals(normalized);
    }

    private String ensureWebUiChannelId() {
        ApprovalChannelRow existing = authQueueStore.findChannelById(WEB_UI_CHANNEL_ID);
        if (existing != null) {
            return existing.id();
        }

        try {
            authQueueStore.insertChannel(new ApprovalChannelRow(
                    WEB_UI_CHANNEL_ID,
                    "web_ui",
                    "Web UI",
                    true,
                    "landing-web-ui",
                    Instant.now()
            ));
        } catch (RuntimeException ignored) {
            // race-safe: reload below
        }

        ApprovalChannelRow reloaded = authQueueStore.findChannelById(WEB_UI_CHANNEL_ID);
        if (reloaded == null) {
            throw new IllegalStateException("Failed to resolve approval channel for web-ui");
        }
        return reloaded.id();
    }

    // ── Inner records ──────────────────────────────────────────────────────

    private record TelegramPageData(List<Map<String, String>> chatRequests, List<Map<String, String>> approvedChats) {
    }

    private record QueueConfirmData(String decision, String requestId) {
    }

    private record TelegramConfirmData(String mode, String chatId) {
    }
}
