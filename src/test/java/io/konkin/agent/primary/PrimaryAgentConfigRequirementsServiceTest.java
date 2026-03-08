package io.konkin.agent.primary;

import io.konkin.config.AgentConfig;
import io.konkin.config.CoinAuthConfig;
import io.konkin.config.CoinConfig;
import io.konkin.config.KonkinConfig;
import io.konkin.agent.mcp.entity.McpDataContracts.RuntimeConfigRequirementsResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PrimaryAgentConfigRequirementsServiceTest {

    @TempDir Path tempDir;

    private static final CoinAuthConfig AUTH_WEBUI = new CoinAuthConfig(
            List.of(), List.of(), true, false, false, null, List.of(), 1, List.of());
    private static final CoinAuthConfig AUTH_NONE = new CoinAuthConfig(
            List.of(), List.of(), false, false, false, null, List.of(), 1, List.of());
    private static final CoinConfig DISABLED_COIN = new CoinConfig(false, null, null, AUTH_WEBUI);

    private KonkinConfig mockConfig() {
        KonkinConfig config = mock(KonkinConfig.class);
        when(config.bitcoin()).thenReturn(DISABLED_COIN);
        when(config.litecoin()).thenReturn(DISABLED_COIN);
        when(config.monero()).thenReturn(DISABLED_COIN);
        when(config.testDummyCoin()).thenReturn(DISABLED_COIN);
        when(config.debugEnabled()).thenReturn(false);
        when(config.landingEnabled()).thenReturn(false);
        when(config.restApiEnabled()).thenReturn(false);
        when(config.telegramEnabled()).thenReturn(false);
        when(config.telegramChatIds()).thenReturn(List.of());
        when(config.primaryAgent()).thenReturn(null);
        return config;
    }

    // ── Server readiness (null/blank coin) ──

    @Test void serverReadinessNoCoinNoChannels() {
        KonkinConfig config = mockConfig();
        var service = new PrimaryAgentConfigRequirementsService(config);
        RuntimeConfigRequirementsResponse resp = service.evaluate(null);
        assertEquals("server", resp.coin());
        assertEquals("NOT_READY", resp.status());
        assertFalse(resp.missing().isEmpty());
    }

    @Test void serverReadinessBlankCoin() {
        KonkinConfig config = mockConfig();
        var service = new PrimaryAgentConfigRequirementsService(config);
        RuntimeConfigRequirementsResponse resp = service.evaluate("  ");
        assertEquals("server", resp.coin());
    }

    @Test void serverReadinessWithWebUiChannel() {
        KonkinConfig config = mockConfig();
        when(config.landingEnabled()).thenReturn(true);
        var service = new PrimaryAgentConfigRequirementsService(config);
        RuntimeConfigRequirementsResponse resp = service.evaluate(null);
        // Still NOT_READY because no coin is configured
        assertEquals("NOT_READY", resp.status());
    }

    @Test void serverReadinessWithTelegramChannel() {
        KonkinConfig config = mockConfig();
        when(config.telegramEnabled()).thenReturn(true);
        when(config.telegramChatIds()).thenReturn(List.of("123"));
        var service = new PrimaryAgentConfigRequirementsService(config);
        RuntimeConfigRequirementsResponse resp = service.evaluate(null);
        assertEquals("NOT_READY", resp.status());
    }

    @Test void serverReadinessTelegramNoChatIds() {
        KonkinConfig config = mockConfig();
        when(config.telegramEnabled()).thenReturn(true);
        when(config.telegramChatIds()).thenReturn(List.of());
        when(config.landingEnabled()).thenReturn(false);
        when(config.restApiEnabled()).thenReturn(false);
        var service = new PrimaryAgentConfigRequirementsService(config);
        RuntimeConfigRequirementsResponse resp = service.evaluate(null);
        assertTrue(resp.invalid().stream().anyMatch(i -> i.message().contains("Telegram is enabled but no telegram chat agent")));
    }

    @Test void serverReadinessWithTestDummyCoin() {
        KonkinConfig config = mockConfig();
        when(config.debugEnabled()).thenReturn(true);
        CoinConfig testCoin = new CoinConfig(true, null, null, AUTH_WEBUI);
        when(config.testDummyCoin()).thenReturn(testCoin);
        when(config.landingEnabled()).thenReturn(true);
        var service = new PrimaryAgentConfigRequirementsService(config);
        RuntimeConfigRequirementsResponse resp = service.evaluate(null);
        assertEquals("READY", resp.status());
    }

    @Test void serverReadinessWithBitcoin() throws IOException {
        KonkinConfig config = mockConfig();
        Path daemonFile = tempDir.resolve("daemon.conf");
        Files.writeString(daemonFile, "rpcuser=user\nrpcpassword=pass\n");
        Path walletFile = tempDir.resolve("wallet.conf");
        Files.writeString(walletFile, "wallet=mywallet\nwallet-passphrase=secret\n");
        CoinConfig btc = new CoinConfig(true, daemonFile.toString(), walletFile.toString(), AUTH_WEBUI);
        when(config.bitcoin()).thenReturn(btc);
        when(config.landingEnabled()).thenReturn(true);
        var service = new PrimaryAgentConfigRequirementsService(config);
        RuntimeConfigRequirementsResponse resp = service.evaluate(null);
        assertEquals("READY", resp.status());
    }

    @Test void serverReadinessBitcoinEnabledMissingDaemon() {
        KonkinConfig config = mockConfig();
        CoinConfig btc = new CoinConfig(true, "/nonexistent/daemon.conf", "/nonexistent/wallet.conf", AUTH_WEBUI);
        when(config.bitcoin()).thenReturn(btc);
        when(config.landingEnabled()).thenReturn(true);
        var service = new PrimaryAgentConfigRequirementsService(config);
        RuntimeConfigRequirementsResponse resp = service.evaluate(null);
        assertEquals("NOT_READY", resp.status());
    }

    @Test void serverReadinessBitcoinDaemonMissingCredentials() throws IOException {
        KonkinConfig config = mockConfig();
        Path daemonFile = tempDir.resolve("daemon.conf");
        Files.writeString(daemonFile, "rpcuser=\nrpcpassword=\n");
        CoinConfig btc = new CoinConfig(true, daemonFile.toString(), "/nonexistent/wallet.conf", AUTH_WEBUI);
        when(config.bitcoin()).thenReturn(btc);
        when(config.landingEnabled()).thenReturn(true);
        var service = new PrimaryAgentConfigRequirementsService(config);
        RuntimeConfigRequirementsResponse resp = service.evaluate(null);
        assertEquals("NOT_READY", resp.status());
    }

    @Test void serverReadinessBitcoinWalletMissing() throws IOException {
        KonkinConfig config = mockConfig();
        Path daemonFile = tempDir.resolve("daemon.conf");
        Files.writeString(daemonFile, "rpcuser=user\nrpcpassword=pass\n");
        CoinConfig btc = new CoinConfig(true, daemonFile.toString(), "/nonexistent/wallet.conf", AUTH_WEBUI);
        when(config.bitcoin()).thenReturn(btc);
        when(config.landingEnabled()).thenReturn(true);
        var service = new PrimaryAgentConfigRequirementsService(config);
        RuntimeConfigRequirementsResponse resp = service.evaluate(null);
        assertEquals("NOT_READY", resp.status());
    }

    // ── Coin-specific: bitcoin ──

    @Test void bitcoinReadinessDisabled() {
        KonkinConfig config = mockConfig();
        var service = new PrimaryAgentConfigRequirementsService(config);
        RuntimeConfigRequirementsResponse resp = service.evaluate("bitcoin");
        assertEquals("bitcoin", resp.coin());
        assertEquals("NOT_READY", resp.status());
    }

    @Test void bitcoinReadinessReady() throws IOException {
        KonkinConfig config = mockConfig();
        AgentConfig agent = new AgentConfig(true, "127.0.0.1", 9090, tempDir.resolve("agent.secret").toString());
        Files.writeString(tempDir.resolve("agent.secret"), "client-id=id1\nclient-secret=sec1\n");
        when(config.primaryAgent()).thenReturn(agent);
        Path daemonFile = tempDir.resolve("daemon.conf");
        Files.writeString(daemonFile, "rpcuser=user\nrpcpassword=pass\n");
        Path walletFile = tempDir.resolve("wallet.conf");
        Files.writeString(walletFile, "wallet=mywallet\nwallet-passphrase=secret\n");
        CoinConfig btc = new CoinConfig(true, daemonFile.toString(), walletFile.toString(), AUTH_WEBUI);
        when(config.bitcoin()).thenReturn(btc);
        when(config.landingEnabled()).thenReturn(true);
        var service = new PrimaryAgentConfigRequirementsService(config);
        RuntimeConfigRequirementsResponse resp = service.evaluate("bitcoin");
        assertEquals("READY", resp.status());
    }

    @Test void bitcoinPlaceholderCredentials() throws IOException {
        KonkinConfig config = mockConfig();
        AgentConfig agent = new AgentConfig(true, "127.0.0.1", 9090, tempDir.resolve("agent.secret").toString());
        Files.writeString(tempDir.resolve("agent.secret"), "client-id=id1\nclient-secret=sec1\n");
        when(config.primaryAgent()).thenReturn(agent);
        Path daemonFile = tempDir.resolve("daemon.conf");
        Files.writeString(daemonFile, "rpcuser=REPLACE_WITH_BITCOIN_RPC_USER\nrpcpassword=pass\n");
        Path walletFile = tempDir.resolve("wallet.conf");
        Files.writeString(walletFile, "wallet=mywallet\nwallet-passphrase=secret\n");
        CoinConfig btc = new CoinConfig(true, daemonFile.toString(), walletFile.toString(), AUTH_WEBUI);
        when(config.bitcoin()).thenReturn(btc);
        when(config.landingEnabled()).thenReturn(true);
        var service = new PrimaryAgentConfigRequirementsService(config);
        RuntimeConfigRequirementsResponse resp = service.evaluate("bitcoin");
        assertEquals("NOT_READY", resp.status());
    }

    @Test void bitcoinWalletPlaceholder() throws IOException {
        KonkinConfig config = mockConfig();
        AgentConfig agent = new AgentConfig(true, "127.0.0.1", 9090, tempDir.resolve("agent.secret").toString());
        Files.writeString(tempDir.resolve("agent.secret"), "client-id=id1\nclient-secret=sec1\n");
        when(config.primaryAgent()).thenReturn(agent);
        Path daemonFile = tempDir.resolve("daemon.conf");
        Files.writeString(daemonFile, "rpcuser=user\nrpcpassword=pass\n");
        Path walletFile = tempDir.resolve("wallet.conf");
        Files.writeString(walletFile, "wallet=REPLACE_WITH_BITCOIN_WALLET_NAME\nwallet-passphrase=secret\n");
        CoinConfig btc = new CoinConfig(true, daemonFile.toString(), walletFile.toString(), AUTH_WEBUI);
        when(config.bitcoin()).thenReturn(btc);
        when(config.landingEnabled()).thenReturn(true);
        var service = new PrimaryAgentConfigRequirementsService(config);
        RuntimeConfigRequirementsResponse resp = service.evaluate("bitcoin");
        assertEquals("NOT_READY", resp.status());
    }

    // ── Coin-specific: testdummycoin ──

    @Test void testDummyCoinDebugDisabled() {
        KonkinConfig config = mockConfig();
        var service = new PrimaryAgentConfigRequirementsService(config);
        RuntimeConfigRequirementsResponse resp = service.evaluate("testdummycoin");
        assertEquals("NOT_READY", resp.status());
    }

    @Test void testDummyCoinDebugEnabledCoinDisabled() {
        KonkinConfig config = mockConfig();
        when(config.debugEnabled()).thenReturn(true);
        var service = new PrimaryAgentConfigRequirementsService(config);
        RuntimeConfigRequirementsResponse resp = service.evaluate("testdummycoin");
        assertEquals("NOT_READY", resp.status());
    }

    @Test void testDummyCoinReady() throws IOException {
        KonkinConfig config = mockConfig();
        when(config.debugEnabled()).thenReturn(true);
        CoinConfig testCoin = new CoinConfig(true, null, null, AUTH_WEBUI);
        when(config.testDummyCoin()).thenReturn(testCoin);
        when(config.landingEnabled()).thenReturn(true);
        AgentConfig agent = new AgentConfig(true, "127.0.0.1", 9090, tempDir.resolve("agent.secret").toString());
        Files.writeString(tempDir.resolve("agent.secret"), "client-id=id1\nclient-secret=sec1\n");
        when(config.primaryAgent()).thenReturn(agent);
        var service = new PrimaryAgentConfigRequirementsService(config);
        RuntimeConfigRequirementsResponse resp = service.evaluate("testdummycoin");
        assertEquals("READY", resp.status());
    }

    // ── Unsupported coin ──

    @Test void unsupportedCoin() {
        KonkinConfig config = mockConfig();
        var service = new PrimaryAgentConfigRequirementsService(config);
        RuntimeConfigRequirementsResponse resp = service.evaluate("dogecoin");
        assertEquals("NOT_READY", resp.status());
        assertFalse(resp.invalid().isEmpty());
    }

    // ── Agent checks ──

    @Test void primaryAgentDisabled() {
        KonkinConfig config = mockConfig();
        var service = new PrimaryAgentConfigRequirementsService(config);
        RuntimeConfigRequirementsResponse resp = service.evaluate("bitcoin");
        assertTrue(resp.missing().stream().anyMatch(i -> i.key().contains("agents.primary")));
    }

    @Test void primaryAgentInvalidEndpoint() {
        KonkinConfig config = mockConfig();
        AgentConfig agent = new AgentConfig(true, "", 0, "/tmp/secret");
        when(config.primaryAgent()).thenReturn(agent);
        var service = new PrimaryAgentConfigRequirementsService(config);
        RuntimeConfigRequirementsResponse resp = service.evaluate("bitcoin");
        assertTrue(resp.invalid().stream().anyMatch(i -> i.key().contains("agents.primary.endpoint")));
    }

    @Test void primaryAgentSecretFileMissing() {
        KonkinConfig config = mockConfig();
        AgentConfig agent = new AgentConfig(true, "127.0.0.1", 9090, "/nonexistent/secret");
        when(config.primaryAgent()).thenReturn(agent);
        var service = new PrimaryAgentConfigRequirementsService(config);
        RuntimeConfigRequirementsResponse resp = service.evaluate("bitcoin");
        assertTrue(resp.missing().stream().anyMatch(i -> i.key().contains("agents.primary.secret-file")));
    }

    @Test void primaryAgentSecretFileInvalidContent() throws IOException {
        KonkinConfig config = mockConfig();
        Path secretFile = tempDir.resolve("agent.secret");
        Files.writeString(secretFile, "client-id=\nclient-secret=\n");
        AgentConfig agent = new AgentConfig(true, "127.0.0.1", 9090, secretFile.toString());
        when(config.primaryAgent()).thenReturn(agent);
        var service = new PrimaryAgentConfigRequirementsService(config);
        RuntimeConfigRequirementsResponse resp = service.evaluate("bitcoin");
        assertTrue(resp.invalid().stream().anyMatch(i -> i.key().contains("agents.primary.secret-file")));
    }

    // ── Auth coherence ──

    @Test void bitcoinAuthNoChannels() throws IOException {
        KonkinConfig config = mockConfig();
        AgentConfig agent = new AgentConfig(true, "127.0.0.1", 9090, tempDir.resolve("agent.secret").toString());
        Files.writeString(tempDir.resolve("agent.secret"), "client-id=id1\nclient-secret=sec1\n");
        when(config.primaryAgent()).thenReturn(agent);
        Path daemonFile = tempDir.resolve("daemon.conf");
        Files.writeString(daemonFile, "rpcuser=user\nrpcpassword=pass\n");
        Path walletFile = tempDir.resolve("wallet.conf");
        Files.writeString(walletFile, "wallet=mywallet\nwallet-passphrase=secret\n");
        CoinConfig btc = new CoinConfig(true, daemonFile.toString(), walletFile.toString(), AUTH_NONE);
        when(config.bitcoin()).thenReturn(btc);
        var service = new PrimaryAgentConfigRequirementsService(config);
        RuntimeConfigRequirementsResponse resp = service.evaluate("bitcoin");
        assertEquals("NOT_READY", resp.status());
    }

    @Test void bitcoinAuthWebUiDisabledGlobally() throws IOException {
        KonkinConfig config = mockConfig();
        AgentConfig agent = new AgentConfig(true, "127.0.0.1", 9090, tempDir.resolve("agent.secret").toString());
        Files.writeString(tempDir.resolve("agent.secret"), "client-id=id1\nclient-secret=sec1\n");
        when(config.primaryAgent()).thenReturn(agent);
        Path daemonFile = tempDir.resolve("daemon.conf");
        Files.writeString(daemonFile, "rpcuser=user\nrpcpassword=pass\n");
        Path walletFile = tempDir.resolve("wallet.conf");
        Files.writeString(walletFile, "wallet=mywallet\nwallet-passphrase=secret\n");
        CoinConfig btc = new CoinConfig(true, daemonFile.toString(), walletFile.toString(), AUTH_WEBUI);
        when(config.bitcoin()).thenReturn(btc);
        when(config.landingEnabled()).thenReturn(false);
        var service = new PrimaryAgentConfigRequirementsService(config);
        RuntimeConfigRequirementsResponse resp = service.evaluate("bitcoin");
        assertEquals("NOT_READY", resp.status());
    }

    @Test void bitcoinAuthRestApiDisabledGlobally() throws IOException {
        KonkinConfig config = mockConfig();
        AgentConfig agent = new AgentConfig(true, "127.0.0.1", 9090, tempDir.resolve("agent.secret").toString());
        Files.writeString(tempDir.resolve("agent.secret"), "client-id=id1\nclient-secret=sec1\n");
        when(config.primaryAgent()).thenReturn(agent);
        Path daemonFile = tempDir.resolve("daemon.conf");
        Files.writeString(daemonFile, "rpcuser=user\nrpcpassword=pass\n");
        Path walletFile = tempDir.resolve("wallet.conf");
        Files.writeString(walletFile, "wallet=mywallet\nwallet-passphrase=secret\n");
        CoinAuthConfig authRestApi = new CoinAuthConfig(
                List.of(), List.of(), false, true, false, null, List.of(), 1, List.of());
        CoinConfig btc = new CoinConfig(true, daemonFile.toString(), walletFile.toString(), authRestApi);
        when(config.bitcoin()).thenReturn(btc);
        when(config.restApiEnabled()).thenReturn(false);
        var service = new PrimaryAgentConfigRequirementsService(config);
        RuntimeConfigRequirementsResponse resp = service.evaluate("bitcoin");
        assertEquals("NOT_READY", resp.status());
    }

    @Test void bitcoinAuthTelegramDisabledGlobally() throws IOException {
        KonkinConfig config = mockConfig();
        AgentConfig agent = new AgentConfig(true, "127.0.0.1", 9090, tempDir.resolve("agent.secret").toString());
        Files.writeString(tempDir.resolve("agent.secret"), "client-id=id1\nclient-secret=sec1\n");
        when(config.primaryAgent()).thenReturn(agent);
        Path daemonFile = tempDir.resolve("daemon.conf");
        Files.writeString(daemonFile, "rpcuser=user\nrpcpassword=pass\n");
        Path walletFile = tempDir.resolve("wallet.conf");
        Files.writeString(walletFile, "wallet=mywallet\nwallet-passphrase=secret\n");
        CoinAuthConfig authTg = new CoinAuthConfig(
                List.of(), List.of(), false, false, true, null, List.of(), 1, List.of());
        CoinConfig btc = new CoinConfig(true, daemonFile.toString(), walletFile.toString(), authTg);
        when(config.bitcoin()).thenReturn(btc);
        when(config.telegramEnabled()).thenReturn(false);
        var service = new PrimaryAgentConfigRequirementsService(config);
        RuntimeConfigRequirementsResponse resp = service.evaluate("bitcoin");
        assertEquals("NOT_READY", resp.status());
    }

    @Test void bitcoinAuthMinApprovalsExceedsChannels() throws IOException {
        KonkinConfig config = mockConfig();
        AgentConfig agent = new AgentConfig(true, "127.0.0.1", 9090, tempDir.resolve("agent.secret").toString());
        Files.writeString(tempDir.resolve("agent.secret"), "client-id=id1\nclient-secret=sec1\n");
        when(config.primaryAgent()).thenReturn(agent);
        Path daemonFile = tempDir.resolve("daemon.conf");
        Files.writeString(daemonFile, "rpcuser=user\nrpcpassword=pass\n");
        Path walletFile = tempDir.resolve("wallet.conf");
        Files.writeString(walletFile, "wallet=mywallet\nwallet-passphrase=secret\n");
        CoinAuthConfig auth = new CoinAuthConfig(
                List.of(), List.of(), true, false, false, null, List.of(), 5, List.of());
        CoinConfig btc = new CoinConfig(true, daemonFile.toString(), walletFile.toString(), auth);
        when(config.bitcoin()).thenReturn(btc);
        when(config.landingEnabled()).thenReturn(true);
        var service = new PrimaryAgentConfigRequirementsService(config);
        RuntimeConfigRequirementsResponse resp = service.evaluate("bitcoin");
        assertEquals("NOT_READY", resp.status());
    }
}
