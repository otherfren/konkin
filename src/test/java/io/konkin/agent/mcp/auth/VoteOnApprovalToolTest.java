package io.konkin.agent.mcp.auth;

import io.konkin.config.CoinAuthConfig;
import io.konkin.config.CoinConfig;
import io.konkin.config.KonkinConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VoteOnApprovalToolTest {

    private static final CoinAuthConfig AUTH_WITH_MCP = new CoinAuthConfig(
            List.of(), List.of(), true, false, false, null, List.of("auth1"), 1, List.of("web-ui"));
    private static final CoinAuthConfig AUTH_NO_MCP = new CoinAuthConfig(
            List.of(), List.of(), true, false, false, null, List.of(), 1, List.of());
    private static final CoinConfig ENABLED_WITH_MCP = new CoinConfig(true, "/d", "/w", AUTH_WITH_MCP);
    private static final CoinConfig ENABLED_NO_MCP = new CoinConfig(true, "/d", "/w", AUTH_NO_MCP);
    private static final CoinConfig DISABLED = new CoinConfig(false, null, null, AUTH_NO_MCP);

    private KonkinConfig mockConfig(CoinConfig btc, CoinConfig ltc, CoinConfig xmr, CoinConfig tdc) {
        KonkinConfig config = mock(KonkinConfig.class);
        when(config.bitcoin()).thenReturn(btc);
        when(config.litecoin()).thenReturn(ltc);
        when(config.monero()).thenReturn(xmr);
        when(config.testDummyCoin()).thenReturn(tdc);
        when(config.resolveCoinConfig(anyString())).thenCallRealMethod();
        return config;
    }

    // ── isAgentAssignedToCoin ──

    @Test void agentAssignedBitcoin() {
        KonkinConfig config = mockConfig(ENABLED_WITH_MCP, DISABLED, DISABLED, DISABLED);
        assertTrue(VoteOnApprovalTool.isAgentAssignedToCoin("auth1", "bitcoin", config));
    }

    @Test void agentNotAssignedBitcoin() {
        KonkinConfig config = mockConfig(ENABLED_WITH_MCP, DISABLED, DISABLED, DISABLED);
        assertFalse(VoteOnApprovalTool.isAgentAssignedToCoin("auth2", "bitcoin", config));
    }

    @Test void agentAssignedLitecoin() {
        KonkinConfig config = mockConfig(DISABLED, ENABLED_WITH_MCP, DISABLED, DISABLED);
        assertTrue(VoteOnApprovalTool.isAgentAssignedToCoin("auth1", "litecoin", config));
    }

    @Test void agentAssignedMonero() {
        KonkinConfig config = mockConfig(DISABLED, DISABLED, ENABLED_WITH_MCP, DISABLED);
        assertTrue(VoteOnApprovalTool.isAgentAssignedToCoin("auth1", "monero", config));
    }

    @Test void agentAssignedTestDummyCoin() {
        KonkinConfig config = mockConfig(DISABLED, DISABLED, DISABLED, ENABLED_WITH_MCP);
        assertTrue(VoteOnApprovalTool.isAgentAssignedToCoin("auth1", "testdummycoin", config));
    }

    @Test void agentAssignedUnknownCoin() {
        KonkinConfig config = mockConfig(DISABLED, DISABLED, DISABLED, DISABLED);
        assertFalse(VoteOnApprovalTool.isAgentAssignedToCoin("auth1", "dogecoin", config));
    }

    @Test void agentAssignedNullCoin() {
        KonkinConfig config = mockConfig(DISABLED, DISABLED, DISABLED, DISABLED);
        assertFalse(VoteOnApprovalTool.isAgentAssignedToCoin("auth1", null, config));
    }

    @Test void agentAssignedNullConfig() {
        assertFalse(VoteOnApprovalTool.isAgentAssignedToCoin("auth1", "bitcoin", null));
    }

    @Test void agentAssignedDisabledCoin() {
        KonkinConfig config = mockConfig(DISABLED, DISABLED, DISABLED, DISABLED);
        assertFalse(VoteOnApprovalTool.isAgentAssignedToCoin("auth1", "bitcoin", config));
    }

    @Test void agentAssignedEmptyCoin() {
        KonkinConfig config = mockConfig(DISABLED, DISABLED, DISABLED, DISABLED);
        assertFalse(VoteOnApprovalTool.isAgentAssignedToCoin("auth1", "  ", config));
    }

    // ── resolveVetoChannels ──

    @Test void resolveVetoChannelsBitcoin() {
        KonkinConfig config = mockConfig(ENABLED_WITH_MCP, DISABLED, DISABLED, DISABLED);
        assertEquals(List.of("web-ui"), VoteOnApprovalTool.resolveVetoChannels("bitcoin", config));
    }

    @Test void resolveVetoChannelsNoVeto() {
        KonkinConfig config = mockConfig(ENABLED_NO_MCP, DISABLED, DISABLED, DISABLED);
        assertTrue(VoteOnApprovalTool.resolveVetoChannels("bitcoin", config).isEmpty());
    }

    @Test void resolveVetoChannelsNullCoin() {
        KonkinConfig config = mockConfig(DISABLED, DISABLED, DISABLED, DISABLED);
        assertTrue(VoteOnApprovalTool.resolveVetoChannels(null, config).isEmpty());
    }

    @Test void resolveVetoChannelsNullConfig() {
        assertTrue(VoteOnApprovalTool.resolveVetoChannels("bitcoin", null).isEmpty());
    }

    @Test void resolveVetoChannelsUnknownCoin() {
        KonkinConfig config = mockConfig(DISABLED, DISABLED, DISABLED, DISABLED);
        assertTrue(VoteOnApprovalTool.resolveVetoChannels("dogecoin", config).isEmpty());
    }

    @Test void resolveVetoChannelsLitecoin() {
        KonkinConfig config = mockConfig(DISABLED, ENABLED_WITH_MCP, DISABLED, DISABLED);
        assertEquals(List.of("web-ui"), VoteOnApprovalTool.resolveVetoChannels("litecoin", config));
    }

    @Test void resolveVetoChannelsMonero() {
        KonkinConfig config = mockConfig(DISABLED, DISABLED, ENABLED_WITH_MCP, DISABLED);
        assertEquals(List.of("web-ui"), VoteOnApprovalTool.resolveVetoChannels("monero", config));
    }

    @Test void resolveVetoChannelsTestDummyCoin() {
        KonkinConfig config = mockConfig(DISABLED, DISABLED, DISABLED, ENABLED_WITH_MCP);
        assertEquals(List.of("web-ui"), VoteOnApprovalTool.resolveVetoChannels("testdummycoin", config));
    }
}
