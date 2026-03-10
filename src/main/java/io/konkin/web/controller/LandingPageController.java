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

package io.konkin.web.controller;

import io.javalin.http.Context;
import io.javalin.http.Cookie;
import io.javalin.http.SameSite;
import io.konkin.config.CoinConfig;
import io.konkin.config.ConfigManager;
import io.konkin.config.KonkinConfig;
import io.konkin.crypto.Coin;
import io.konkin.crypto.WalletSupervisor;
import io.konkin.db.ApprovalRequestRepository;
import io.konkin.db.ChannelRepository;
import io.konkin.db.HistoryRepository;
import io.konkin.db.RequestDependencyLoader;
import io.konkin.db.VoteRepository;
import io.konkin.db.VoteService;
import io.konkin.db.entity.ApprovalChannelRow;
import io.konkin.db.entity.ApprovalRequestRow;
import io.konkin.db.entity.ExecutionAttemptDetail;
import io.konkin.db.entity.LogQueueFilterOptions;
import io.konkin.db.entity.PageResult;
import io.konkin.db.entity.RequestDependencies;
import io.konkin.db.entity.StateTransitionRow;
import io.konkin.db.entity.VoteDetail;
import io.konkin.security.PasswordFileManager;
import io.konkin.web.LandingPageMapper;
import io.konkin.web.LandingPageMapper.TablePageData;
import io.konkin.web.WebUtils;
import io.konkin.telegram.TelegramWebController;
import io.konkin.telegram.TelegramWebController.TelegramPageData;
import io.konkin.web.service.LandingPageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

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

    // [M-6] Rate limiting for web UI login — same approach as AgentOAuthHandler
    private static final int MAX_FAILED_LOGIN_ATTEMPTS = 5;
    private static final long FAILED_LOGIN_WINDOW_SECONDS = 60;
    private final Deque<Instant> failedLoginAttempts = new ArrayDeque<>();

    private final LandingPageService landingPageService;
    private final boolean passwordProtectionEnabled;
    private final Path passwordFilePath;
    private volatile PasswordFileManager passwordFileManager;
    private final boolean telegramEnabled;
    private TelegramWebController telegramWebController;
    private final ApprovalRequestRepository requestRepo;
    private final VoteRepository voteRepo;
    private final ChannelRepository channelRepo;
    private final HistoryRepository historyRepo;
    private final RequestDependencyLoader depLoader;
    private final ConfigManager configManager;
    private final LandingPageMapper mapper;
    private final Map<Coin, WalletSupervisor> walletSupervisors;
    private final VoteService voteService;
    private final Path restApiSecretFilePath;
    private final AtomicReference<String> activeApiKey;

    private final Map<String, Instant> activeSessions = new ConcurrentHashMap<>();

    /** Convenience constructor — wraps config in a ConfigManager. */
    public LandingPageController(
            LandingPageService landingPageService,
            boolean passwordProtectionEnabled,
            Path passwordFilePath,
            PasswordFileManager passwordFileManager,
            boolean telegramEnabled,
            TelegramWebController telegramWebController,
            ApprovalRequestRepository requestRepo,
            VoteRepository voteRepo,
            ChannelRepository channelRepo,
            HistoryRepository historyRepo,
            RequestDependencyLoader depLoader,
            KonkinConfig config,
            LandingPageMapper mapper
    ) {
        this(landingPageService, passwordProtectionEnabled, passwordFilePath, passwordFileManager,
                telegramEnabled, telegramWebController, requestRepo, voteRepo,
                channelRepo, historyRepo, depLoader, new ConfigManager(config), mapper, null, null,
                null, null);
    }

    /** Convenience constructor — wraps config in a ConfigManager. */
    public LandingPageController(
            LandingPageService landingPageService,
            boolean passwordProtectionEnabled,
            Path passwordFilePath,
            PasswordFileManager passwordFileManager,
            boolean telegramEnabled,
            TelegramWebController telegramWebController,
            ApprovalRequestRepository requestRepo,
            VoteRepository voteRepo,
            ChannelRepository channelRepo,
            HistoryRepository historyRepo,
            RequestDependencyLoader depLoader,
            KonkinConfig config,
            LandingPageMapper mapper,
            Map<Coin, WalletSupervisor> walletSupervisors,
            VoteService voteService
    ) {
        this(landingPageService, passwordProtectionEnabled, passwordFilePath, passwordFileManager,
                telegramEnabled, telegramWebController, requestRepo, voteRepo,
                channelRepo, historyRepo, depLoader, new ConfigManager(config), mapper, walletSupervisors, voteService,
                null, null);
    }

    public LandingPageController(
            LandingPageService landingPageService,
            boolean passwordProtectionEnabled,
            Path passwordFilePath,
            PasswordFileManager passwordFileManager,
            boolean telegramEnabled,
            TelegramWebController telegramWebController,
            ApprovalRequestRepository requestRepo,
            VoteRepository voteRepo,
            ChannelRepository channelRepo,
            HistoryRepository historyRepo,
            RequestDependencyLoader depLoader,
            ConfigManager configManager,
            LandingPageMapper mapper,
            Map<Coin, WalletSupervisor> walletSupervisors,
            VoteService voteService,
            Path restApiSecretFilePath,
            AtomicReference<String> activeApiKey
    ) {
        if (configManager == null) {
            throw new IllegalArgumentException("configManager is required");
        }

        this.landingPageService = landingPageService;
        this.passwordProtectionEnabled = passwordProtectionEnabled;
        this.passwordFilePath = passwordFilePath;
        this.passwordFileManager = passwordFileManager;
        this.telegramEnabled = telegramEnabled;
        this.telegramWebController = telegramWebController;
        this.requestRepo = requestRepo;
        this.voteRepo = voteRepo;
        this.channelRepo = channelRepo;
        this.historyRepo = historyRepo;
        this.depLoader = depLoader;
        this.configManager = configManager;
        this.mapper = mapper;
        this.walletSupervisors = walletSupervisors != null ? walletSupervisors : Map.of();
        this.voteService = voteService;
        this.restApiSecretFilePath = restApiSecretFilePath;
        this.activeApiKey = activeApiKey != null ? activeApiKey : new AtomicReference<>();
    }

    public void setTelegramWebController(TelegramWebController telegramWebController) {
        this.telegramWebController = telegramWebController;
    }

    // ── Page handlers ──────────────────────────────────────────────────────

    public void handleRoot(Context ctx) {
        renderLandingForPage(ctx, "queue");
    }

    public void handleLog(Context ctx) {
        renderLandingForPage(ctx, "history");
    }

    public void handleDetailsPage(Context ctx) {
        if (requestRepo == null) {
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

        ApprovalRequestRow row = requestRepo.findApprovalRequestById(requestId);
        if (row == null) {
            ctx.status(404);
            ctx.contentType("text/plain; charset=UTF-8");
            ctx.result("No approval request found for id: " + requestId);
            return;
        }

        Map<String, RequestDependencies> dependenciesByRequestId =
                depLoader.loadRequestDependencies(List.of(requestId));

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
        if (passwordProtectionEnabled && !hasValidSession(ctx)) {
            showLogin(ctx, false);
            return;
        }

        String notice = ctx.queryParam("telegram_notice");
        boolean error = "true".equalsIgnoreCase(ctx.queryParam("telegram_notice_error"));
        String draft = ctx.queryParam("telegram_draft");
        String confirmMode = ctx.queryParam("telegram_confirm_mode");
        String confirmChatId = ctx.queryParam("telegram_confirm_chat_id");

        TelegramPageData telegramPageData = loadTelegramPageData();

        boolean telegramConfirmRequired = false;
        String telegramConfirmMode = "";
        String telegramConfirmChatId = "";
        String telegramConfirmChatIdShort = "-";
        String telegramConfirmActionPath = "";

        if (confirmMode != null && !confirmMode.isBlank()) {
            String mode = "reset".equalsIgnoreCase(confirmMode) ? "reset" : "unapprove";
            telegramConfirmRequired = true;
            telegramConfirmMode = mode;
            telegramConfirmChatId = defaultIfBlank(confirmChatId, "").trim();
            telegramConfirmChatIdShort = "reset".equals(mode) ? "-" : abbreviateId(telegramConfirmChatId);
            telegramConfirmActionPath = "reset".equals(mode) ? "/auth_channels/telegram/reset" : "/auth_channels/telegram/unapprove";
        }

        ctx.contentType("text/html; charset=UTF-8");
        ctx.result(landingPageService.renderTelegramPage(
                passwordProtectionEnabled,
                telegramPageData.chatRequests(),
                telegramPageData.approvedChats(),
                notice,
                error,
                draft,
                telegramConfirmRequired,
                telegramConfirmMode,
                telegramConfirmChatId,
                telegramConfirmChatIdShort,
                telegramConfirmActionPath,
                WebUtils.csrfTokenForSession(ctx),
                mapper.buildTelegramSettingsModel()
        ));
    }

    // Wallet handlers extracted to WalletController


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

    public void handleAuthChannelWebUiPage(Context ctx) {
        if (passwordProtectionEnabled && !hasValidSession(ctx)) {
            showLogin(ctx, false);
            return;
        }

        ctx.contentType("text/html; charset=UTF-8");
        ctx.result(landingPageService.renderAuthChannelWebUi(
                passwordProtectionEnabled,
                mapper.buildWebUiChannelModel(),
                "",
                mapper.buildWebUiSettingsModel()
        ));
    }

    public void handlePasswordRotate(Context ctx) {
        if (passwordProtectionEnabled && !hasValidSession(ctx)) {
            showLogin(ctx, false);
            return;
        }

        if (!passwordProtectionEnabled || passwordFilePath == null) {
            ctx.redirect("/auth_channels/web-ui");
            return;
        }

        PasswordFileManager.CreateResult result = PasswordFileManager.createNew(passwordFilePath);
        this.passwordFileManager = result.manager();
        activeSessions.clear();
        issueSessionCookie(ctx);
        log.info("Web-UI password rotated via auth channel page, file: {}", passwordFilePath.toAbsolutePath());

        ctx.contentType("text/html; charset=UTF-8");
        ctx.result(landingPageService.renderAuthChannelWebUi(
                passwordProtectionEnabled,
                mapper.buildWebUiChannelModel(),
                result.cleartextPassword(),
                mapper.buildWebUiSettingsModel()
        ));
    }

    // Driver agent handler extracted to SettingsController

    // ── Setup wizard ──────────────────────────────────────────────────────

    public boolean isWizardMode() {
        return passwordProtectionEnabled && passwordFileManager == null;
    }

    public void handleSetupPage(Context ctx) {
        if (!isWizardMode()) {
            ctx.redirect("/");
            return;
        }
        showSetup(ctx, "create", null);
    }

    public void handleSetupCreate(Context ctx) {
        if (!isWizardMode()) {
            ctx.redirect("/");
            return;
        }

        PasswordFileManager.CreateResult result = PasswordFileManager.createNew(passwordFilePath);
        this.passwordFileManager = result.manager();
        showSetup(ctx, "reveal", result.cleartextPassword());
    }

    private void showSetup(Context ctx, String step, String password) {
        ctx.contentType("text/html; charset=UTF-8");
        ctx.result(landingPageService.renderSetup(step, password));
    }

    // API key handlers extracted to SettingsController

    // ── Login / logout ─────────────────────────────────────────────────────

    public void handleLoginPage(Context ctx) {
        if (!passwordProtectionEnabled) {
            ctx.redirect("/");
            return;
        }

        if (isWizardMode()) {
            ctx.redirect("/setup");
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

        if (isWizardMode()) {
            ctx.redirect("/setup");
            return;
        }

        // [M-6] Rate limiting on web UI login endpoint
        if (isLoginRateLimited()) {
            log.warn("Rate-limited landing page login attempt from {}", ctx.ip());
            ctx.status(429);
            showLogin(ctx, true);
            return;
        }

        String password = ctx.formParam("password");
        if (password == null || password.isBlank() || !passwordFileManager.verifyPassword(password)) {
            recordFailedLogin();
            log.warn("Failed landing page login from {}", ctx.ip());
            showLogin(ctx, true);
            return;
        }

        issueSessionCookie(ctx);
        ctx.redirect("/");
    }

    public void handleLogout(Context ctx) {
        String token = ctx.cookie(SESSION_COOKIE_NAME);
        if (token != null) {
            activeSessions.remove(token);
            WebUtils.removeCsrfToken(token);
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

    // ── Telegram handlers (delegate to TelegramWebController) ──────────────

    public Map<String, Instant> activeSessions() {
        return activeSessions;
    }

    /**
     * Removes expired sessions and their associated CSRF tokens.
     * Called periodically from a background cleanup thread.
     */
    public void cleanupExpiredSessions() {
        Instant now = Instant.now();
        activeSessions.entrySet().removeIf(entry -> {
            if (entry.getValue().isBefore(now)) {
                WebUtils.removeCsrfToken(entry.getKey());
                return true;
            }
            return false;
        });
    }

    public void showLogin(Context ctx, boolean invalidPassword) {
        if (isWizardMode()) {
            ctx.redirect("/setup");
            return;
        }
        ctx.status(invalidPassword ? 401 : 200);
        ctx.contentType("text/html; charset=UTF-8");
        ctx.result(landingPageService.renderLogin(invalidPassword));
    }

    public boolean hasValidSession(Context ctx) {
        return WebUtils.hasValidSession(ctx, activeSessions, SESSION_TTL);
    }

    private String newSessionToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void issueSessionCookie(Context ctx) {
        String sessionToken = newSessionToken();
        activeSessions.put(sessionToken, Instant.now().plus(SESSION_TTL));
        WebUtils.generateCsrfToken(sessionToken);

        Cookie sessionCookie = new Cookie(SESSION_COOKIE_NAME, sessionToken);
        sessionCookie.setPath("/");
        sessionCookie.setHttpOnly(true);
        sessionCookie.setSecure(isSecureRequest(ctx));
        sessionCookie.setMaxAge((int) SESSION_TTL.toSeconds());
        sessionCookie.setSameSite(SameSite.STRICT);
        ctx.cookie(sessionCookie);
    }

    // [M-6] Rate limiting helpers — mirrors AgentOAuthHandler pattern
    private synchronized boolean isLoginRateLimited() {
        purgeExpiredLoginAttempts(Instant.now());
        return failedLoginAttempts.size() >= MAX_FAILED_LOGIN_ATTEMPTS;
    }

    private synchronized void recordFailedLogin() {
        Instant now = Instant.now();
        purgeExpiredLoginAttempts(now);
        failedLoginAttempts.addLast(now);
    }

    private synchronized void purgeExpiredLoginAttempts(Instant now) {
        Instant cutoff = now.minusSeconds(FAILED_LOGIN_WINDOW_SECONDS);
        while (!failedLoginAttempts.isEmpty() && !failedLoginAttempts.peekFirst().isAfter(cutoff)) {
            failedLoginAttempts.removeFirst();
        }
    }

    private static boolean isSecureRequest(Context ctx) {
        String forwardedProto = ctx.header("X-Forwarded-Proto");
        if (forwardedProto != null) {
            return "https".equalsIgnoreCase(forwardedProto.trim());
        }
        return "https".equalsIgnoreCase(ctx.scheme());
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
                ctx, activePage,
                telegramNotice, telegramNoticeError, telegramDraft,
                queueNotice, queueNoticeError, queueConfirmData,
                null
        );
    }

    public void renderLandingForPage(
            Context ctx,
            String activePage,
            String telegramNotice,
            boolean telegramNoticeError,
            String telegramDraft,
            String queueNotice,
            boolean queueNoticeError,
            QueueConfirmData queueConfirmData,
            TelegramWebController.TelegramConfirmData telegramConfirmData
    ) {
        if (!passwordProtectionEnabled || hasValidSession(ctx)) {
            // Only load data needed for the active page to avoid unnecessary DB queries
            TelegramPageData telegramPageData = "auth_channel_telegram".equals(activePage)
                    ? loadTelegramPageData()
                    : new TelegramPageData(List.of(), List.of());

            TablePageData queuePageData = "queue".equals(activePage)
                    ? applyQueueUiState(loadQueuePageData(ctx), queueNotice, queueNoticeError, queueConfirmData)
                    : applyQueueUiState(emptyPageData("expires_at", "asc"), queueNotice, queueNoticeError, queueConfirmData);

            TablePageData auditPageData = "history".equals(activePage)
                    ? loadAuditPageData(ctx)
                    : emptyPageData("created_at", "desc");

            TablePageData logQueuePageData = "history".equals(activePage)
                    ? loadLogQueuePageData(ctx)
                    : emptyLogQueuePageData();

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
                telegramConfirmActionPath = "reset".equals(confirmMode) ? "/auth_channels/telegram/reset" : "/auth_channels/telegram/unapprove";
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
                    logQueuePageData.pageMeta(),
                    WebUtils.csrfTokenForSession(ctx)));
            return;
        }

        showLogin(ctx, false);
    }

    // ── Internal: data loading ─────────────────────────────────────────────

    private TelegramPageData loadTelegramPageData() {
        if (!telegramEnabled) {
            return new TelegramPageData(List.of(), List.of());
        }

        return telegramWebController.loadTelegramPageData();
    }

    private Map<String, Object> buildAuthChannelsModel() {
        if (!telegramEnabled) {
            return mapper.buildAuthChannelsModel(List.of(), null, List.of(), false);
        }

        TelegramWebController.DiscoveredChats chats = telegramWebController.discoverAndPersistChats();
        return mapper.buildAuthChannelsModel(
                chats.discoveredRequests(),
                chats.secret(),
                chats.configuredTelegramChatIds(),
                true
        );
    }

    private TablePageData loadQueuePageData(Context ctx) {
        if (requestRepo == null) {
            return emptyPageData("expires_at", "asc");
        }

        String sortBy = defaultIfBlank(ctx.queryParam("queue_sort"), "expires_at");
        String sortDir = defaultIfBlank(ctx.queryParam("queue_dir"), "asc");
        int page = parsePositiveInt(ctx.queryParam("queue_page"), 1);
        int pageSize = parsePositiveInt(ctx.queryParam("queue_page_size"), 25);

        PageResult<ApprovalRequestRow> result =
                requestRepo.pagePendingApprovalRequests(sortBy, sortDir, page, pageSize);

        return mapApprovalPageData(result);
    }

    private TablePageData loadLogQueuePageData(Context ctx) {
        if (requestRepo == null) {
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
                requestRepo.pageNonPendingApprovalRequests(
                        sortBy, sortDir, page, pageSize,
                        filterCoin, filterTool, filterState, filterText
                );

        LogQueueFilterOptions options = requestRepo.loadNonPendingFilterOptions();

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

        Map<String, RequestDependencies> dependenciesByRequestId = depLoader.loadRequestDependencies(requestIds);
        return mapper.mapApprovalPageData(result, dependenciesByRequestId);
    }

    private TablePageData loadAuditPageData(Context ctx) {
        if (historyRepo == null) {
            return emptyPageData("created_at", "desc");
        }

        String sortBy = defaultIfBlank(ctx.queryParam("audit_sort"), "created_at");
        String sortDir = defaultIfBlank(ctx.queryParam("audit_dir"), "desc");
        int page = parsePositiveInt(ctx.queryParam("audit_page"), 1);
        int pageSize = parsePositiveInt(ctx.queryParam("audit_page_size"), 25);

        PageResult<StateTransitionRow> result =
                historyRepo.pageStateTransitions(sortBy, sortDir, page, pageSize);

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
            pageMeta.put("queueConfirmCoin", queueConfirmData.coin() != null ? queueConfirmData.coin() : "");
            pageMeta.put("queueConfirmAmountNative", queueConfirmData.amountNative() != null ? queueConfirmData.amountNative() : "");
            pageMeta.put("queueConfirmToAddress", queueConfirmData.toAddress() != null ? queueConfirmData.toAddress() : "");
            pageMeta.put("queueConfirmToolName", queueConfirmData.toolName() != null ? queueConfirmData.toolName() : "");
            pageMeta.put("queueConfirmReason", queueConfirmData.reason() != null ? queueConfirmData.reason() : "");
        }

        return new TablePageData(source.rows(), Map.copyOf(pageMeta));
    }

    private static TablePageData emptyLogQueuePageData() {
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
        if (requestRepo == null) {
            ctx.status(404);
            return;
        }

        if (passwordProtectionEnabled && !hasValidSession(ctx)) {
            showLogin(ctx, false);
            return;
        }

        if (passwordProtectionEnabled && !WebUtils.isValidCsrf(ctx)) {
            log.warn("CSRF validation failed for queue {} from {}", decision, ctx.ip());
            ctx.status(403);
            renderLandingForPage(ctx, "queue", "", false, "", "Invalid CSRF token. Please reload the page and try again.", true, null);
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
            String coin = "";
            String amountNative = "";
            String toAddress = "";
            String toolName = "";
            String reason = "";
            if (requestRepo != null) {
                ApprovalRequestRow row = requestRepo.findApprovalRequestById(requestId);
                if (row != null) {
                    coin = row.coin() != null ? row.coin() : "";
                    amountNative = row.amountNative() != null ? row.amountNative() : "";
                    toAddress = row.toAddress() != null ? row.toAddress() : "";
                    toolName = row.toolName() != null ? row.toolName() : "";
                    reason = row.reason() != null ? row.reason() : "";
                }
            }
            renderLandingForPage(
                    ctx, "queue", "", false, "",
                    "Please confirm to " + actionLabel + " request " + abbreviateId(requestId) + ".",
                    false,
                    new QueueConfirmData(actionLabel, requestId, coin, amountNative, toAddress, toolName, reason)
            );
            return;
        }

        applyQueueDecision(ctx, requestId, normalizedDecision);
    }

    private void applyQueueDecision(Context ctx, String requestId, String decision) {
        // Ensure the web-ui channel row exists (idempotent, outside the vote transaction)
        String channelId;
        try {
            channelId = ensureWebUiChannelId();
        } catch (RuntimeException e) {
            log.warn("Failed to resolve web-ui approval channel for request {}: {}", requestId, e.getMessage());
            renderLandingForPage(ctx, "queue", "", false, "", "Failed to resolve web-ui approval channel.", true, null);
            return;
        }

        // Resolve veto channels for this coin
        List<String> vetoChannels = List.of();
        ApprovalRequestRow requestRow = requestRepo.findApprovalRequestById(requestId);
        if (requestRow != null) {
            vetoChannels = resolveVetoChannels(requestRow.coin());

            // [M-4] Verify web-ui is an authorized auth channel for this coin
            CoinConfig coinConfig = resolveCoinConfig(requestRow.coin());
            if (coinConfig != null && coinConfig.auth() != null && !coinConfig.auth().webUi()) {
                log.warn("Web UI vote rejected: web-ui auth channel not enabled for coin={}, requestId={}", requestRow.coin(), requestId);
                renderLandingForPage(ctx, "queue", "", false, "",
                        "Web UI voting is not enabled for " + requestRow.coin() + ".", true, null);
                return;
            }
        }

        // Cast vote transactionally (locks the request row, prevents race conditions)
        VoteService.VoteResult result;
        try {
            result = voteService.castVote(
                    requestId, channelId, decision, null, WEB_UI_CHANNEL_ID,
                    "web_ui", WEB_UI_CHANNEL_ID, vetoChannels
            );
        } catch (RuntimeException e) {
            log.warn("Queue decision {} failed from {} for requestId={}: {}", decision, ctx.ip(), requestId, e.getMessage());
            renderLandingForPage(ctx, "queue", "", false, "", "Failed to persist queue decision.", true, null);
            return;
        }

        if (!result.success()) {
            String errorMessage = switch (result.error()) {
                case "already_voted" -> "This web-ui session already voted on this request.";
                case "request_expired" -> "Request has expired and can no longer be voted on.";
                default -> "Request not found or already resolved.";
            };
            renderLandingForPage(ctx, "queue", "", false, "", errorMessage, true, null);
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
        ApprovalChannelRow existing = channelRepo.findChannelById(WEB_UI_CHANNEL_ID);
        if (existing != null) {
            return existing.id();
        }

        try {
            channelRepo.insertChannel(new ApprovalChannelRow(
                    WEB_UI_CHANNEL_ID, "web_ui", "Web UI", true, "landing-web-ui", Instant.now()
            ));
        } catch (RuntimeException ignored) {
            // race-safe: reload below
        }

        ApprovalChannelRow reloaded = channelRepo.findChannelById(WEB_UI_CHANNEL_ID);
        if (reloaded == null) {
            throw new IllegalStateException("Failed to resolve approval channel for web-ui");
        }
        return reloaded.id();
    }

    private List<String> resolveVetoChannels(String coin) {
        CoinConfig coinConfig = resolveCoinConfig(coin);
        if (coinConfig == null || coinConfig.auth() == null || coinConfig.auth().vetoChannels() == null) {
            return List.of();
        }
        return coinConfig.auth().vetoChannels();
    }

    private CoinConfig resolveCoinConfig(String coin) {
        return configManager.get().resolveCoinConfig(coin);
    }

    // ── History export ──────────────────────────────────────────────────────

    private static final DateTimeFormatter CT_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    public void handleHistoryExport(Context ctx) {
        if (requestRepo == null) {
            ctx.status(404);
            return;
        }

        if (passwordProtectionEnabled && !hasValidSession(ctx)) {
            showLogin(ctx, false);
            return;
        }

        // Load COMPLETED requests (successful transactions)
        List<ApprovalRequestRow> completed = requestRepo.findByState("COMPLETED");

        // Load execution attempt details for these requests
        List<String> requestIds = new ArrayList<>();
        for (ApprovalRequestRow row : completed) {
            if (row.id() != null && !row.id().isBlank()) {
                requestIds.add(row.id());
            }
        }

        Map<String, List<ExecutionAttemptDetail>> executionsByRequest =
                requestIds.isEmpty() ? Map.of() : depLoader.loadRequestDependencies(requestIds)
                        .entrySet().stream()
                        .collect(java.util.stream.Collectors.toMap(
                                Map.Entry::getKey,
                                e -> e.getValue().executionAttempts()
                        ));

        StringBuilder csv = new StringBuilder();
        // CoinTracking CSV header
        csv.append("\"Type\",\"Buy\",\"Cur.\",\"Sell\",\"Cur.\",\"Fee\",\"Cur.\",\"Exchange\",\"Group\",\"Comment\",\"Date\",\"Txid\"\n");

        for (ApprovalRequestRow row : completed) {
            List<ExecutionAttemptDetail> attempts = executionsByRequest.getOrDefault(row.id(), List.of());

            // Find the successful execution attempt
            ExecutionAttemptDetail successAttempt = null;
            for (ExecutionAttemptDetail attempt : attempts) {
                if ("success".equalsIgnoreCase(attempt.result()) && attempt.txid() != null) {
                    successAttempt = attempt;
                    break;
                }
            }

            String txid = successAttempt != null ? successAttempt.txid() : "";
            String fee = successAttempt != null && successAttempt.daemonFeeNative() != null
                    ? successAttempt.daemonFeeNative() : "";
            String ticker = coinTicker(row.coin());

            // Date: use the execution finish time if available, otherwise resolvedAt
            Instant dateInstant = successAttempt != null && successAttempt.finishedAt() != null
                    ? successAttempt.finishedAt()
                    : row.resolvedAt() != null ? row.resolvedAt() : row.requestedAt();
            String date = dateInstant != null ? CT_DATE_FORMAT.format(dateInstant) : "";

            // Build comment from reason + memo
            String comment = buildExportComment(row);

            // Type=Withdrawal: we are sending coin out
            csv.append(csvLine(
                    "Withdrawal",       // Type
                    "",                  // Buy (empty for withdrawal)
                    "",                  // Buy Currency (empty)
                    row.amountNative(),  // Sell
                    ticker,              // Sell Currency
                    fee,                 // Fee
                    ticker,              // Fee Currency (same as coin)
                    "konkin",            // Exchange
                    "",                  // Group
                    comment,             // Comment
                    date,                // Date
                    txid                 // Txid
            ));
        }

        ctx.contentType("text/csv; charset=UTF-8");
        ctx.header("Content-Disposition",
                "attachment; filename=\"cointracking_export_" + Instant.now().toEpochMilli() + ".csv\"");
        ctx.result(csv.toString());
    }

    private static String buildExportComment(ApprovalRequestRow row) {
        StringBuilder sb = new StringBuilder();
        if (row.toAddress() != null && !row.toAddress().isBlank()) {
            sb.append("to:").append(row.toAddress());
        }
        if (row.reason() != null && !row.reason().isBlank()) {
            if (sb.length() > 0) sb.append(" | ");
            sb.append(row.reason());
        }
        if (row.memo() != null && !row.memo().isBlank()) {
            if (sb.length() > 0) sb.append(" | ");
            sb.append("memo:").append(row.memo());
        }
        return sb.toString();
    }

    private static String csvLine(String... fields) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(csvEscape(fields[i])).append('"');
        }
        sb.append('\n');
        return sb.toString();
    }

    private static String csvEscape(String value) {
        if (value == null) return "";
        return value.replace("\"", "\"\"");
    }

    private static String coinTicker(String coin) {
        if (coin == null) return "";
        return switch (coin.toLowerCase(Locale.ROOT)) {
            case "bitcoin" -> "BTC";
            case "litecoin" -> "LTC";
            case "monero" -> "XMR";
            case "ethereum" -> "ETH";
            case "solana" -> "SOL";
            case "tron" -> "TRX";
            case "pirate" -> "ARRR";
            case "zano" -> "ZANO";
            default -> coin.toUpperCase(Locale.ROOT);
        };
    }

    // ── Inner records ──────────────────────────────────────────────────────

    private record QueueConfirmData(String decision, String requestId,
                                        String coin, String amountNative, String toAddress, String toolName, String reason) {
    }
}