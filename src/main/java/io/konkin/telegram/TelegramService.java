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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Telegram API client bound to one bot token and a list of approved chat IDs.
 */
public class TelegramService {

    private static final Logger log = LoggerFactory.getLogger(TelegramService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient httpClient;
    private final URI sendMessageEndpoint;
    private final URI updatesEndpoint;
    private final URI answerCallbackQueryEndpoint;
    private final URI editMessageTextEndpoint;
    private final List<String> chatIds;

    public TelegramService(String apiBaseUrl, String botToken, String chatId) {
        this(apiBaseUrl, botToken, List.of(chatId));
    }

    public TelegramService(String apiBaseUrl, String botToken, List<String> chatIds) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();

        String normalizedBase = apiBaseUrl.endsWith("/")
                ? apiBaseUrl.substring(0, apiBaseUrl.length() - 1)
                : apiBaseUrl;

        String botBase = normalizedBase + "/bot" + botToken;
        this.sendMessageEndpoint = URI.create(botBase + "/sendMessage");
        this.updatesEndpoint = URI.create(botBase + "/getUpdates");
        this.answerCallbackQueryEndpoint = URI.create(botBase + "/answerCallbackQuery");
        this.editMessageTextEndpoint = URI.create(botBase + "/editMessageText");

        LinkedHashSet<String> normalizedChatIds = new LinkedHashSet<>();
        if (chatIds != null) {
            for (String chatId : chatIds) {
                if (chatId != null && !chatId.isBlank()) {
                    normalizedChatIds.add(chatId.trim());
                }
            }
        }
        this.chatIds = List.copyOf(normalizedChatIds);
    }

    public SendResult sendMessage(String messageText) {
        return sendMessage(messageText, chatIds);
    }

    public SendResult sendMessage(String messageText, List<String> targetChatIds) {
        LinkedHashSet<String> normalizedTargets = new LinkedHashSet<>();
        if (targetChatIds != null) {
            for (String chatId : targetChatIds) {
                if (chatId != null && !chatId.isBlank()) {
                    normalizedTargets.add(chatId.trim());
                }
            }
        }

        if (normalizedTargets.isEmpty()) {
            return new SendResult(false, "no approved chat IDs configured");
        }

        int successCount = 0;
        List<String> failures = new ArrayList<>();

        for (String chatId : normalizedTargets) {
            ChatSendOutcome outcome = sendToChat(chatId, messageText);
            if (outcome.success()) {
                successCount++;
            } else {
                failures.add(outcome.detail());
            }
        }

        if (successCount == normalizedTargets.size()) {
            return new SendResult(true, "ok");
        }

        if (successCount > 0) {
            return new SendResult(true, "sent to " + successCount + "/" + normalizedTargets.size() + " chats (partial)");
        }

        String detail = failures.isEmpty()
                ? "telegram send failed"
                : failures.getFirst();
        return new SendResult(false, detail);
    }

    public List<ChatRequest> discoverChatRequests() {
        HttpRequest request = HttpRequest.newBuilder(updatesEndpoint)
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return List.of();
            }

            JsonNode root = JSON.readTree(response.body());
            if (!root.path("ok").asBoolean(false)) {
                return List.of();
            }

            Map<String, ChatRequest> deduplicated = new LinkedHashMap<>();
            for (JsonNode update : root.path("result")) {
                JsonNode chat = update.path("message").path("chat");
                if (chat.isMissingNode() || chat.isNull()) {
                    chat = update.path("channel_post").path("chat");
                }
                if (chat.isMissingNode() || chat.isNull()) {
                    continue;
                }

                String chatId = chat.path("id").asText("").trim();
                if (chatId.isEmpty()) {
                    continue;
                }

                String chatType = chat.path("type").asText("").trim();
                String title = resolveChatTitle(chat);
                String username = resolveChatUsername(chat);
                deduplicated.put(chatId, new ChatRequest(chatId, chatType, title, username));
            }

