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

package io.konkin.telegram;

import io.javalin.http.Context;
import io.konkin.web.WebUtils;
import io.konkin.web.controller.LandingPageController;
import io.konkin.web.service.LandingPageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.konkin.web.UiFormattingUtils.*;
import static io.konkin.web.WebUtils.*;

public class TelegramWebController {

    private static final Logger log = LoggerFactory.getLogger(TelegramWebController.class);

    private final List<String> configuredTelegramChatIds;
    private final TelegramService telegramService;
    private final TelegramSecretService telegramSecretService;
    private final LandingPageService landingPageService;
    private final boolean passwordProtectionEnabled;
    private Map<String, Instant> activeSessions;
    private LandingPageController landingPageController;

    public TelegramWebController(
            List<String> configuredTelegramChatIds,
            TelegramService telegramService,
            TelegramSecretService telegramSecretService,
            LandingPageService landingPageService,
            boolean passwordProtectionEnabled,
            Map<String, Instant> activeSessions
    ) {
        this.configuredTelegramChatIds = configuredTelegramChatIds == null ? List.of() : List.copyOf(configuredTelegramChatIds);
        this.telegramService = telegramService;
        this.telegramSecretService = telegramSecretService;
        this.landingPageService = landingPageService;
        this.passwordProtectionEnabled = passwordProtectionEnabled;
        this.activeSessions = activeSessions;
    }

    public void setLandingPageController(LandingPageController landingPageController) {
        this.landingPageController = landingPageController;
    }

    public void setActiveSessions(Map<String, Instant> activeSessions) {
        this.activeSessions = activeSessions;
    }

    // ── Action handlers ────────────────────────────────────────────────────

    public void handleApprove(Context ctx) {
        if (passwordProtectionEnabled && !WebUtils.hasValidSession(ctx, activeSessions)) {
            showLogin(ctx, false);
            return;
        }
        if (passwordProtectionEnabled && !WebUtils.isValidCsrf(ctx)) {
            log.warn("CSRF validation failed for telegram approve from {}", ctx.ip());
            ctx.status(403);
            renderTelegramPage(ctx, "Invalid CSRF token. Please reload the page and try again.", true, "", null);
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
            renderTelegramPage(ctx, "Chat ID cannot be empty.", true, "", null);
            return;
        }

        String chatType = defaultIfBlank(ctx.formParam("chat_type"), "").trim();
        String chatTitle = defaultIfBlank(ctx.formParam("chat_title"), "").trim();
        String chatUsername = defaultIfBlank(ctx.formParam("chat_username"), "").trim();

        if (telegramSecretService.approveChat(chatId, chatType, chatTitle, chatUsername)) {
            if (sourceAuthChannels) {
                ctx.redirect("/auth_channels");
                return;
            }
            renderTelegramPage(ctx, "Approved Telegram chat " + abbreviateId(chatId) + ".", false, "", null);
            return;
        }

        log.warn("Failed Telegram chat approval from {} for chatId={}", ctx.ip(), chatId);
        if (sourceAuthChannels) {
            ctx.redirect("/auth_channels");
            return;
        }
        renderTelegramPage(ctx, "Failed to approve Telegram chat.", true, "", null);
    }

    public void handleUnapprove(Context ctx) {
        if (passwordProtectionEnabled && !WebUtils.hasValidSession(ctx, activeSessions)) {
            showLogin(ctx, false);
            return;
        }
        if (passwordProtectionEnabled && !WebUtils.isValidCsrf(ctx)) {
            log.warn("CSRF validation failed for telegram unapprove from {}", ctx.ip());
            ctx.status(403);
            renderTelegramPage(ctx, "Invalid CSRF token. Please reload the page and try again.", true, "", null);
            return;
        }

        String chatId = defaultIfBlank(ctx.formParam("chat_id"), "").trim();
        if (chatId.isEmpty()) {
            renderTelegramPage(ctx, "Missing Telegram chat ID.", true, "", null);
            return;
        }

        String confirm = defaultIfBlank(ctx.formParam("confirm"), "").trim();
        if (!"yes".equalsIgnoreCase(confirm)) {
            renderTelegramPage(ctx, "Please confirm to unapprove chat " + abbreviateId(chatId) + ".", false, "", new TelegramConfirmData("unapprove", chatId));
            return;
        }

        if (telegramSecretService.unapproveChatId(chatId)) {
            renderTelegramPage(ctx, "Unapproved Telegram chat " + abbreviateId(chatId) + ".", false, "", null);
            return;
        }

        log.warn("Failed Telegram chat unapproval from {} for chatId={}", ctx.ip(), chatId);
        renderTelegramPage(ctx, "Failed to unapprove Telegram chat.", true, "", null);
    }

