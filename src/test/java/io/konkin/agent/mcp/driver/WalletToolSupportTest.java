package io.konkin.agent.mcp.driver;

import io.konkin.config.CoinConfig;
import io.konkin.config.KonkinConfig;
import io.konkin.crypto.*;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WalletToolSupportTest {

    // --- errorResult ---

    @Test
    void errorResult_returnsIsErrorTrue() {
        CallToolResult result = WalletToolSupport.errorResult("bad_input", "Missing field");
        assertTrue(result.isError());
        String text = ((TextContent) result.content().getFirst()).text();
        assertTrue(text.contains("bad_input"));
        assertTrue(text.contains("Missing field"));
    }

    // --- walletError ---

    @Test
    void walletError_connectionException() {
        WalletConnectionException ex = new WalletConnectionException("Cannot connect");
        CallToolResult result = WalletToolSupport.walletError(ex);
        assertTrue(result.isError());
        String text = ((TextContent) result.content().getFirst()).text();
        assertTrue(text.contains("wallet_offline"));
    }

    @Test
    void walletError_insufficientFundsException() {
        WalletInsufficientFundsException ex = new WalletInsufficientFundsException(
                new BigDecimal("10"), new BigDecimal("5"));
        CallToolResult result = WalletToolSupport.walletError(ex);
        assertTrue(result.isError());
        String text = ((TextContent) result.content().getFirst()).text();
        assertTrue(text.contains("insufficient_funds"));
        assertTrue(text.contains("10"));
        assertTrue(text.contains("5"));
    }

    @Test
    void walletError_genericWalletOperationException() {
        WalletOperationException ex = new WalletOperationException("Some op error");
        CallToolResult result = WalletToolSupport.walletError(ex);
        assertTrue(result.isError());
        String text = ((TextContent) result.content().getFirst()).text();
        assertTrue(text.contains("wallet_error"));
    }

    // --- unexpectedError ---

    @Test
    void unexpectedError_returnsInternalError() {
        CallToolResult result = WalletToolSupport.unexpectedError("testTool", new RuntimeException("boom"));
        assertTrue(result.isError());
        String text = ((TextContent) result.content().getFirst()).text();
        assertTrue(text.contains("internal_error"));
        assertTrue(text.contains("testTool"));
    }

    // --- jsonResult ---

    @Test
    void jsonResult_serializesObject() {
        CallToolResult result = WalletToolSupport.jsonResult(Map.of("key", "value"));
        assertFalse(result.isError());
        String text = ((TextContent) result.content().getFirst()).text();
        assertTrue(text.contains("\"key\""));
        assertTrue(text.contains("\"value\""));
    }

    // --- validateCoinEnabled ---

    @Test
    void validateCoinEnabled_nullCoin() {
        KonkinConfig config = mock(KonkinConfig.class);
        CallToolResult result = WalletToolSupport.validateCoinEnabled(config, null);
        assertNotNull(result);
        assertTrue(result.isError());
        String text = ((TextContent) result.content().getFirst()).text();
        assertTrue(text.contains("invalid_input"));
    }

    @Test
    void validateCoinEnabled_blankCoin() {
        KonkinConfig config = mock(KonkinConfig.class);
        CallToolResult result = WalletToolSupport.validateCoinEnabled(config, "  ");
        assertNotNull(result);
        assertTrue(result.isError());
    }

    @Test
    void validateCoinEnabled_bitcoinEnabled() {
        KonkinConfig config = mock(KonkinConfig.class);
        CoinConfig btcConfig = mock(CoinConfig.class);
        when(config.bitcoin()).thenReturn(btcConfig);
        when(btcConfig.enabled()).thenReturn(true);

        assertNull(WalletToolSupport.validateCoinEnabled(config, "bitcoin"));
    }

    @Test
    void validateCoinEnabled_bitcoinDisabled() {
        KonkinConfig config = mock(KonkinConfig.class);
        CoinConfig btcConfig = mock(CoinConfig.class);
        when(config.bitcoin()).thenReturn(btcConfig);
        when(btcConfig.enabled()).thenReturn(false);

        CallToolResult result = WalletToolSupport.validateCoinEnabled(config, "bitcoin");
        assertNotNull(result);
        assertTrue(result.isError());
        String text = ((TextContent) result.content().getFirst()).text();
        assertTrue(text.contains("coin_not_enabled"));
    }

    @Test
    void validateCoinEnabled_moneroEnabled() {
        KonkinConfig config = mock(KonkinConfig.class);
        CoinConfig xmrConfig = mock(CoinConfig.class);
        when(config.monero()).thenReturn(xmrConfig);
        when(xmrConfig.enabled()).thenReturn(true);

        assertNull(WalletToolSupport.validateCoinEnabled(config, "monero"));
    }

    @Test
    void validateCoinEnabled_unsupportedCoin() {
        KonkinConfig config = mock(KonkinConfig.class);
        CallToolResult result = WalletToolSupport.validateCoinEnabled(config, "dogecoin");
        assertNotNull(result);
        assertTrue(result.isError());
        String text = ((TextContent) result.content().getFirst()).text();
        assertTrue(text.contains("unsupported_coin"));
    }

    @Test
    void validateCoinEnabled_caseInsensitive() {
        KonkinConfig config = mock(KonkinConfig.class);
        CoinConfig btcConfig = mock(CoinConfig.class);
        when(config.bitcoin()).thenReturn(btcConfig);
        when(btcConfig.enabled()).thenReturn(true);

        assertNull(WalletToolSupport.validateCoinEnabled(config, " Bitcoin "));
    }

    // --- resolveCoin ---

    @Test
    void resolveCoin_bitcoin() {
        assertEquals(Coin.BTC, WalletToolSupport.resolveCoin("bitcoin"));
        assertEquals(Coin.BTC, WalletToolSupport.resolveCoin(" Bitcoin "));
    }

    @Test
    void resolveCoin_monero() {
        assertEquals(Coin.XMR, WalletToolSupport.resolveCoin("monero"));
    }

    @Test
    void resolveCoin_unknown_returnsNull() {
        assertNull(WalletToolSupport.resolveCoin("litecoin"));
    }

    // --- lookupSupervisor ---

    @Test
    void lookupSupervisor_found() {
        WalletSupervisor supervisor = mock(WalletSupervisor.class);
        Map<Coin, WalletSupervisor> supervisors = Map.of(Coin.BTC, supervisor);

        assertSame(supervisor, WalletToolSupport.lookupSupervisor(supervisors, Coin.BTC));
    }

    @Test
    void lookupSupervisor_notFound() {
        assertNull(WalletToolSupport.lookupSupervisor(Map.of(), Coin.BTC));
    }

    // --- argString ---

    @Test
    void argString_returnsValue() {
        Map<String, Object> args = Map.of("coin", "bitcoin", "amount", 42);
        assertEquals("bitcoin", WalletToolSupport.argString(args, "coin"));
        assertEquals("42", WalletToolSupport.argString(args, "amount"));
    }

    @Test
    void argString_missingKey_returnsNull() {
        assertNull(WalletToolSupport.argString(Map.of(), "missing"));
    }

    @Test
    void argString_nullArgs_returnsNull() {
        assertNull(WalletToolSupport.argString(null, "key"));
    }

    // --- toJson ---

    @Test
    void toJson_serializesMap() {
        String json = WalletToolSupport.toJson(Map.of("status", "ok"));
        assertTrue(json.contains("\"status\""));
        assertTrue(json.contains("\"ok\""));
    }
}
