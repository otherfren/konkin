package io.konkin.web.service;

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

        this.sendMessageEndpoint = URI.create(normalizedBase + "/bot" + botToken + "/sendMessage");
        this.updatesEndpoint = URI.create(normalizedBase + "/bot" + botToken + "/getUpdates");

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
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Telegram chat discovery failed", e);
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

    public record ChatRequest(String chatId, String chatType, String chatTitle, String chatUsername) {
    }

    public record SendResult(boolean success, String detail) {
    }

    private record ChatSendOutcome(boolean success, String detail) {
    }
}
