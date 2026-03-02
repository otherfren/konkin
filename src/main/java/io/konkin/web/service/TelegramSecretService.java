package io.konkin.web.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
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
 * - chat-type.<chatId>=<type>
 * - chat-title.<chatId>=<title>
 * - chat-username.<chatId>=<username>
 */
public class TelegramSecretService {

    private static final Logger log = LoggerFactory.getLogger(TelegramSecretService.class);

    public static final String TELEGRAM_CHAT_IDS_PLACEHOLDER = "REPLACE_WITH_TELEGRAM_CHAT_IDS";
    public static final String TELEGRAM_TOKEN_PLACEHOLDER = "REPLACE_WITH_TELEGRAM_BOT_TOKEN";

    private static final String CHAT_TYPE_PREFIX = "chat-type.";
    private static final String CHAT_TITLE_PREFIX = "chat-title.";
    private static final String CHAT_USERNAME_PREFIX = "chat-username.";

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
            return new TelegramSecret("", List.of(), Map.of());
        }

        String botToken = "";
        LinkedHashSet<String> chatIds = new LinkedHashSet<>();
        LinkedHashMap<String, String> chatTypes = new LinkedHashMap<>();
        LinkedHashMap<String, String> chatTitles = new LinkedHashMap<>();
        LinkedHashMap<String, String> chatUsernames = new LinkedHashMap<>();

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
                    continue;
                }

                if ("chat-ids".equalsIgnoreCase(key)) {
                    for (String split : value.split(",")) {
                        addChatId(chatIds, split);
                    }
                    continue;
                }

                if (key.startsWith(CHAT_TYPE_PREFIX)) {
                    String chatId = normalizeChatId(key.substring(CHAT_TYPE_PREFIX.length()));
                    if (!chatId.isEmpty()) {
                        chatTypes.put(chatId, normalizeMetaValue(value));
                    }
                    continue;
                }

                if (key.startsWith(CHAT_TITLE_PREFIX)) {
                    String chatId = normalizeChatId(key.substring(CHAT_TITLE_PREFIX.length()));
                    if (!chatId.isEmpty()) {
                        chatTitles.put(chatId, normalizeMetaValue(value));
                    }
                    continue;
                }

                if (key.startsWith(CHAT_USERNAME_PREFIX)) {
                    String chatId = normalizeChatId(key.substring(CHAT_USERNAME_PREFIX.length()));
                    if (!chatId.isEmpty()) {
                        chatUsernames.put(chatId, normalizeMetaValue(value));
                    }
                }
            }
        } catch (IOException e) {
            log.warn("Failed reading Telegram secret file {}", secretFile, e);
            return new TelegramSecret("", List.of(), Map.of());
        }

        LinkedHashSet<String> metadataChatIds = new LinkedHashSet<>();
        metadataChatIds.addAll(chatTypes.keySet());
        metadataChatIds.addAll(chatTitles.keySet());
        metadataChatIds.addAll(chatUsernames.keySet());

        LinkedHashMap<String, ChatMeta> chatMetaById = new LinkedHashMap<>();
        for (String chatId : metadataChatIds) {
            ChatMeta chatMeta = new ChatMeta(
                    chatTypes.getOrDefault(chatId, ""),
                    chatTitles.getOrDefault(chatId, ""),
                    chatUsernames.getOrDefault(chatId, "")
            );
            if (chatMeta.hasContent()) {
                chatMetaById.put(chatId, chatMeta);
            }
        }

        return new TelegramSecret(botToken, List.copyOf(chatIds), chatMetaById);
    }

    public boolean approveChatId(String chatId) {
        return approveChat(chatId, "", "", "");
    }

    public boolean approveChat(String chatId, String chatType, String chatTitle, String chatUsername) {
        String normalizedChatId = normalizeChatId(chatId);
        if (normalizedChatId.isEmpty()) {
            return false;
        }

        TelegramSecret current = readSecret();
        LinkedHashSet<String> chatIds = new LinkedHashSet<>(current.chatIds());
        chatIds.add(normalizedChatId);

        LinkedHashMap<String, ChatMeta> metadata = new LinkedHashMap<>(current.chatMetaById());
        ChatMeta merged = mergeMetadata(metadata.get(normalizedChatId), new ChatMeta(chatType, chatTitle, chatUsername));
        if (merged.hasContent()) {
            metadata.put(normalizedChatId, merged);
        }

        return writeSecret(new TelegramSecret(current.botToken(), List.copyOf(chatIds), metadata));
    }

    public boolean unapproveChatId(String chatId) {
        String normalizedChatId = normalizeChatId(chatId);
        if (normalizedChatId.isEmpty()) {
            return false;
        }

        TelegramSecret current = readSecret();
        LinkedHashSet<String> chatIds = new LinkedHashSet<>(current.chatIds());
        boolean removed = chatIds.remove(normalizedChatId);
        if (!removed) {
            return true;
        }

        return writeSecret(new TelegramSecret(current.botToken(), List.copyOf(chatIds), current.chatMetaById()));
    }

    public boolean resetApprovedChatIds() {
        TelegramSecret current = readSecret();
        return writeSecret(new TelegramSecret(current.botToken(), List.of(), current.chatMetaById()));
    }

    public boolean rememberDiscoveredChats(List<TelegramService.ChatRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return true;
        }

        TelegramSecret current = readSecret();
        LinkedHashMap<String, ChatMeta> metadata = new LinkedHashMap<>(current.chatMetaById());
        boolean changed = false;

        for (TelegramService.ChatRequest request : requests) {
            if (request == null) {
                continue;
            }

            String chatId = normalizeChatId(request.chatId());
            if (chatId.isEmpty()) {
                continue;
            }

            ChatMeta discovered = ChatMeta.fromChatRequest(request);
            if (!discovered.hasContent()) {
                continue;
            }

            ChatMeta existing = metadata.get(chatId);
            ChatMeta merged = mergeMetadata(existing, discovered);
            if (!merged.equals(existing)) {
                metadata.put(chatId, merged);
                changed = true;
            }
        }

        if (!changed) {
            return true;
        }

        return writeSecret(new TelegramSecret(current.botToken(), current.chatIds(), metadata));
    }

    public boolean writeSecret(TelegramSecret secret) {
        TelegramSecret normalized = secret == null
                ? new TelegramSecret("", List.of(), Map.of())
                : secret;

        String token = normalized.botToken() == null ? "" : normalized.botToken().trim();

        LinkedHashSet<String> normalizedChatIds = new LinkedHashSet<>();
        for (String chatId : normalized.chatIds()) {
            addChatId(normalizedChatIds, chatId);
        }

        LinkedHashMap<String, ChatMeta> metadata = new LinkedHashMap<>();
        for (Map.Entry<String, ChatMeta> entry : normalized.chatMetaById().entrySet()) {
            String chatId = normalizeChatId(entry.getKey());
            if (chatId.isEmpty()) {
                continue;
            }

            ChatMeta chatMeta = entry.getValue() == null ? new ChatMeta("", "", "") : entry.getValue();
            if (!chatMeta.hasContent()) {
                continue;
            }

            metadata.put(chatId, chatMeta);
        }

        LinkedHashMap<String, ChatMeta> orderedMetadata = new LinkedHashMap<>();
        for (String chatId : normalizedChatIds) {
            ChatMeta chatMeta = metadata.get(chatId);
            if (chatMeta != null) {
                orderedMetadata.put(chatId, chatMeta);
            }
        }
        for (Map.Entry<String, ChatMeta> entry : metadata.entrySet()) {
            orderedMetadata.putIfAbsent(entry.getKey(), entry.getValue());
        }

        String joinedChatIds = normalizedChatIds.isEmpty()
                ? TELEGRAM_CHAT_IDS_PLACEHOLDER
                : String.join(",", normalizedChatIds);

        StringBuilder serialized = new StringBuilder();
        serialized.append("# KONKIN Telegram secret file\n");
        serialized.append("# 1) Open Telegram and chat with @BotFather to create/get your bot token.\n");
        serialized.append("# 2) Send a message (for example /start) to your bot from private/group/channel chats you want to allow.\n");
        serialized.append("# 3) Open /telegram in KONKIN to approve discovered chat requests.\n");
        serialized.append("# 4) Restart KONKIN after changing bot-token manually.\n");
        serialized.append("bot-token=")
                .append(token.isBlank() ? TELEGRAM_TOKEN_PLACEHOLDER : sanitizeValue(token))
                .append('\n');
        serialized.append("chat-ids=").append(joinedChatIds).append('\n');

        if (!orderedMetadata.isEmpty()) {
            serialized.append('\n');
            serialized.append("# Discovered chat metadata (auto-maintained by /telegram).\n");
            for (Map.Entry<String, ChatMeta> entry : orderedMetadata.entrySet()) {
                String chatId = entry.getKey();
                ChatMeta chatMeta = entry.getValue();

                if (!chatMeta.chatType().isBlank()) {
                    serialized.append(CHAT_TYPE_PREFIX).append(chatId).append('=').append(sanitizeValue(chatMeta.chatType())).append('\n');
                }
                if (!chatMeta.chatTitle().isBlank()) {
                    serialized.append(CHAT_TITLE_PREFIX).append(chatId).append('=').append(sanitizeValue(chatMeta.chatTitle())).append('\n');
                }
                if (!chatMeta.chatUsername().isBlank()) {
                    serialized.append(CHAT_USERNAME_PREFIX).append(chatId).append('=').append(sanitizeValue(chatMeta.chatUsername())).append('\n');
                }
            }
        }

        try {
            Path parent = secretFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(secretFile, serialized.toString(), StandardCharsets.UTF_8);
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

    private static ChatMeta mergeMetadata(ChatMeta existing, ChatMeta incoming) {
        if (existing == null || !existing.hasContent()) {
            return incoming == null ? new ChatMeta("", "", "") : incoming;
        }
        if (incoming == null || !incoming.hasContent()) {
            return existing;
        }

        String chatType = incoming.chatType().isBlank() ? existing.chatType() : incoming.chatType();
        String chatTitle = incoming.chatTitle().isBlank() ? existing.chatTitle() : incoming.chatTitle();
        String chatUsername = incoming.chatUsername().isBlank() ? existing.chatUsername() : incoming.chatUsername();
        return new ChatMeta(chatType, chatTitle, chatUsername);
    }

    private static String sanitizeValue(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\r', ' ').replace('\n', ' ').trim();
    }

    private static String normalizeMetaValue(String value) {
        return sanitizeValue(value);
    }

    private static String normalizeChatId(String candidate) {
        if (candidate == null) {
            return "";
        }

        String normalized = candidate.trim();
        if (normalized.isEmpty() || TELEGRAM_CHAT_IDS_PLACEHOLDER.equals(normalized)) {
            return "";
        }

        return normalized;
    }

    private static void addChatId(LinkedHashSet<String> chatIds, String candidate) {
        String normalized = normalizeChatId(candidate);
        if (normalized.isEmpty()) {
            return;
        }

        chatIds.add(normalized);
    }

    public record TelegramSecret(String botToken, List<String> chatIds, Map<String, ChatMeta> chatMetaById) {

        public TelegramSecret(String botToken, List<String> chatIds) {
            this(botToken, chatIds, Map.of());
        }

        public TelegramSecret {
            String normalizedToken = botToken == null ? "" : botToken.trim();
            botToken = normalizedToken;

            LinkedHashSet<String> normalizedChatIds = new LinkedHashSet<>();
            if (chatIds != null) {
                for (String chatId : chatIds) {
                    String normalized = normalizeChatId(chatId);
                    if (!normalized.isEmpty()) {
                        normalizedChatIds.add(normalized);
                    }
                }
            }
            chatIds = List.copyOf(normalizedChatIds);

            LinkedHashMap<String, ChatMeta> normalizedMeta = new LinkedHashMap<>();
            if (chatMetaById != null) {
                for (Map.Entry<String, ChatMeta> entry : chatMetaById.entrySet()) {
                    String chatId = normalizeChatId(entry.getKey());
                    if (chatId.isEmpty()) {
                        continue;
                    }

                    ChatMeta chatMeta = entry.getValue() == null ? new ChatMeta("", "", "") : entry.getValue();
                    if (!chatMeta.hasContent()) {
                        continue;
                    }

                    normalizedMeta.put(chatId, chatMeta);
                }
            }

            chatMetaById = Collections.unmodifiableMap(normalizedMeta);
        }
    }

    public record ChatMeta(String chatType, String chatTitle, String chatUsername) {

        public ChatMeta {
            chatType = sanitizeValue(chatType);
            chatTitle = sanitizeValue(chatTitle);
            chatUsername = sanitizeValue(chatUsername);
        }

        public boolean hasContent() {
            return !chatType.isBlank() || !chatTitle.isBlank() || !chatUsername.isBlank();
        }

        public String displayName() {
            if (!chatTitle.isBlank()) {
                return chatTitle;
            }
            if (!chatUsername.isBlank()) {
                return "@" + chatUsername;
            }
            return "";
        }

        public static ChatMeta fromChatRequest(TelegramService.ChatRequest request) {
            if (request == null) {
                return new ChatMeta("", "", "");
            }
            return new ChatMeta(request.chatType(), request.chatTitle(), request.chatUsername());
        }
    }
}
