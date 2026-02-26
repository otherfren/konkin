package io.konkin.web.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Reads and updates the Telegram secret file.
 *
 * Supported keys:
 * - bot-token=<telegram bot token>
 * - chat-ids=<id1,id2,...>
 */
public class TelegramSecretService {

    private static final Logger log = LoggerFactory.getLogger(TelegramSecretService.class);

    public static final String TELEGRAM_CHAT_IDS_PLACEHOLDER = "REPLACE_WITH_TELEGRAM_CHAT_IDS";
    public static final String TELEGRAM_TOKEN_PLACEHOLDER = "REPLACE_WITH_TELEGRAM_BOT_TOKEN";

    private final Path secretFile;

    public TelegramSecretService(Path secretFile) {
        this.secretFile = secretFile;
    }

    public Path secretFile() {
        return secretFile;
    }

    public boolean ensureExists() {
        if (Files.exists(secretFile)) {
            return true;
        }

        try {
            Path parent = secretFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            Files.writeString(secretFile, bootstrapContent(), StandardCharsets.UTF_8);
            return true;
        } catch (IOException e) {
            log.error("Failed to bootstrap Telegram secret file: {}", secretFile, e);
            return false;
        }
    }

    public TelegramSecret readSecret() {
        if (!Files.exists(secretFile)) {
            return new TelegramSecret("", List.of());
        }

        String botToken = "";
        LinkedHashSet<String> chatIds = new LinkedHashSet<>();

        try {
            for (String line : Files.readAllLines(secretFile, StandardCharsets.UTF_8)) {
                String candidate = line == null ? "" : line.trim();
                if (candidate.isEmpty() || candidate.startsWith("#")) {
                    continue;
                }

                int separator = candidate.indexOf('=');
                if (separator <= 0 || separator >= candidate.length() - 1) {
                    continue;
                }

                String key = candidate.substring(0, separator).trim();
                String value = candidate.substring(separator + 1).trim();

                if ("bot-token".equalsIgnoreCase(key) || "token".equalsIgnoreCase(key)) {
                    botToken = value;
                } else if ("chat-ids".equalsIgnoreCase(key)) {
                    for (String split : value.split(",")) {
                        addChatId(chatIds, split);
                    }
                }
            }
        } catch (IOException e) {
            log.warn("Failed reading Telegram secret file {}", secretFile, e);
            return new TelegramSecret("", List.of());
        }

        return new TelegramSecret(botToken, List.copyOf(chatIds));
    }

    public boolean approveChatId(String chatId) {
        if (chatId == null || chatId.isBlank()) {
            return false;
        }

        TelegramSecret current = readSecret();
        LinkedHashSet<String> chatIds = new LinkedHashSet<>(current.chatIds());
        addChatId(chatIds, chatId);

        String token = current.botToken() == null ? "" : current.botToken().trim();
        return writeSecret(new TelegramSecret(token, List.copyOf(chatIds)));
    }

    public boolean writeSecret(TelegramSecret secret) {
        String token = secret.botToken() == null ? "" : secret.botToken().trim();

        LinkedHashSet<String> normalizedChatIds = new LinkedHashSet<>();
        for (String chatId : secret.chatIds()) {
            addChatId(normalizedChatIds, chatId);
        }

        String joinedChatIds = normalizedChatIds.isEmpty()
                ? TELEGRAM_CHAT_IDS_PLACEHOLDER
                : String.join(",", normalizedChatIds);

        String serialized = """
                # KONKIN Telegram secret file
                # 1) Open Telegram and chat with @BotFather to create/get your bot token.
                # 2) Send a message (for example /start) to your bot from private/group/channel chats you want to allow.
                # 3) Open /telegram in KONKIN to approve discovered chat requests.
                # 4) Restart KONKIN after changing bot-token manually.
                bot-token=%s
                chat-ids=%s
                """.formatted(
                token.isBlank() ? TELEGRAM_TOKEN_PLACEHOLDER : token,
                joinedChatIds
        );

        try {
            Path parent = secretFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(secretFile, serialized, StandardCharsets.UTF_8);
            return true;
        } catch (IOException e) {
            log.error("Failed writing Telegram secret file: {}", secretFile, e);
            return false;
        }
    }

    public boolean hasConfiguredBotToken(TelegramSecret secret) {
        if (secret == null) {
            return false;
        }

        String token = secret.botToken();
        if (token == null || token.isBlank()) {
            return false;
        }

        return !TELEGRAM_TOKEN_PLACEHOLDER.equals(token.trim());
    }

    public static List<String> mergeChatIds(List<String> primary, List<String> secondary) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();

        if (primary != null) {
            for (String value : primary) {
                addChatId(merged, value);
            }
        }

        if (secondary != null) {
            for (String value : secondary) {
                addChatId(merged, value);
            }
        }

        return List.copyOf(merged);
    }

    public List<Map<String, String>> toChatRows(List<String> chatIds) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (chatIds != null) {
            for (String chatId : chatIds) {
                addChatId(normalized, chatId);
            }
        }

        return normalized.stream()
                .map(chatId -> {
                    Map<String, String> row = new LinkedHashMap<>();
                    row.put("chatId", chatId);
                    return row;
                })
                .toList();
    }

    private String bootstrapContent() {
        return """
                # KONKIN Telegram secret file
                # 1) Open Telegram and chat with @BotFather to create/get your bot token.
                # 2) Send a message (for example /start) to your bot from private/group/channel chats you want to allow.
                # 3) Open /telegram in KONKIN to approve discovered chat requests.
                # 4) If landing is disabled, set chat-ids manually and restart KONKIN.
                bot-token=%s
                chat-ids=%s
                """.formatted(
                TELEGRAM_TOKEN_PLACEHOLDER,
                TELEGRAM_CHAT_IDS_PLACEHOLDER
        );
    }

    private static void addChatId(LinkedHashSet<String> chatIds, String candidate) {
        if (candidate == null) {
            return;
        }

        String normalized = candidate.trim();
        if (normalized.isEmpty()) {
            return;
        }

        if (TELEGRAM_CHAT_IDS_PLACEHOLDER.equals(normalized)) {
            return;
        }

        chatIds.add(normalized);
    }

    public record TelegramSecret(String botToken, List<String> chatIds) {
    }
}
