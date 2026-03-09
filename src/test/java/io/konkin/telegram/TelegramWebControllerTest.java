package io.konkin.telegram;

import io.konkin.web.service.LandingPageService;
import io.konkin.telegram.TelegramSecretService.ChatMeta;
import io.konkin.telegram.TelegramSecretService.TelegramSecret;
import io.konkin.telegram.TelegramService.ChatRequest;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TelegramWebControllerTest {

    private final TelegramService telegramService = mock(TelegramService.class);
    private final TelegramSecretService telegramSecretService = mock(TelegramSecretService.class);
    private final LandingPageService landingPageService = mock(LandingPageService.class);

    private TelegramWebController controller(List<String> configuredChatIds) {
        return new TelegramWebController(
                configuredChatIds, telegramService, telegramSecretService,
                landingPageService, false, null
        );
    }

    // ── loadTelegramPageData ──

    @Test void noDiscoveredChatsAndNoApproved_emptyRows() {
        when(telegramService.discoverChatRequests()).thenReturn(List.of());
        when(telegramSecretService.rememberDiscoveredChats(anyList())).thenReturn(true);
        when(telegramSecretService.readSecret()).thenReturn(new TelegramSecret("tok", List.of(), Map.of()));

        var data = controller(List.of()).loadTelegramPageData();

        assertTrue(data.chatRequests().isEmpty());
        assertTrue(data.approvedChats().isEmpty());
    }

    @Test void discoveredChatNotApproved_appearsInRequestRows() {
        var discovered = List.of(new ChatRequest("111", "group", "My Group", "mygroup"));
        when(telegramService.discoverChatRequests()).thenReturn(discovered);
        when(telegramSecretService.rememberDiscoveredChats(anyList())).thenReturn(true);
        when(telegramSecretService.readSecret()).thenReturn(new TelegramSecret("tok", List.of(), Map.of()));

        var data = controller(List.of()).loadTelegramPageData();

        assertEquals(1, data.chatRequests().size());
        assertEquals("111", data.chatRequests().getFirst().get("chatId"));
        assertEquals("group", data.chatRequests().getFirst().get("chatType"));
        assertEquals("My Group", data.chatRequests().getFirst().get("chatTitle"));
        assertEquals("mygroup", data.chatRequests().getFirst().get("chatUsername"));
        assertTrue(data.approvedChats().isEmpty());
    }

    @Test void discoveredChatAlreadyApproved_appearsInApprovedRowsNotRequestRows() {
        var discovered = List.of(new ChatRequest("222", "private", "Alice", "alice"));
        when(telegramService.discoverChatRequests()).thenReturn(discovered);
        when(telegramSecretService.rememberDiscoveredChats(anyList())).thenReturn(true);
        when(telegramSecretService.readSecret()).thenReturn(
                new TelegramSecret("tok", List.of("222"), Map.of())
        );

        var data = controller(List.of()).loadTelegramPageData();

        assertTrue(data.chatRequests().isEmpty());
        assertEquals(1, data.approvedChats().size());
        assertEquals("222", data.approvedChats().getFirst().get("chatId"));
    }

    @Test void persistedMetadataForUnapprovedChat_appearsInRequestRows() {
        // No live discoveries, but metadata persisted from a previous discovery
        when(telegramService.discoverChatRequests()).thenReturn(List.of());
        when(telegramSecretService.rememberDiscoveredChats(anyList())).thenReturn(true);

        Map<String, ChatMeta> metaMap = new LinkedHashMap<>();
        metaMap.put("333", new ChatMeta("channel", "Old Channel", "oldchan"));
        when(telegramSecretService.readSecret()).thenReturn(
                new TelegramSecret("tok", List.of(), metaMap)
        );

        var data = controller(List.of()).loadTelegramPageData();

        assertEquals(1, data.chatRequests().size());
        assertEquals("333", data.chatRequests().getFirst().get("chatId"));
        assertEquals("channel", data.chatRequests().getFirst().get("chatType"));
        assertEquals("Old Channel", data.chatRequests().getFirst().get("chatTitle"));
        assertEquals("oldchan", data.chatRequests().getFirst().get("chatUsername"));
        assertTrue(data.approvedChats().isEmpty());
    }

    @Test void mixOfApprovedDiscoveredAndPersistedOnly() {
        // Chat A: discovered + approved (via secret file)
        // Chat B: discovered + not approved
        // Chat C: persisted metadata only, not approved, not discovered live
        // Chat D: approved via configured list, no metadata
        var discovered = List.of(
                new ChatRequest("AAA", "group", "Group A", "grpA"),
                new ChatRequest("BBB", "private", "Bob", "bob")
        );
        when(telegramService.discoverChatRequests()).thenReturn(discovered);
        when(telegramSecretService.rememberDiscoveredChats(anyList())).thenReturn(true);

        Map<String, ChatMeta> metaMap = new LinkedHashMap<>();
        metaMap.put("AAA", new ChatMeta("group", "Group A", "grpA"));
        metaMap.put("CCC", new ChatMeta("supergroup", "Persisted Chat", "pchat"));
        when(telegramSecretService.readSecret()).thenReturn(
                new TelegramSecret("tok", List.of("AAA"), metaMap)
        );

        // DDD is configured in the application config
        var data = controller(List.of("DDD")).loadTelegramPageData();

        // Approved: AAA (from secret) + DDD (from configured) = 2
        assertEquals(2, data.approvedChats().size());
        List<String> approvedIds = data.approvedChats().stream().map(r -> r.get("chatId")).toList();
        assertTrue(approvedIds.contains("DDD"));
        assertTrue(approvedIds.contains("AAA"));

        // Request rows: BBB (discovered, not approved) + CCC (persisted metadata, not approved)
        assertEquals(2, data.chatRequests().size());
        List<String> requestIds = data.chatRequests().stream().map(r -> r.get("chatId")).toList();
        assertTrue(requestIds.contains("BBB"));
        assertTrue(requestIds.contains("CCC"));

        // Verify CCC has its persisted metadata
        Map<String, String> cccRow = data.chatRequests().stream()
                .filter(r -> "CCC".equals(r.get("chatId")))
                .findFirst().orElseThrow();
        assertEquals("supergroup", cccRow.get("chatType"));
        assertEquals("Persisted Chat", cccRow.get("chatTitle"));
        assertEquals("pchat", cccRow.get("chatUsername"));
    }

    // ── approvedChatIds ──

    @Test void approvedChatIdsMergesConfiguredAndSecretFile() {
        when(telegramSecretService.readSecret()).thenReturn(
                new TelegramSecret("tok", List.of("100", "200"), Map.of())
        );

        // Configured has "200" (duplicate) and "300"
        List<String> result = controller(List.of("200", "300")).approvedChatIds();

        assertEquals(3, result.size());
        // mergeChatIds puts primary (configured) first, then secondary (secret), deduplicating
        assertTrue(result.contains("200"));
        assertTrue(result.contains("300"));
        assertTrue(result.contains("100"));
    }
}