            return List.copyOf(deduplicated.values());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("Telegram chat discovery interrupted");
            return List.of();
        } catch (IOException e) {
            log.warn("Telegram chat discovery failed: {}", e.getMessage());
            return List.of();
        }
    }

    private ChatSendOutcome sendToChat(String chatId, String messageText) {
        String payload = "chat_id=" + encode(chatId)
                + "&text=" + encode(messageText);

        HttpRequest request = HttpRequest.newBuilder(sendMessageEndpoint)
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return new ChatSendOutcome(
                        false,
                        "telegram api http status " + response.statusCode() + " for chat " + chatId
                );
            }

            if (response.body() != null && response.body().contains("\"ok\":true")) {
                return new ChatSendOutcome(true, "ok");
            }

            return new ChatSendOutcome(false, "telegram api response did not confirm ok=true for chat " + chatId);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Telegram send failed", e);
            return new ChatSendOutcome(false, e.getMessage() == null ? "send failed" : e.getMessage());
        }
    }

    private static String resolveChatTitle(JsonNode chat) {
        String title = chat.path("title").asText("").trim();
        if (!title.isEmpty()) {
            return title;
        }

        String username = chat.path("username").asText("").trim();
        if (!username.isEmpty()) {
            return "@" + username;
        }

        String firstName = chat.path("first_name").asText("").trim();
        String lastName = chat.path("last_name").asText("").trim();
        String joined = (firstName + " " + lastName).trim();
        return joined;
    }

    private static String resolveChatUsername(JsonNode chat) {
        return chat.path("username").asText("").trim();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * Sends a message with inline approve/deny buttons to all approved chat IDs.
     */
    public SendResult sendApprovalPrompt(String messageText, String requestId) {
        String replyMarkupJson = buildApprovalKeyboardJson(requestId);

        int successCount = 0;
        List<String> failures = new ArrayList<>();

        for (String chatId : chatIds) {
            ChatSendOutcome outcome = sendToChatWithReplyMarkup(chatId, messageText, replyMarkupJson);
            if (outcome.success()) {
                successCount++;
            } else {
                failures.add(outcome.detail());
            }
        }

        if (chatIds.isEmpty()) {
            return new SendResult(false, "no approved chat IDs configured");
        }

        if (successCount == chatIds.size()) {
            return new SendResult(true, "ok");
        }

        if (successCount > 0) {
            return new SendResult(true, "sent to " + successCount + "/" + chatIds.size() + " chats (partial)");
        }

        String detail = failures.isEmpty()
                ? "telegram send failed"
                : failures.getFirst();
        return new SendResult(false, detail);
    }

    private ChatSendOutcome sendToChatWithReplyMarkup(String chatId, String messageText, String replyMarkupJson) {
        String payload = "chat_id=" + encode(chatId)
                + "&text=" + encode(messageText)
                + "&reply_markup=" + encode(replyMarkupJson);

        HttpRequest request = HttpRequest.newBuilder(sendMessageEndpoint)
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return new ChatSendOutcome(
                        false,
                        "telegram api http status " + response.statusCode() + " for chat " + chatId
                );
            }

            if (response.body() != null && response.body().contains("\"ok\":true")) {
                return new ChatSendOutcome(true, "ok");
            }

            return new ChatSendOutcome(false, "telegram api response did not confirm ok=true for chat " + chatId);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Telegram send with inline keyboard failed", e);
            return new ChatSendOutcome(false, e.getMessage() == null ? "send failed" : e.getMessage());
        }
    }

    private static String buildApprovalKeyboardJson(String requestId) {
        try {
            Map<String, Object> approveButton = new LinkedHashMap<>();
            approveButton.put("text", "\u2705 Approve");
            approveButton.put("callback_data", "approve:" + requestId);

            Map<String, Object> denyButton = new LinkedHashMap<>();
            denyButton.put("text", "\u274C Deny");
            denyButton.put("callback_data", "deny:" + requestId);

            Map<String, Object> keyboard = new LinkedHashMap<>();
            keyboard.put("inline_keyboard", List.of(List.of(approveButton, denyButton)));

            return JSON.writeValueAsString(keyboard);
        } catch (IOException e) {
            log.error("Failed to build inline keyboard JSON", e);
            return "{\"inline_keyboard\":[]}";
        }
    }

    /**
     * Fetches updates from Telegram with an offset, returning raw JSON results.
     * Uses long polling with the given timeout (in seconds).
     */
    public JsonNode getUpdates(long offset, int timeoutSeconds) {
        String url = updatesEndpoint.toString()
                + "?offset=" + offset
                + "&timeout=" + timeoutSeconds
                + "&allowed_updates=" + encode("[\"callback_query\",\"message\",\"channel_post\"]");

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSeconds + 5))
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return null;
            }

            JsonNode root = JSON.readTree(response.body());
            if (!root.path("ok").asBoolean(false)) {
                return null;
            }

            return root.path("result");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("Telegram getUpdates interrupted");
            return null;
        } catch (IOException e) {
            log.warn("Telegram getUpdates failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Acknowledges a callback query to dismiss the loading spinner on the button.
     */
    public boolean answerCallbackQuery(String callbackQueryId, String text) {
        String payload = "callback_query_id=" + encode(callbackQueryId);
        if (text != null && !text.isBlank()) {
            payload += "&text=" + encode(text);
        }

        HttpRequest request = HttpRequest.newBuilder(answerCallbackQueryEndpoint)
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() >= 200 && response.statusCode() < 300
                    && response.body() != null && response.body().contains("\"ok\":true");
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Telegram answerCallbackQuery failed", e);
            return false;
        }
    }

    /**
     * Edits a previously sent message's text (removes inline keyboard by default).
     */
    public boolean editMessageText(String chatId, long messageId, String newText) {
        return editMessageText(chatId, messageId, newText, true);
    }

    /**
     * Edits a previously sent message's text, optionally removing the inline keyboard.
     */
    public boolean editMessageText(String chatId, long messageId, String newText, boolean removeKeyboard) {
        String payload = "chat_id=" + encode(chatId)
                + "&message_id=" + messageId
                + "&text=" + encode(newText);

        if (removeKeyboard) {
            payload += "&reply_markup=" + encode("{\"inline_keyboard\":[]}");
        }

        HttpRequest request = HttpRequest.newBuilder(editMessageTextEndpoint)
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() >= 200 && response.statusCode() < 300
                    && response.body() != null && response.body().contains("\"ok\":true");
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Telegram editMessageText failed", e);
            return false;
        }
    }

    /**
     * Tests whether a bot token is valid by calling the Telegram getMe endpoint.
     * Returns null on success, or an error message on failure.
     */
    public static String testBotToken(String apiBaseUrl, String botToken) {
        String normalizedBase = apiBaseUrl.endsWith("/")
                ? apiBaseUrl.substring(0, apiBaseUrl.length() - 1)
                : apiBaseUrl;

        URI getMeEndpoint = URI.create(normalizedBase + "/bot" + botToken + "/getMe");

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        HttpRequest request = HttpRequest.newBuilder(getMeEndpoint)
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401 || response.statusCode() == 404) {
                return "Invalid bot token (HTTP " + response.statusCode() + ")";
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return "Telegram API returned HTTP " + response.statusCode();
            }

            JsonNode root = JSON.readTree(response.body());
            if (!root.path("ok").asBoolean(false)) {
                String description = root.path("description").asText("unknown error");
                return "Telegram API error: " + description;
            }

            return null; // success
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Connection interrupted";
        } catch (IOException e) {
            String msg = e.getMessage();
            return "Connection failed: " + (msg != null ? msg : e.getClass().getSimpleName());
        }
    }

    /**
     * Returns the list of approved chat IDs this service is configured with.
     */
    public List<String> approvedChatIds() {
        return chatIds;
    }

    public record ChatRequest(String chatId, String chatType, String chatTitle, String chatUsername) {
    }

    public record SendResult(boolean success, String detail) {
    }

    private record ChatSendOutcome(boolean success, String detail) {
    }
}