    public void handleReset(Context ctx) {
        if (passwordProtectionEnabled && !WebUtils.hasValidSession(ctx, activeSessions)) {
            showLogin(ctx, false);
            return;
        }
        if (passwordProtectionEnabled && !WebUtils.isValidCsrf(ctx)) {
            log.warn("CSRF validation failed for telegram reset from {}", ctx.ip());
            ctx.status(403);
            renderTelegramPage(ctx, "Invalid CSRF token. Please reload the page and try again.", true, "", null);
            return;
        }

        String confirm = defaultIfBlank(ctx.formParam("confirm"), "").trim();
        if (!"yes".equalsIgnoreCase(confirm)) {
            renderTelegramPage(ctx, "Please confirm reset of all approved Telegram chats.", false, "", new TelegramConfirmData("reset", ""));
            return;
        }

        if (telegramSecretService.resetApprovedChatIds()) {
            renderTelegramPage(ctx, "Reset persisted approved Telegram chats.", false, "", null);
            return;
        }

        log.warn("Failed Telegram approved-chat reset from {}", ctx.ip());
        renderTelegramPage(ctx, "Failed to reset approved Telegram chats.", true, "", null);
    }

    public void handleSend(Context ctx) {
        if (passwordProtectionEnabled && !WebUtils.hasValidSession(ctx, activeSessions)) {
            showLogin(ctx, false);
            return;
        }
        if (passwordProtectionEnabled && !WebUtils.isValidCsrf(ctx)) {
            log.warn("CSRF validation failed for telegram send from {}", ctx.ip());
            ctx.status(403);
            renderTelegramPage(ctx, "Invalid CSRF token. Please reload the page and try again.", true, "", null);
            return;
        }

        String messageInput = ctx.formParam("telegram_message");
        String messageText = messageInput == null ? "" : messageInput.trim();

        if (messageText.isEmpty()) {
            renderTelegramPage(ctx, "Message cannot be empty.", true, messageInput == null ? "" : messageInput, null);
            return;
        }

        if (messageText.length() > 4096) {
            renderTelegramPage(ctx, "Message is too long (max 4096 characters).", true, messageInput, null);
            return;
        }

        List<String> targetChatIds = approvedChatIds();
        TelegramService.SendResult result = telegramService.sendMessage(messageText, targetChatIds);
        if (result.success()) {
            renderTelegramPage(ctx, "Telegram message sent.", false, "", null);
            return;
        }

        String detail = result.detail() == null || result.detail().isBlank() ? "unknown error" : result.detail();
        log.warn("Telegram send failed from {}: {}", ctx.ip(), detail);
        renderTelegramPage(ctx, "Telegram send failed: " + detail, true, messageInput, null);
    }

    private void renderTelegramPage(Context ctx, String notice, boolean error, String draft, TelegramConfirmData confirmData) {
        if (landingPageController != null) {
            landingPageController.renderLandingForPage(
                    ctx, "auth_channel_telegram", notice, error, draft,
                    "", false, null, confirmData
            );
        } else {
            // Fallback for tests if controller is not yet linked
            ctx.status(200);
            ctx.result(notice);
        }
    }

    private void showLogin(Context ctx, boolean invalidPassword) {
        ctx.status(invalidPassword ? 401 : 200);
        ctx.contentType("text/html; charset=UTF-8");
        ctx.result(landingPageService.renderLogin(invalidPassword));
    }

    public record TelegramConfirmData(String mode, String chatId) {}

    // ── Data loading ───────────────────────────────────────────────────────

    public TelegramPageData loadTelegramPageData() {
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

        for (Map.Entry<String, TelegramSecretService.ChatMeta> entry : metadataByChatId.entrySet()) {
            String chatId = entry.getKey();
            if (approvedSet.contains(chatId) || discoveredByChatId.containsKey(chatId)) {
                continue;
            }
            TelegramSecretService.ChatMeta meta = entry.getValue();
            Map<String, String> row = new LinkedHashMap<>();
            row.put("chatId", chatId);
            row.put("chatType", firstNonBlank(meta.chatType(), "unknown"));
            row.put("chatTitle", firstNonBlank(meta.chatTitle(), "(no title)"));
            row.put("chatUsername", firstNonBlank(meta.chatUsername(), ""));
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

    public List<String> approvedChatIds() {
        TelegramSecretService.TelegramSecret secret = telegramSecretService.readSecret();
        return TelegramSecretService.mergeChatIds(configuredTelegramChatIds, secret.chatIds());
    }

    public DiscoveredChats discoverAndPersistChats() {
        List<TelegramService.ChatRequest> discoveredRequests = telegramService.discoverChatRequests();
        if (!telegramSecretService.rememberDiscoveredChats(discoveredRequests)) {
            log.warn("Failed to persist discovered Telegram chat metadata for auth_channels page");
        }

        TelegramSecretService.TelegramSecret secret = telegramSecretService.readSecret();
        return new DiscoveredChats(discoveredRequests, secret, configuredTelegramChatIds);
    }

    // ── Result types ───────────────────────────────────────────────────────

    public sealed interface TelegramActionResult {

        record Redirect(String path) implements TelegramActionResult {
        }

        record Render(String notice, boolean error, String draft) implements TelegramActionResult {
        }

        record ConfirmRequired(String notice, String mode, String chatId) implements TelegramActionResult {
        }
    }

    public record TelegramPageData(
            List<Map<String, String>> chatRequests,
            List<Map<String, String>> approvedChats
    ) {
    }

    public record DiscoveredChats(
            List<TelegramService.ChatRequest> discoveredRequests,
            TelegramSecretService.TelegramSecret secret,
            List<String> configuredTelegramChatIds
    ) {
    }
}