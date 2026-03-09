package io.konkin.web.service;

import io.konkin.config.CoinAuthConfig;
import io.konkin.config.CoinConfig;
import io.konkin.config.KonkinConfig;
import io.konkin.db.entity.ApprovalRequestRow;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.*;

class TelegramApprovalNotifierTest {

    private static final CoinAuthConfig AUTH_TG = new CoinAuthConfig(
            List.of(), List.of(), false, false, true, null, List.of(), 1, List.of());
    private static final CoinAuthConfig AUTH_NO_TG = new CoinAuthConfig(
            List.of(), List.of(), true, false, false, null, List.of(), 1, List.of());

    private static KonkinConfig mockConfig() {
        KonkinConfig config = mock(KonkinConfig.class);
        when(config.resolveCoinConfig(anyString())).thenCallRealMethod();
        return config;
    }

    private ApprovalRequestRow row(String coin) {
        return new ApprovalRequestRow(
                "req-1", coin, "send", "sess-1", "nonce-1", "hash-1", "composite-1",
                "addr1", "0.5", "normal", null, "test memo", "test reason",
                Instant.now(), Instant.now().plusSeconds(3600),
                "PENDING", null, null,
                1, 0, 0, null,
                Instant.now(), Instant.now(), null
        );
    }

    @Test void notifiesWhenTelegramEnabled() {
        KonkinConfig config = mockConfig();
        CoinConfig btc = new CoinConfig(true, "/d", "/w", AUTH_TG);
        when(config.bitcoin()).thenReturn(btc);

        TelegramService tgService = mock(TelegramService.class);
        when(tgService.sendApprovalPrompt(anyString(), anyString()))
                .thenReturn(new TelegramService.SendResult(true, "ok"));

        var notifier = new TelegramApprovalNotifier(tgService, config);
        notifier.notifyIfTelegramEnabled(row("bitcoin"));

        verify(tgService).sendApprovalPrompt(anyString(), eq("req-1"));
    }

    @Test void skipsWhenTelegramDisabledForCoin() {
        KonkinConfig config = mockConfig();
        CoinConfig btc = new CoinConfig(true, "/d", "/w", AUTH_NO_TG);
        when(config.bitcoin()).thenReturn(btc);

        TelegramService tgService = mock(TelegramService.class);
        var notifier = new TelegramApprovalNotifier(tgService, config);
        notifier.notifyIfTelegramEnabled(row("bitcoin"));

        verifyNoInteractions(tgService);
    }

    @Test void skipsWhenCoinNotFound() {
        KonkinConfig config = mockConfig();
        TelegramService tgService = mock(TelegramService.class);
        var notifier = new TelegramApprovalNotifier(tgService, config);
        notifier.notifyIfTelegramEnabled(row("unknowncoin"));

        verifyNoInteractions(tgService);
    }

    @Test void skipsWhenCoinNull() {
        KonkinConfig config = mockConfig();
        TelegramService tgService = mock(TelegramService.class);
        var notifier = new TelegramApprovalNotifier(tgService, config);
        notifier.notifyIfTelegramEnabled(row(null));

        verifyNoInteractions(tgService);
    }

    @Test void handlesLitecoin() {
        KonkinConfig config = mockConfig();
        CoinConfig ltc = new CoinConfig(true, "/d", "/w", AUTH_TG);
        when(config.litecoin()).thenReturn(ltc);

        TelegramService tgService = mock(TelegramService.class);
        when(tgService.sendApprovalPrompt(anyString(), anyString()))
                .thenReturn(new TelegramService.SendResult(true, "ok"));

        var notifier = new TelegramApprovalNotifier(tgService, config);
        notifier.notifyIfTelegramEnabled(row("litecoin"));

        verify(tgService).sendApprovalPrompt(anyString(), eq("req-1"));
    }

