package io.konkin.web.controller;

import io.javalin.http.Context;
import io.javalin.http.Cookie;
import io.javalin.http.SameSite;
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
import java.util.List;
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
    private static final DateTimeFormatter TS_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);

    private final LandingPageService landingPageService;
    private final boolean passwordProtectionEnabled;
    private final PasswordFileManager passwordFileManager;
    private final boolean telegramEnabled;
    private final List<String> configuredTelegramChatIds;
    private final TelegramService telegramService;
    private final TelegramSecretService telegramSecretService;
    private final AuthQueueStore authQueueStore;

    private final Map<String, Instant> activeSessions = new ConcurrentHashMap<>();

    public LandingPageController(
            LandingPageService landingPageService,
            boolean passwordProtectionEnabled,
            PasswordFileManager passwordFileManager,
            boolean telegramEnabled,
            List<String> configuredTelegramChatIds,
            TelegramService telegramService,
            TelegramSecretService telegramSecretService,
            AuthQueueStore authQueueStore
    ) {
        if (passwordProtectionEnabled && passwordFileManager == null) {
            throw new IllegalArgumentException("passwordFileManager is required when password protection is enabled");
        }

        if (telegramEnabled && (telegramService == null || telegramSecretService == null)) {
            throw new IllegalArgumentException("telegramService and telegramSecretService are required when telegram is enabled");
        }

        this.landingPageService = landingPageService;
        this.passwordProtectionEnabled = passwordProtectionEnabled;
        this.passwordFileManager = passwordFileManager;
        this.telegramEnabled = telegramEnabled;
        this.configuredTelegramChatIds = configuredTelegramChatIds == null ? List.of() : List.copyOf(configuredTelegramChatIds);
        this.telegramService = telegramService;
        this.telegramSecretService = telegramSecretService;
        this.authQueueStore = authQueueStore;
    }

    public void handleRoot(Context ctx) {
        renderLandingForPage(ctx, "queue");
    }

    public void handleLog(Context ctx) {
        renderLandingForPage(ctx, "log");
    }

    public void handleTelegramPage(Context ctx) {
        if (!telegramEnabled) {
            ctx.status(404);
            return;
        }

        renderLandingForPage(ctx, "telegram");
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
                    auditPageData.pageMeta()));
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
            return emptyPageData("requested_at", "desc");
        }

        String sortBy = defaultIfBlank(ctx.queryParam("queue_sort"), "requested_at");
        String sortDir = defaultIfBlank(ctx.queryParam("queue_dir"), "desc");
        int page = parsePositiveInt(ctx.queryParam("queue_page"), 1);
        int pageSize = parsePositiveInt(ctx.queryParam("queue_page_size"), 25);

        AuthQueueStore.PageResult<AuthQueueStore.ApprovalRequestRow> result =
                authQueueStore.pageApprovalRequests(sortBy, sortDir, page, pageSize);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (AuthQueueStore.ApprovalRequestRow row : result.rows()) {
            Map<String, Object> mapped = new LinkedHashMap<>();
            mapped.put("id", row.id());
            mapped.put("coin", row.coin());
            mapped.put("toolName", row.toolName());
            mapped.put("requestSessionId", row.requestSessionId() == null ? "-" : row.requestSessionId());
            mapped.put("nonceComposite", row.nonceComposite());
            mapped.put("requestedAt", formatInstant(row.requestedAt()));
            mapped.put("expiresAt", formatInstant(row.expiresAt()));
            mapped.put("state", row.state());
            mapped.put("minApprovalsRequired", row.minApprovalsRequired());
            mapped.put("approvalsGranted", row.approvalsGranted());
            mapped.put("approvalsDenied", row.approvalsDenied());
            mapped.put("updatedAt", formatInstant(row.updatedAt()));
            rows.add(Map.copyOf(mapped));
        }

        return new TablePageData(List.copyOf(rows), pageMetaFrom(result));
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

    private static String formatInstant(Instant instant) {
        return instant == null ? "-" : TS_FORMAT.format(instant);
    }

    private record TelegramPageData(List<Map<String, String>> chatRequests, List<Map<String, String>> approvedChats) {
    }

    private record TablePageData(List<Map<String, Object>> rows, Map<String, Object> pageMeta) {
    }
}
