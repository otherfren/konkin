package io.konkin.web.service;

import com.fasterxml.jackson.databind.JsonNode;
import io.konkin.config.CoinAuthConfig;
import io.konkin.config.CoinConfig;
import io.konkin.config.KonkinConfig;
import io.konkin.db.ApprovalRequestRepository;
import io.konkin.db.HistoryRepository;
import io.konkin.db.VoteRepository;
import io.konkin.db.entity.ApprovalRequestRow;
import io.konkin.db.entity.StateTransitionRow;
import io.konkin.db.entity.VoteDetail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Background service that polls the Telegram Bot API for callback_query updates
 * (inline button presses on approval prompts) and processes approve/deny votes.
 */
public class TelegramCallbackPoller {

    private static final Logger log = LoggerFactory.getLogger(TelegramCallbackPoller.class);

    private static final String TELEGRAM_CHANNEL_ID = "telegram";
    private static final String ACTOR_TYPE = "telegram";
    private static final String ACTOR_ID = "telegram-callback";

    private final TelegramService telegramService;
    private final ApprovalRequestRepository requestRepo;
    private final VoteRepository voteRepo;
    private final HistoryRepository historyRepo;
    private final KonkinConfig config;

    private ScheduledExecutorService scheduler;
    private long nextOffset;

