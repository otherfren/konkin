package io.konkin.web.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.javalin.http.Context;
import io.javalin.http.Cookie;
import io.javalin.http.SameSite;
import io.konkin.config.KonkinConfig;
import io.konkin.db.AuthQueueStore;
import io.konkin.db.entity.ApprovalChannelRow;
import io.konkin.db.entity.ApprovalRequestRow;
import io.konkin.db.entity.ExecutionAttemptDetail;
import io.konkin.db.entity.LogQueueFilterOptions;
import io.konkin.db.entity.PageResult;
import io.konkin.db.entity.RequestChannelDetail;
import io.konkin.db.entity.RequestDependencies;
import io.konkin.db.entity.StateTransitionDetail;
import io.konkin.db.entity.StateTransitionRow;
import io.konkin.db.entity.VoteDetail;
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
import java.util.Objects;
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
        ctx.result(toPrettyJson(buildDetailsObject(row, dependencies)));
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
                buildWalletsModel()
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
                buildDriverAgentModel()
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

    public void handleQueueApprove(Context ctx) {
        handleQueueDecision(ctx, "approve");
    }

    public void handleQueueDeny(Context ctx) {
        handleQueueDecision(ctx, "deny");
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

    private void showLogin(Context ctx, boolean invalidPassword) {
        ctx.status(invalidPassword ? 401 : 200);
        ctx.contentType("text/html; charset=UTF-8");
        ctx.result(landingPageService.renderLogin(invalidPassword));
    }

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
        Set<String> approvedSet = new HashSet<>(approvedChatIds);

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

        PageResult<ApprovalRequestRow> result =
                authQueueStore.pagePendingApprovalRequests(sortBy, sortDir, page, pageSize);

        return mapApprovalPageData(result);
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

    private static Map<String, Object> pageMetaFrom(PageResult<?> result) {
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

    private static String firstNonBlank(String... values) {
        if (values == null || values.length == 0) {
            return "";
        }

        for (String value : values) {
            if (value != null) {
                String trimmed = value.trim();
                if (!trimmed.isEmpty()) {
                    return trimmed;
                }
            }
        }

        return "";
    }

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

    private Map<String, Object> buildAuthChannelsModel() {
        Map<String, Object> root = new LinkedHashMap<>();

        Map<String, Object> webUi = new LinkedHashMap<>();
        webUi.put("enabled", config.landingEnabled());
        webUi.put("passwordProtectionEnabled", config.landingPasswordProtectionEnabled());
        webUi.put("passwordFile", safe(config.landingPasswordFile()));
        root.put("webUi", Map.copyOf(webUi));

        Map<String, Object> restApi = new LinkedHashMap<>();
        boolean restApiEnabled = config.restApiEnabled();
        restApi.put("enabled", restApiEnabled);
        restApi.put("healthPath", "/api/v1/health");
        restApi.put("apiKeyHeader", "X-API-Key");
        restApi.put("protectedScope", "/api/v1/* (except /api/v1/health)");
        restApi.put("apiKeyProtectionEnabled", restApiEnabled);
        restApi.put("secretFile", restApiEnabled ? safe(config.restApiSecretFile()) : "-");
        root.put("restApi", Map.copyOf(restApi));

        root.put("telegramEnabled", telegramEnabled);
        root.put("telegramUsers", buildTelegramChannelUsers());
        root.put("authAgents", buildAuthAgentChannels());

        return Map.copyOf(root);
    }

    private Map<String, Object> buildDriverAgentModel() {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> driverAgent = buildDriverAgentChannel();
        root.put("driverAgent", driverAgent);
        root.put("authMethod", buildDriverAgentAuthMethod(driverAgent));
        root.put("mcpRegistration", buildDriverAgentMcpRegistration(driverAgent));
        return Map.copyOf(root);
    }

    private Map<String, Object> buildDriverAgentAuthMethod(Map<String, Object> driverAgent) {
        boolean configured = Boolean.TRUE.equals(driverAgent.get("configured"));
        boolean enabled = Boolean.TRUE.equals(driverAgent.get("enabled"));

        String tokenEndpoint = driverAgent.get("oauthTokenPath") instanceof String value ? value : "-";
        String secretFile = driverAgent.get("secretFile") instanceof String value ? value : "-";

        Map<String, Object> authMethod = new LinkedHashMap<>();
        authMethod.put("configured", configured);
        authMethod.put("enabled", enabled);
        authMethod.put("method", "OAuth 2.0 Client Credentials");
        authMethod.put("clientId", "konkin-primary");
        authMethod.put("tokenEndpoint", tokenEndpoint);
        authMethod.put("authorizationHeader", "Authorization: Bearer <access_token>");
        authMethod.put("secretFile", secretFile);
        return Map.copyOf(authMethod);
    }

    private Map<String, Object> buildDriverAgentMcpRegistration(Map<String, Object> driverAgent) {
        boolean configured = Boolean.TRUE.equals(driverAgent.get("configured"));
        boolean enabled = Boolean.TRUE.equals(driverAgent.get("enabled"));

        String tokenEndpoint = driverAgent.get("oauthTokenPath") instanceof String value ? value : "-";
        String sseEndpoint = driverAgent.get("ssePath") instanceof String value ? value : "-";

        String tokenCommand = enabled && !"-".equals(tokenEndpoint)
                ? """
                curl -s -X POST \"%s\" \\
                  -d \"grant_type=client_credentials\" \\
                  -d \"client_id=konkin-primary\" \\
                  -d \"client_secret=YOUR_SECRET\"
                """.strip().formatted(tokenEndpoint)
                : "-";

        String registerCommand = enabled && !"-".equals(sseEndpoint)
                ? """
                claude mcp add --transport sse \\
                  -H \"Authorization: Bearer YOUR_BEARER_TOKEN\" \\
                  -s project \\
                  konkin \"%s\"
                """.strip().formatted(sseEndpoint)
                : "-";

        Map<String, Object> mcpRegistration = new LinkedHashMap<>();
        mcpRegistration.put("configured", configured);
        mcpRegistration.put("enabled", enabled);
        mcpRegistration.put("sseEndpoint", sseEndpoint);
        mcpRegistration.put("tokenEndpoint", tokenEndpoint);
        mcpRegistration.put("registerCommand", registerCommand);
        mcpRegistration.put("tokenCommand", tokenCommand);
        mcpRegistration.put("verifyCommand", "claude mcp list");
        mcpRegistration.put("skillPath", "documents/SKILL-driver-agent.md");
        return Map.copyOf(mcpRegistration);
    }

    private List<Map<String, Object>> buildTelegramChannelUsers() {
        if (!telegramEnabled) {
            return List.of();
        }

        List<TelegramService.ChatRequest> discoveredRequests = telegramService.discoverChatRequests();
        if (!telegramSecretService.rememberDiscoveredChats(discoveredRequests)) {
            log.warn("Failed to persist discovered Telegram chat metadata for auth_channels page");
        }

        TelegramSecretService.TelegramSecret secret = telegramSecretService.readSecret();
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

    private Map<String, Object> buildDriverAgentChannel() {
        KonkinConfig.AgentConfig driverAgent = config.primaryAgent();
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

        boolean enabled = driverAgent.enabled();
        String bind = safe(driverAgent.bind());
        int port = driverAgent.port();
        String endpointBase = enabled ? "http://" + bind + ":" + port : "-";

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("configured", true);
        row.put("name", "driver");
        row.put("type", "driver");
        row.put("enabled", enabled);
        row.put("bind", bind);
        row.put("port", port > 0 ? Integer.toString(port) : "-");
        row.put("healthPath", enabled ? endpointBase + "/health" : "-");
        row.put("oauthTokenPath", enabled ? endpointBase + "/oauth/token" : "-");
        row.put("ssePath", enabled ? endpointBase + "/sse" : "-");
        row.put("secretFile", safe(driverAgent.secretFile()));
        return Map.copyOf(row);
    }

    private List<Map<String, Object>> buildAuthAgentChannels() {
        Map<String, KonkinConfig.AgentConfig> authAgents = config.secondaryAgents();
        if (authAgents == null || authAgents.isEmpty()) {
            return List.of();
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, KonkinConfig.AgentConfig> entry : authAgents.entrySet()) {
            String agentName = safe(entry.getKey());
            KonkinConfig.AgentConfig agentConfig = entry.getValue();

            boolean enabled = agentConfig != null && agentConfig.enabled();
            String bind = agentConfig == null ? "-" : safe(agentConfig.bind());
            int port = agentConfig == null ? 0 : agentConfig.port();
            String endpointBase = enabled ? "http://" + bind + ":" + port : "-";

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", agentName);
            row.put("authChannelId", "verification-agent:" + agentName);
            row.put("enabled", enabled);
            row.put("bind", bind);
            row.put("port", port > 0 ? Integer.toString(port) : "-");
            row.put("healthPath", enabled ? endpointBase + "/health" : "-");
            row.put("oauthTokenPath", enabled ? endpointBase + "/oauth/token" : "-");
            row.put("secretFile", agentConfig == null ? "-" : safe(agentConfig.secretFile()));
            rows.add(Map.copyOf(row));
        }

        return List.copyOf(rows);
    }

    private Map<String, Object> buildWalletsModel() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("configuredAuthChannels", buildConfiguredAuthChannels());

        List<Map<String, Object>> coins = new ArrayList<>();
        coins.add(buildCoinAuthDefinition("bitcoin", config.bitcoin()));
        coins.add(buildCoinAuthDefinition("litecoin", config.litecoin()));
        coins.add(buildCoinAuthDefinition("monero", config.monero()));
        root.put("coins", List.copyOf(coins));
        return Map.copyOf(root);
    }

    private List<Map<String, Object>> buildConfiguredAuthChannels() {
        List<Map<String, Object>> channels = new ArrayList<>();

        Map<String, Object> webUi = new LinkedHashMap<>();
        webUi.put("name", "web-ui");
        webUi.put("enabled", config.landingEnabled());
        channels.add(Map.copyOf(webUi));

        if (config.restApiEnabled()) {
            Map<String, Object> restApi = new LinkedHashMap<>();
            restApi.put("name", "rest-api");
            restApi.put("enabled", true);
            channels.add(Map.copyOf(restApi));
        }

        Map<String, Object> telegram = new LinkedHashMap<>();
        telegram.put("name", "telegram");
        telegram.put("enabled", config.telegramEnabled());
        channels.add(Map.copyOf(telegram));

        Map<String, KonkinConfig.AgentConfig> authAgents = config.secondaryAgents();
        if (authAgents != null && !authAgents.isEmpty()) {
            for (Map.Entry<String, KonkinConfig.AgentConfig> entry : authAgents.entrySet()) {
                KonkinConfig.AgentConfig agentConfig = entry.getValue();
                if (agentConfig == null || !agentConfig.enabled()) {
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

        List<Map<String, Object>> verificationAgents = new ArrayList<>();
        Map<String, KonkinConfig.AgentConfig> authAgents = config.secondaryAgents();
        for (String channelName : auth.mcpAuthChannels()) {
            String safeChannelName = safe(channelName);
            KonkinConfig.AgentConfig agentConfig = authAgents.get(channelName);

            boolean enabled = agentConfig != null && agentConfig.enabled();
            String bind = agentConfig == null ? "unknown" : safe(agentConfig.bind());
            String connectUrl = (enabled && !"-".equals(bind)) ? "http://" + bind : "unknown";
            String port = (agentConfig != null && agentConfig.port() > 0) ? Integer.toString(agentConfig.port()) : "unknown";

            Map<String, Object> verificationAgent = new LinkedHashMap<>();
            verificationAgent.put("name", safeChannelName);
            verificationAgent.put("enabled", enabled);
            verificationAgent.put("connectUrl", connectUrl);
            verificationAgent.put("port", port);
            verificationAgent.put("status", enabled ? "reachable (config)" : "unknown");
            verificationAgents.add(Map.copyOf(verificationAgent));
        }

        List<String> vetoChannels = auth.vetoChannels() == null ? List.of() : auth.vetoChannels();

        coin.put("coin", coinId);
        coin.put("coinIconName", coinIconName(coinId));
        coin.put("enabled", coinConfig.enabled());
        coin.put("connectionStatus", coinConfig.enabled() ? "unknown" : "disabled");
        coin.put("lastLifeSign", "unknown");
        coin.put("daemonSecretFile", safe(coinConfig.bitcoinDaemonConfigSecretFile()));
        coin.put("walletSecretFile", safe(coinConfig.bitcoinWalletConfigSecretFile()));
        coin.put("maskedBalance", "unknown");
        coin.put("channels", Map.copyOf(channels));
        coin.put("channelWarnings", List.copyOf(warnings));
        coin.put("verificationAgents", List.copyOf(verificationAgents));
        coin.put("quorumLine", auth.minApprovalsRequired() + "-of-N");
        coin.put("vetoChannelsLine", vetoChannels.isEmpty() ? "none" : String.join(", ", vetoChannels));
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

    private record QueueConfirmData(String decision, String requestId) {
    }

    private record TelegramConfirmData(String mode, String chatId) {
    }
}
