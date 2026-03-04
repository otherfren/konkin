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
import io.konkin.config.KonkinConfig;
import io.konkin.crypto.DepositAddress;
import io.konkin.crypto.WalletSupervisor;
import io.konkin.db.ApprovalRequestRepository;
import io.konkin.db.ChannelRepository;
import io.konkin.db.HistoryRepository;
import io.konkin.db.RequestDependencyLoader;
import io.konkin.db.VoteRepository;
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
import io.konkin.web.WebUtils;
import io.konkin.web.controller.TelegramWebController.TelegramPageData;
import io.konkin.web.service.LandingPageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
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
    private TelegramWebController telegramWebController;
    private final ApprovalRequestRepository requestRepo;
    private final VoteRepository voteRepo;
    private final ChannelRepository channelRepo;
    private final HistoryRepository historyRepo;
    private final RequestDependencyLoader depLoader;
    private final KonkinConfig config;
    private final LandingPageMapper mapper;
    private final WalletSupervisor walletSupervisor;

    private final Map<String, Instant> activeSessions = new ConcurrentHashMap<>();

    public LandingPageController(
            LandingPageService landingPageService,
            boolean passwordProtectionEnabled,
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
        this(landingPageService, passwordProtectionEnabled, passwordFileManager,
                telegramEnabled, telegramWebController, requestRepo, voteRepo,
                channelRepo, historyRepo, depLoader, config, mapper, null);
    }

    public LandingPageController(
            LandingPageService landingPageService,
            boolean passwordProtectionEnabled,
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
            WalletSupervisor walletSupervisor
    ) {
        if (passwordProtectionEnabled && passwordFileManager == null) {
            throw new IllegalArgumentException("passwordFileManager is required when password protection is enabled");
        }

        if (config == null) {
            throw new IllegalArgumentException("config is required");
        }

        this.landingPageService = landingPageService;
        this.passwordProtectionEnabled = passwordProtectionEnabled;
        this.passwordFileManager = passwordFileManager;
        this.telegramEnabled = telegramEnabled;
        this.telegramWebController = telegramWebController;
        this.requestRepo = requestRepo;
        this.voteRepo = voteRepo;
        this.channelRepo = channelRepo;
        this.historyRepo = historyRepo;
        this.depLoader = depLoader;
        this.config = config;
        this.mapper = mapper;
        this.walletSupervisor = walletSupervisor;
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

        String notice = ctx.queryParam("telegram_notice");
        boolean error = "true".equalsIgnoreCase(ctx.queryParam("telegram_notice_error"));
        String draft = ctx.queryParam("telegram_draft");
        String confirmMode = ctx.queryParam("telegram_confirm_mode");
        String confirmChatId = ctx.queryParam("telegram_confirm_chat_id");

        TelegramWebController.TelegramConfirmData confirmData = null;
        if (confirmMode != null && !confirmMode.isBlank()) {
            confirmData = new TelegramWebController.TelegramConfirmData(confirmMode, confirmChatId);
        }

        renderLandingForPage(ctx, "telegram", notice, error, draft, "", false, null, confirmData);
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

    public void handleGenerateDepositAddress(Context ctx) {
        if (passwordProtectionEnabled && !hasValidSession(ctx)) {
            showLogin(ctx, false);
            return;
        }

        String coinId = defaultIfBlank(ctx.formParam("coin"), "").trim().toLowerCase(Locale.ROOT);
        if (coinId.isEmpty()) {
            ctx.status(400);
            ctx.contentType("text/plain; charset=UTF-8");
            ctx.result("Missing required form parameter: coin");
            return;
        }

        if (walletSupervisor == null) {
            log.warn("Generate deposit address requested but no wallet supervisor available");
            ctx.redirect("/wallets");
            return;
        }

        try {
            DepositAddress depositAddress = walletSupervisor.execute(wallet -> wallet.depositAddress());
            String address = depositAddress.address();

            mapper.persistDepositAddress(coinId, address);
            log.info("Generated new {} deposit address and persisted to KvStore", coinId);
        } catch (Exception e) {
            log.warn("Failed to generate deposit address for {}: {}", coinId, e.getMessage());
        }

        ctx.redirect("/wallets");
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

    // ── Telegram handlers (delegate to TelegramWebController) ──────────────

    public Map<String, Instant> activeSessions() {
        return activeSessions;
    }

    private void showLogin(Context ctx, boolean invalidPassword) {
        ctx.status(invalidPassword ? 401 : 200);
        ctx.contentType("text/html; charset=UTF-8");
        ctx.result(landingPageService.renderLogin(invalidPassword));
    }

    private boolean hasValidSession(Context ctx) {
        return WebUtils.hasValidSession(ctx, activeSessions);
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
        if (requestRepo == null) {
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
            String coin = "";
            String amountNative = "";
            String toAddress = "";
            String toolName = "";
            if (requestRepo != null) {
                ApprovalRequestRow row = requestRepo.findApprovalRequestById(requestId);
                if (row != null) {
                    coin = row.coin() != null ? row.coin() : "";
                    amountNative = row.amountNative() != null ? row.amountNative() : "";
                    toAddress = row.toAddress() != null ? row.toAddress() : "";
                    toolName = row.toolName() != null ? row.toolName() : "";
                }
            }
            renderLandingForPage(
                    ctx, "queue", "", false, "",
                    "Please confirm to " + actionLabel + " request " + abbreviateId(requestId) + ".",
                    false,
                    new QueueConfirmData(actionLabel, requestId, coin, amountNative, toAddress, toolName)
            );
            return;
        }

        applyQueueDecision(ctx, requestId, normalizedDecision);
    }

    private void applyQueueDecision(Context ctx, String requestId, String decision) {
        ApprovalRequestRow requestRow = requestRepo.findApprovalRequestById(requestId);
        if (requestRow == null || !isVoteableState(requestRow.state())) {
            renderLandingForPage(ctx, "queue", "", false, "", "Request not found or already resolved.", true, null);
            return;
        }

        if (requestRow.expiresAt() != null && requestRow.expiresAt().isBefore(Instant.now())) {
            renderLandingForPage(ctx, "queue", "", false, "", "Request has expired and can no longer be voted on.", true, null);
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

        List<VoteDetail> existingVotes = voteRepo.listVotesForRequest(requestId);
        boolean alreadyVoted = existingVotes.stream()
                .anyMatch(vote -> vote.channelId() != null && vote.channelId().equalsIgnoreCase(channelId));
        if (alreadyVoted) {
            renderLandingForPage(ctx, "queue", "", false, "", "This web-ui session already voted on this request.", true, null);
            return;
        }

        Instant now = Instant.now();

        try {
            voteRepo.insertVote(new VoteDetail(
                    0L, requestId, channelId, decision, null, WEB_UI_CHANNEL_ID, now
            ));

            List<VoteDetail> votes = voteRepo.listVotesForRequest(requestId);
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
            requestRepo.updateApprovalRequest(updated);

            if (!Objects.equals(previousState, nextState)) {
                historyRepo.insertStateTransition(new StateTransitionRow(
                        0L, requestId, previousState, nextState,
                        "web_ui", WEB_UI_CHANNEL_ID, reasonCode, now
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

    // ── Inner records ──────────────────────────────────────────────────────

    private record QueueConfirmData(String decision, String requestId,
                                        String coin, String amountNative, String toAddress, String toolName) {
    }
}