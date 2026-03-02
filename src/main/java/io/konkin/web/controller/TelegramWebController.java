package io.konkin.web.controller;

import io.javalin.http.Context;
import io.konkin.db.AuthQueueStore;
import io.konkin.db.entity.ApprovalChannelRow;
import io.konkin.web.service.TelegramSecretService;
import io.konkin.web.service.TelegramService;
import io.konkin.web.UiFormattingUtils;
import io.konkin.web.WebUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;

public class TelegramWebController {

    private static final Logger log = LoggerFactory.getLogger(TelegramWebController.class);
    private static final String TELEGRAM_CHANNEL_ID_PREFIX = "telegram:";

    private final boolean telegramEnabled;
    private final TelegramService telegramService;
    private final TelegramSecretService telegramSecretService;
    private final AuthQueueStore authQueueStore;

    public TelegramWebController(
            boolean telegramEnabled,
            TelegramService telegramService,
            TelegramSecretService telegramSecretService,
            AuthQueueStore authQueueStore
    ) {
        this.telegramEnabled = telegramEnabled;
        this.telegramService = telegramService;
        this.telegramSecretService = telegramSecretService;
        this.authQueueStore = authQueueStore;
    }

    public boolean isTelegramEnabled() {
        return telegramEnabled;
    }

    public void handleTelegramApprove(Context ctx, Runnable onAuthError, LandingPageAction onResult) {
        if (!telegramEnabled) {
            ctx.status(404);
            return;
        }

        String sourcePage = WebUtils.defaultIfBlank(ctx.formParam("source_page"), "").trim();
        boolean sourceAuthChannels = "auth_channels".equalsIgnoreCase(sourcePage);

        String chatIdInput = ctx.formParam("chat_id");
        String chatId = chatIdInput == null ? "" : chatIdInput.trim();
        if (chatId.isEmpty()) {
            if (sourceAuthChannels) {
                ctx.redirect("/auth_channels");
                return;
            }
            onResult.render("telegram", "Chat ID cannot be empty.", true, "");
            return;
        }

        String chatType = WebUtils.defaultIfBlank(ctx.formParam("chat_type"), "").trim();
        String chatTitle = WebUtils.defaultIfBlank(ctx.formParam("chat_title"), "").trim();
        String chatUsername = WebUtils.defaultIfBlank(ctx.formParam("chat_username"), "").trim();

        if (telegramSecretService.approveChat(chatId, chatType, chatTitle, chatUsername)) {
            if (sourceAuthChannels) {
                ctx.redirect("/auth_channels");
            } else {
                onResult.render("telegram", "Approved Telegram chat " + UiFormattingUtils.abbreviateId(chatId) + ".", false, "");
            }
            return;
        }

        log.warn("Failed Telegram chat approval from {} for chatId={}", ctx.ip(), chatId);
        if (sourceAuthChannels) {
            ctx.redirect("/auth_channels");
            return;
        }
        onResult.render("telegram", "Failed to approve Telegram chat.", true, "");
    }

    public void handleTelegramUnapprove(Context ctx, Runnable onAuthError, LandingPageAction onResult, TelegramConfirmAction onConfirm) {
        if (!telegramEnabled) {
            ctx.status(404);
            return;
        }

        String chatId = WebUtils.defaultIfBlank(ctx.formParam("chat_id"), "").trim();
        if (chatId.isEmpty()) {
            onResult.render("telegram", "Missing Telegram chat ID.", true, "");
            return;
        }

        String confirm = WebUtils.defaultIfBlank(ctx.formParam("confirm"), "").trim();
        if (!"yes".equalsIgnoreCase(confirm)) {
            onConfirm.showConfirm("unapprove", chatId);
            return;
        }

        if (telegramSecretService.unapproveChatId(chatId)) {
            onResult.render("telegram", "Unapproved Telegram chat " + UiFormattingUtils.abbreviateId(chatId) + ".", false, "");
            return;
        }

        log.warn("Failed Telegram chat unapproval from {} for chatId={}", ctx.ip(), chatId);
        onResult.render("telegram", "Failed to unapprove Telegram chat.", true, "");
    }

    public void handleTelegramReset(Context ctx, Runnable onAuthError, LandingPageAction onResult, TelegramConfirmAction onConfirm) {
        if (!telegramEnabled) {
            ctx.status(404);
            return;
        }

        String confirm = WebUtils.defaultIfBlank(ctx.formParam("confirm"), "").trim();
        if (!"yes".equalsIgnoreCase(confirm)) {
            onConfirm.showConfirm("reset", "");
            return;
        }

        if (telegramSecretService.resetApprovedChatIds()) {
            onResult.render("telegram", "Reset all approved Telegram chats.", false, "");
            return;
        }

        log.warn("Failed Telegram chat reset from {}", ctx.ip());
        onResult.render("telegram", "Failed to reset Telegram chats.", true, "");
    }

    public void handleTelegramSubmit(Context ctx, Runnable onAuthError, LandingPageAction onResult) {
        if (!telegramEnabled) {
            ctx.status(404);
            return;
        }

        String draft = WebUtils.defaultIfBlank(ctx.formParam("draft"), "").trim();
        if (draft.isEmpty()) {
            onResult.render("telegram", "Message cannot be empty.", true, "");
            return;
        }

        TelegramService.SendResult result = telegramService.sendMessage(draft);
        if (result.success()) {
            onResult.render("telegram", "Telegram message sent.", false, "");
        } else {
            log.warn("Failed to send Telegram message from {}: {}", ctx.ip(), result.detail());
            onResult.render("telegram", "Failed to send Telegram message: " + result.detail(), true, draft);
        }
    }

    @FunctionalInterface
    public interface LandingPageAction {
        void render(String page, String notice, boolean error, String draft);
    }

    @FunctionalInterface
    public interface TelegramConfirmAction {
        void showConfirm(String action, String chatId);
    }
}