    @Test void handlesMonero() {
        KonkinConfig config = mockConfig();
        CoinConfig xmr = new CoinConfig(true, "/d", "/w", AUTH_TG);
        when(config.monero()).thenReturn(xmr);

        TelegramService tgService = mock(TelegramService.class);
        when(tgService.sendApprovalPrompt(anyString(), anyString()))
                .thenReturn(new TelegramService.SendResult(true, "ok"));

        var notifier = new TelegramApprovalNotifier(tgService, config);
        notifier.notifyIfTelegramEnabled(row("monero"));

        verify(tgService).sendApprovalPrompt(anyString(), eq("req-1"));
    }

    @Test void handlesTestDummyCoin() {
        KonkinConfig config = mockConfig();
        CoinConfig test = new CoinConfig(true, null, null, AUTH_TG);
        when(config.testDummyCoin()).thenReturn(test);

        TelegramService tgService = mock(TelegramService.class);
        when(tgService.sendApprovalPrompt(anyString(), anyString()))
                .thenReturn(new TelegramService.SendResult(true, "ok"));

        var notifier = new TelegramApprovalNotifier(tgService, config);
        notifier.notifyIfTelegramEnabled(row("testdummycoin"));

        verify(tgService).sendApprovalPrompt(anyString(), eq("req-1"));
    }

    @Test void handlesSendFailure() {
        KonkinConfig config = mockConfig();
        CoinConfig btc = new CoinConfig(true, "/d", "/w", AUTH_TG);
        when(config.bitcoin()).thenReturn(btc);

        TelegramService tgService = mock(TelegramService.class);
        when(tgService.sendApprovalPrompt(anyString(), anyString()))
                .thenReturn(new TelegramService.SendResult(false, "error"));

        var notifier = new TelegramApprovalNotifier(tgService, config);
        // Should not throw
        notifier.notifyIfTelegramEnabled(row("bitcoin"));
    }

    @Test void handlesSendException() {
        KonkinConfig config = mockConfig();
        CoinConfig btc = new CoinConfig(true, "/d", "/w", AUTH_TG);
        when(config.bitcoin()).thenReturn(btc);

        TelegramService tgService = mock(TelegramService.class);
        when(tgService.sendApprovalPrompt(anyString(), anyString()))
                .thenThrow(new RuntimeException("boom"));

        var notifier = new TelegramApprovalNotifier(tgService, config);
        // Should not throw
        notifier.notifyIfTelegramEnabled(row("bitcoin"));
    }

    @Test void messageIncludesFeePolicyAndCap() {
        KonkinConfig config = mockConfig();
        CoinConfig btc = new CoinConfig(true, "/d", "/w", AUTH_TG);
        when(config.bitcoin()).thenReturn(btc);

        TelegramService tgService = mock(TelegramService.class);
        when(tgService.sendApprovalPrompt(anyString(), anyString()))
                .thenReturn(new TelegramService.SendResult(true, "ok"));

        var notifier = new TelegramApprovalNotifier(tgService, config);
        ApprovalRequestRow r = new ApprovalRequestRow(
                "req-2", "bitcoin", "send", "sess-2", "nonce-2", "hash-2", "composite-2",
                "addr2", "1.0", "normal", "0.001", null, null,
                Instant.now(), Instant.now().plusSeconds(3600),
                "PENDING", null, null,
                1, 0, 0, null,
                Instant.now(), Instant.now(), null
        );
        notifier.notifyIfTelegramEnabled(r);

        verify(tgService).sendApprovalPrompt(argThat(msg ->
                msg.contains("Fee policy: normal") && msg.contains("Fee cap: 0.001")), eq("req-2"));
    }

    @Test void nullAuthConfig() {
        KonkinConfig config = mockConfig();
        CoinConfig btc = new CoinConfig(true, "/d", "/w", null);
        when(config.bitcoin()).thenReturn(btc);

        TelegramService tgService = mock(TelegramService.class);
        var notifier = new TelegramApprovalNotifier(tgService, config);
        notifier.notifyIfTelegramEnabled(row("bitcoin"));

        verifyNoInteractions(tgService);
    }
}
