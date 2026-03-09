package io.konkin.telegram;

import io.konkin.telegram.TelegramSecretService.ChatMeta;
import io.konkin.telegram.TelegramSecretService.TelegramSecret;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TelegramSecretServiceTest {

    @TempDir Path tempDir;

    // ── ensureExists ──

    @Test void ensureExistsCreatesFile() {
        Path secretFile = tempDir.resolve("sub/telegram.secret");
        var service = new TelegramSecretService(secretFile);
        assertTrue(service.ensureExists());
        assertTrue(Files.exists(secretFile));
    }

    @Test void ensureExistsAlreadyExists() throws IOException {
        Path secretFile = tempDir.resolve("telegram.secret");
        Files.writeString(secretFile, "bot-token=abc\nchat-ids=123\n");
        var service = new TelegramSecretService(secretFile);
        assertTrue(service.ensureExists());
    }

    // ── readSecret ──

    @Test void readSecretMissingFile() {
        var service = new TelegramSecretService(tempDir.resolve("missing"));
        TelegramSecret secret = service.readSecret();
        assertEquals("", secret.botToken());
        assertTrue(secret.chatIds().isEmpty());
    }

    @Test void readSecretBasic() throws IOException {
        Path f = tempDir.resolve("tg.secret");
        Files.writeString(f, "bot-token=mytoken123\nchat-ids=111,222\n");
        var service = new TelegramSecretService(f);
        TelegramSecret secret = service.readSecret();
        assertEquals("mytoken123", secret.botToken());
        assertEquals(List.of("111", "222"), secret.chatIds());
    }

    @Test void readSecretTokenAlias() throws IOException {
        Path f = tempDir.resolve("tg.secret");
        Files.writeString(f, "token=mytoken\nchat-ids=111\n");
        var service = new TelegramSecretService(f);
        assertEquals("mytoken", service.readSecret().botToken());
    }

    @Test void readSecretSkipsCommentsAndBlanks() throws IOException {
        Path f = tempDir.resolve("tg.secret");
        Files.writeString(f, "# comment\n\nbot-token=tok\n# another\nchat-ids=1\n");
        var service = new TelegramSecretService(f);
        TelegramSecret secret = service.readSecret();
        assertEquals("tok", secret.botToken());
        assertEquals(List.of("1"), secret.chatIds());
    }

    @Test void readSecretSkipsMalformedLines() throws IOException {
        Path f = tempDir.resolve("tg.secret");
        Files.writeString(f, "no-equals-sign\n=value-only\nbot-token=tok\nchat-ids=1\n");
        var service = new TelegramSecretService(f);
        assertEquals("tok", service.readSecret().botToken());
    }

    @Test void readSecretWithMetadata() throws IOException {
        Path f = tempDir.resolve("tg.secret");
        Files.writeString(f, """
                bot-token=tok
                chat-ids=111,222
                chat-type.111=private
                chat-title.111=John
                chat-username.111=johndoe
                chat-type.222=group
                chat-title.222=My Group
                """);
        var service = new TelegramSecretService(f);
        TelegramSecret secret = service.readSecret();
        assertEquals(2, secret.chatMetaById().size());
        assertEquals("private", secret.chatMetaById().get("111").chatType());
        assertEquals("John", secret.chatMetaById().get("111").chatTitle());
        assertEquals("johndoe", secret.chatMetaById().get("111").chatUsername());
        assertEquals("group", secret.chatMetaById().get("222").chatType());
    }

    @Test void readSecretFiltersChatIdPlaceholder() throws IOException {
        Path f = tempDir.resolve("tg.secret");
        Files.writeString(f, "bot-token=tok\nchat-ids=REPLACE_WITH_TELEGRAM_CHAT_IDS\n");
        var service = new TelegramSecretService(f);
        assertTrue(service.readSecret().chatIds().isEmpty());
    }

    // ── approveChatId / unapproveChatId ──

    @Test void approveChatId() throws IOException {
        Path f = tempDir.resolve("tg.secret");
        Files.writeString(f, "bot-token=tok\nchat-ids=111\n");
        var service = new TelegramSecretService(f);
        assertTrue(service.approveChatId("222"));
        TelegramSecret updated = service.readSecret();
        assertTrue(updated.chatIds().contains("222"));
        assertTrue(updated.chatIds().contains("111"));
    }

    @Test void approveChatIdBlank() throws IOException {
        Path f = tempDir.resolve("tg.secret");
        Files.writeString(f, "bot-token=tok\nchat-ids=111\n");
        var service = new TelegramSecretService(f);
        assertFalse(service.approveChatId("  "));
    }

    @Test void unapproveChatId() throws IOException {
        Path f = tempDir.resolve("tg.secret");
        Files.writeString(f, "bot-token=tok\nchat-ids=111,222\n");
        var service = new TelegramSecretService(f);
        assertTrue(service.unapproveChatId("222"));
        TelegramSecret updated = service.readSecret();
        assertFalse(updated.chatIds().contains("222"));
        assertTrue(updated.chatIds().contains("111"));
    }

    @Test void unapproveChatIdNotPresent() throws IOException {
        Path f = tempDir.resolve("tg.secret");
        Files.writeString(f, "bot-token=tok\nchat-ids=111\n");
        var service = new TelegramSecretService(f);
        assertTrue(service.unapproveChatId("999"));
    }

    @Test void unapproveChatIdBlank() throws IOException {
        Path f = tempDir.resolve("tg.secret");
        Files.writeString(f, "bot-token=tok\nchat-ids=111\n");
        var service = new TelegramSecretService(f);
        assertFalse(service.unapproveChatId("  "));
    }

    // ── resetApprovedChatIds ──

    @Test void resetApprovedChatIds() throws IOException {
        Path f = tempDir.resolve("tg.secret");
        Files.writeString(f, "bot-token=tok\nchat-ids=111,222\n");
        var service = new TelegramSecretService(f);
        assertTrue(service.resetApprovedChatIds());
        assertTrue(service.readSecret().chatIds().isEmpty());
    }

    // ── approveChat with metadata ──

    @Test void approveChatWithMetadata() throws IOException {
        Path f = tempDir.resolve("tg.secret");
        Files.writeString(f, "bot-token=tok\nchat-ids=\n");
        var service = new TelegramSecretService(f);
        assertTrue(service.approveChat("111", "private", "John", "johndoe"));
        TelegramSecret updated = service.readSecret();
        assertTrue(updated.chatIds().contains("111"));
        assertEquals("private", updated.chatMetaById().get("111").chatType());
    }

    // ── rememberDiscoveredChats ──

    @Test void rememberDiscoveredChatsEmpty() throws IOException {
        Path f = tempDir.resolve("tg.secret");
        Files.writeString(f, "bot-token=tok\nchat-ids=111\n");
        var service = new TelegramSecretService(f);
        assertTrue(service.rememberDiscoveredChats(List.of()));
        assertTrue(service.rememberDiscoveredChats(null));
    }

    @Test void rememberDiscoveredChatsNewMetadata() throws IOException {
        Path f = tempDir.resolve("tg.secret");
        Files.writeString(f, "bot-token=tok\nchat-ids=111\n");
        var service = new TelegramSecretService(f);
        var request = new TelegramService.ChatRequest("111", "private", "John", "johndoe");
        assertTrue(service.rememberDiscoveredChats(List.of(request)));
        TelegramSecret updated = service.readSecret();
        assertEquals("private", updated.chatMetaById().get("111").chatType());
    }

    @Test void rememberDiscoveredChatsNoChange() throws IOException {
        Path f = tempDir.resolve("tg.secret");
        Files.writeString(f, "bot-token=tok\nchat-ids=111\nchat-type.111=private\nchat-title.111=John\nchat-username.111=johndoe\n");
        var service = new TelegramSecretService(f);
        var request = new TelegramService.ChatRequest("111", "private", "John", "johndoe");
        assertTrue(service.rememberDiscoveredChats(List.of(request)));
    }

    @Test void rememberDiscoveredChatsNullRequest() throws IOException {
        Path f = tempDir.resolve("tg.secret");
        Files.writeString(f, "bot-token=tok\nchat-ids=111\n");
        var service = new TelegramSecretService(f);
        List<TelegramService.ChatRequest> withNull = new ArrayList<>();
        withNull.add(null);
        assertTrue(service.rememberDiscoveredChats(withNull));
    }

    @Test void rememberDiscoveredChatsBlankChatId() throws IOException {
        Path f = tempDir.resolve("tg.secret");
        Files.writeString(f, "bot-token=tok\nchat-ids=111\n");
        var service = new TelegramSecretService(f);
        var request = new TelegramService.ChatRequest("  ", "private", "John", "johndoe");
        assertTrue(service.rememberDiscoveredChats(List.of(request)));
    }

    @Test void rememberDiscoveredChatsNoContent() throws IOException {
        Path f = tempDir.resolve("tg.secret");
        Files.writeString(f, "bot-token=tok\nchat-ids=111\n");
        var service = new TelegramSecretService(f);
        var request = new TelegramService.ChatRequest("111", "", "", "");
        assertTrue(service.rememberDiscoveredChats(List.of(request)));
    }

    // ── hasConfiguredBotToken ──

    @Test void hasConfiguredBotTokenNull() {
        var service = new TelegramSecretService(tempDir.resolve("x"));
        assertFalse(service.hasConfiguredBotToken(null));
    }

    @Test void hasConfiguredBotTokenBlank() {
        var service = new TelegramSecretService(tempDir.resolve("x"));
        assertFalse(service.hasConfiguredBotToken(new TelegramSecret("", List.of())));
    }

    @Test void hasConfiguredBotTokenPlaceholder() {
        var service = new TelegramSecretService(tempDir.resolve("x"));
        assertFalse(service.hasConfiguredBotToken(
                new TelegramSecret(TelegramSecretService.TELEGRAM_TOKEN_PLACEHOLDER, List.of())));
    }

    @Test void hasConfiguredBotTokenValid() {
        var service = new TelegramSecretService(tempDir.resolve("x"));
        assertTrue(service.hasConfiguredBotToken(new TelegramSecret("real-token", List.of())));
    }

    // ── mergeChatIds ──

    @Test void mergeChatIdsNullBoth() {
        assertTrue(TelegramSecretService.mergeChatIds(null, null).isEmpty());
    }

    @Test void mergeChatIdsDeduplicates() {
        List<String> result = TelegramSecretService.mergeChatIds(List.of("1", "2"), List.of("2", "3"));
        assertEquals(List.of("1", "2", "3"), result);
    }

    // ── toChatRows ──

    @Test void toChatRowsNull() {
        var service = new TelegramSecretService(tempDir.resolve("x"));
        assertTrue(service.toChatRows(null).isEmpty());
    }

    @Test void toChatRowsValues() {
        var service = new TelegramSecretService(tempDir.resolve("x"));
        var rows = service.toChatRows(List.of("111", "222"));
        assertEquals(2, rows.size());
        assertEquals("111", rows.get(0).get("chatId"));
    }

    // ── ChatMeta ──

    @Test void chatMetaHasContent() {
        assertFalse(new ChatMeta("", "", "").hasContent());
        assertTrue(new ChatMeta("private", "", "").hasContent());
        assertTrue(new ChatMeta("", "title", "").hasContent());
        assertTrue(new ChatMeta("", "", "user").hasContent());
    }

    @Test void chatMetaDisplayName() {
        assertEquals("My Group", new ChatMeta("group", "My Group", "").displayName());
        assertEquals("@johndoe", new ChatMeta("private", "", "johndoe").displayName());
        assertEquals("", new ChatMeta("private", "", "").displayName());
    }

    @Test void chatMetaFromChatRequestNull() {
        ChatMeta meta = ChatMeta.fromChatRequest(null);
        assertFalse(meta.hasContent());
    }

    @Test void chatMetaFromChatRequest() {
        var req = new TelegramService.ChatRequest("111", "private", "John", "johndoe");
        ChatMeta meta = ChatMeta.fromChatRequest(req);
        assertEquals("private", meta.chatType());
        assertEquals("John", meta.chatTitle());
        assertEquals("johndoe", meta.chatUsername());
    }

    // ── writeSecret ──

    @Test void writeSecretNull() {
        Path f = tempDir.resolve("tg.secret");
        var service = new TelegramSecretService(f);
        assertTrue(service.writeSecret(null));
        assertTrue(Files.exists(f));
    }

    @Test void writeSecretPreservesMetadataOrder() throws IOException {
        Path f = tempDir.resolve("tg.secret");
        var service = new TelegramSecretService(f);
        var meta = Map.of("222", new ChatMeta("group", "Group", ""),
                "111", new ChatMeta("private", "John", "johndoe"));
        assertTrue(service.writeSecret(new TelegramSecret("tok", List.of("111", "222"), meta)));
        String content = Files.readString(f);
        assertTrue(content.contains("chat-ids=111,222"));
        assertTrue(content.contains("chat-type.111=private"));
        assertTrue(content.contains("chat-type.222=group"));
    }
}