    public TelegramCallbackPoller(
            TelegramService telegramService,
            ApprovalRequestRepository requestRepo,
            VoteRepository voteRepo,
            HistoryRepository historyRepo,
            KonkinConfig config
    ) {
        this.telegramService = telegramService;
        this.requestRepo = requestRepo;
        this.voteRepo = voteRepo;
        this.historyRepo = historyRepo;
        this.config = config;
        this.nextOffset = 0;
    }

    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "telegram-callback-poller");
            t.setDaemon(true);
            return t;
        });

        scheduler.scheduleWithFixedDelay(this::poll, 2, 3, TimeUnit.SECONDS);

        Duration autoDenyTimeout = config.telegramAutoDenyTimeout();
        if (autoDenyTimeout != null && !autoDenyTimeout.isZero() && !autoDenyTimeout.isNegative()) {
            scheduler.scheduleAtFixedRate(this::sweepAutoDeny, 10, 10, TimeUnit.SECONDS);
            log.info("Telegram callback poller started (interval=3s, auto-deny-timeout={})", autoDenyTimeout);
        } else {
            log.info("Telegram callback poller started (interval=3s, auto-deny=disabled)");
        }
    }

    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    private void poll() {
        try {
            JsonNode updates = telegramService.getUpdates(nextOffset, 1);
            if (updates == null || !updates.isArray() || updates.isEmpty()) {
                return;
            }

            for (JsonNode update : updates) {
                long updateId = update.path("update_id").asLong(-1);
                if (updateId >= 0) {
                    nextOffset = updateId + 1;
                }

                JsonNode callbackQuery = update.path("callback_query");
                if (callbackQuery.isMissingNode() || callbackQuery.isNull()) {
                    continue;
                }

                try {
                    handleCallbackQuery(callbackQuery);
                } catch (Exception e) {
                    log.warn("Failed to handle callback query: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Telegram callback poll failed: {}", e.getMessage());
        }
    }

    private void handleCallbackQuery(JsonNode callbackQuery) {
        String callbackQueryId = callbackQuery.path("id").asText("");
        String callbackData = callbackQuery.path("data").asText("");
        JsonNode from = callbackQuery.path("from");
        JsonNode message = callbackQuery.path("message");

        if (callbackQueryId.isEmpty() || callbackData.isEmpty()) {
            return;
        }

        // Parse callback_data: "approve:<requestId>" or "deny:<requestId>"
        int colonIndex = callbackData.indexOf(':');
        if (colonIndex <= 0 || colonIndex >= callbackData.length() - 1) {
            telegramService.answerCallbackQuery(callbackQueryId, "Invalid callback data.");
            return;
        }

        String action = callbackData.substring(0, colonIndex);
        String requestId = callbackData.substring(colonIndex + 1);

        if (!"approve".equals(action) && !"deny".equals(action)) {
            telegramService.answerCallbackQuery(callbackQueryId, "Unknown action.");
            return;
        }

        // Validate the callback came from an approved chat
        String chatId = message.path("chat").path("id").asText("").trim();
        if (!telegramService.approvedChatIds().contains(chatId)) {
            telegramService.answerCallbackQuery(callbackQueryId, "This chat is not authorized.");
            return;
        }

        // Resolve the voter identity
        String username = from.path("username").asText("").trim();
        String firstName = from.path("first_name").asText("").trim();
        String decidedBy = !username.isEmpty() ? "@" + username : (!firstName.isEmpty() ? firstName : "telegram-user");

        // Look up the request
        ApprovalRequestRow requestRow = requestRepo.findApprovalRequestById(requestId);
        if (requestRow == null) {
            telegramService.answerCallbackQuery(callbackQueryId, "Request not found.");
            return;
        }

        // Check if the request is still votable
        String currentState = requestRow.state();
        if (!"QUEUED".equalsIgnoreCase(currentState) && !"PENDING".equalsIgnoreCase(currentState)) {
            telegramService.answerCallbackQuery(callbackQueryId, "This request has already been resolved.");
            editMessageWithOutcome(message, chatId, requestRow, currentState);
            return;
        }

        // Check if expired
        if (requestRow.expiresAt() != null && Instant.now().isAfter(requestRow.expiresAt())) {
            telegramService.answerCallbackQuery(callbackQueryId, "This request has expired.");
            return;
        }

        // Check for duplicate vote from telegram channel
        List<VoteDetail> existingVotes = voteRepo.listVotesForRequest(requestId);
        boolean alreadyVoted = existingVotes.stream()
                .anyMatch(v -> TELEGRAM_CHANNEL_ID.equals(v.channelId()));
        if (alreadyVoted) {
            telegramService.answerCallbackQuery(callbackQueryId, "A vote has already been cast via Telegram.");
            return;
        }

        // Insert the vote
        Instant now = Instant.now();
        String decision = action; // "approve" or "deny"
        voteRepo.insertVote(new VoteDetail(
                0L, requestId, TELEGRAM_CHANNEL_ID, decision, null, decidedBy, now
        ));

        // Evaluate vote outcome
        List<VoteDetail> allVotes = voteRepo.listVotesForRequest(requestId);
        int approvalsGranted = (int) allVotes.stream()
                .filter(v -> "approve".equalsIgnoreCase(v.decision())).count();
        int approvalsDenied = (int) allVotes.stream()
                .filter(v -> "deny".equalsIgnoreCase(v.decision())).count();

        String previousState = currentState;
        String nextState = previousState;
        String reasonCode;
        String reasonText;
        Instant resolvedAt = null;

        if (approvalsDenied > 0) {
            nextState = "DENIED";
            reasonCode = "vote_denied";
            reasonText = "Denied by Telegram vote";
            resolvedAt = now;
        } else if (approvalsGranted >= Math.max(1, requestRow.minApprovalsRequired())) {
            nextState = "APPROVED";
            reasonCode = "approval_threshold_met";
            reasonText = "Minimum approvals reached";
            resolvedAt = now;
        } else if ("QUEUED".equalsIgnoreCase(previousState)) {
            nextState = "PENDING";
            reasonCode = "awaiting_more_votes";
            reasonText = "Awaiting additional approvals";
        } else {
            reasonCode = "awaiting_more_votes";
            reasonText = "Awaiting additional approvals";
        }

        // Update request state
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

        // Insert state transition if state changed
        if (!nextState.equals(previousState)) {
            try {
                historyRepo.insertStateTransition(new StateTransitionRow(
                        0L, requestId, previousState, nextState,
                        ACTOR_TYPE, ACTOR_ID, reasonCode, now
                ));
            } catch (Exception e) {
                log.warn("State transition log failed for request {}: {}", requestId, e.getMessage());
            }
        }

        // Answer the callback query
        String answerText = "approve".equals(decision)
                ? "\u2705 Approved"
                : "\u274C Denied";
        telegramService.answerCallbackQuery(callbackQueryId, answerText);

        // Edit the original message: remove buttons and show outcome with new state
        long messageId = message.path("message_id").asLong(-1);
        if (messageId > 0) {
            String originalText = message.path("text").asText("");
            String outcomeLabel = "approve".equals(decision)
                    ? "\u2705 Approved by " + decidedBy
                    : "\u274C Denied by " + decidedBy;
            String stateLabel = "\uD83D\uDCCB Status: " + nextState;
            String updatedText = originalText + "\n\n" + outcomeLabel + "\n" + stateLabel;
            telegramService.editMessageText(chatId, messageId, updatedText);
        }

        log.info("Telegram vote processed: request={}, decision={}, by={}, state={}->{}",
                requestId, decision, decidedBy, previousState, nextState);
    }

    private void sweepAutoDeny() {
        try {
            Duration autoDenyTimeout = config.telegramAutoDenyTimeout();
            if (autoDenyTimeout == null || autoDenyTimeout.isZero() || autoDenyTimeout.isNegative()) {
                return;
            }

            Instant now = Instant.now();
            Instant cutoff = now.minus(autoDenyTimeout);

            List<ApprovalRequestRow> votable = requestRepo.findVotableRequests();
            for (ApprovalRequestRow row : votable) {
                try {
                    // Only auto-deny if telegram is enabled for this coin
                    if (!isTelegramEnabledForCoin(row.coin())) {
                        continue;
                    }

                    // Only auto-deny if the request was created before the cutoff
                    if (row.requestedAt() == null || row.requestedAt().isAfter(cutoff)) {
                        continue;
                    }

                    // Only auto-deny if no telegram vote has been cast yet
                    List<VoteDetail> votes = voteRepo.listVotesForRequest(row.id());
                    boolean hasTelegramVote = votes.stream()
                            .anyMatch(v -> TELEGRAM_CHANNEL_ID.equals(v.channelId()));
                    if (hasTelegramVote) {
                        continue;
                    }

                    // Auto-deny
                    String previousState = row.state();
                    int approvalsDenied = (int) votes.stream()
                            .filter(v -> "deny".equalsIgnoreCase(v.decision())).count();
                    int approvalsGranted = (int) votes.stream()
                            .filter(v -> "approve".equalsIgnoreCase(v.decision())).count();

                    voteRepo.insertVote(new VoteDetail(
                            0L, row.id(), TELEGRAM_CHANNEL_ID, "deny",
                            "auto-deny timeout (" + autoDenyTimeout + ")",
                            "telegram-auto-deny", now
                    ));
                    approvalsDenied++;

                    ApprovalRequestRow updated = new ApprovalRequestRow(
                            row.id(),
                            row.coin(),
                            row.toolName(),
                            row.requestSessionId(),
                            row.nonceUuid(),
                            row.payloadHashSha256(),
                            row.nonceComposite(),
                            row.toAddress(),
                            row.amountNative(),
                            row.feePolicy(),
                            row.feeCapNative(),
                            row.memo(),
                            row.requestedAt(),
                            row.expiresAt(),
                            "DENIED",
                            "telegram_auto_deny_timeout",
                            "Auto-denied: no Telegram response within " + autoDenyTimeout,
                            row.minApprovalsRequired(),
                            approvalsGranted,
                            approvalsDenied,
                            row.policyActionAtCreation(),
                            row.createdAt(),
                            now,
                            now
                    );
                    requestRepo.updateApprovalRequest(updated);

                    historyRepo.insertStateTransition(new StateTransitionRow(
                            0L, row.id(), previousState, "DENIED",
                            "system", "telegram-auto-deny",
                            "telegram_auto_deny_timeout", now
                    ));

                    log.info("Telegram auto-denied request {} (was {} since {}, timeout={})",
                            row.id(), previousState, row.requestedAt(), autoDenyTimeout);
                } catch (Exception e) {
                    log.warn("Failed to auto-deny request {}: {}", row.id(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Telegram auto-deny sweep failed: {}", e.getMessage());
        }
    }

    private boolean isTelegramEnabledForCoin(String coin) {
        CoinConfig coinConfig = resolveCoinConfig(coin);
        if (coinConfig == null) {
            return false;
        }
        CoinAuthConfig authConfig = coinConfig.auth();
        return authConfig != null && authConfig.telegram();
    }

    private CoinConfig resolveCoinConfig(String coin) {
        if (coin == null) {
            return null;
        }
        return switch (coin) {
            case "bitcoin" -> config.bitcoin();
            case "litecoin" -> config.litecoin();
            case "monero" -> config.monero();
            case "testdummycoin" -> config.testDummyCoin();
            default -> null;
        };
    }

    private void editMessageWithOutcome(JsonNode message, String chatId, ApprovalRequestRow row, String resolvedState) {
        long messageId = message.path("message_id").asLong(-1);
        if (messageId <= 0) {
            return;
        }

        String originalText = message.path("text").asText("");
        String outcomeLabel = switch (resolvedState.toUpperCase()) {
            case "APPROVED" -> "\u2705 Already approved";
            case "DENIED" -> "\u274C Already denied";
            case "EXPIRED" -> "\u23F0 Expired";
            default -> "Resolved (" + resolvedState + ")";
        };
        String updatedText = originalText + "\n\n" + outcomeLabel;
        telegramService.editMessageText(chatId, messageId, updatedText);
    }
}
