package io.konkin.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class KonkinConfigLoaderTest {

    @TempDir Path tempDir;

    private KonkinConfig loadToml(String tomlContent) throws IOException {
        Path configFile = tempDir.resolve("config.toml");
        Files.writeString(configFile, tomlContent);
        return KonkinConfig.load(configFile.toString());
    }

    private String baseToml() {
        return """
                config-version = 1
                [server]
                host = "127.0.0.1"
                port = 7070
                """;
    }

    @Test void loadsMinimalConfig() throws IOException {
        KonkinConfig config = loadToml(baseToml());
        assertEquals("127.0.0.1", config.host());
        assertEquals(7070, config.port());
        assertFalse(config.landingEnabled());
    }

    @Test void missingConfigVersionThrows() {
        assertThrows(IllegalStateException.class, () -> loadToml("[server]\nhost = \"127.0.0.1\"\n"));
    }

    @Test void wrongConfigVersionThrows() {
        assertThrows(IllegalStateException.class, () -> loadToml("config-version = 999\n[server]\nhost = \"127.0.0.1\"\n"));
    }

    @Test void webUiSection() throws IOException {
        KonkinConfig config = loadToml(baseToml() + """
                [web-ui]
                enabled = true
                [web-ui.password-protection]
                enabled = true
                password-file = "/tmp/pw.txt"
                [web-ui.template]
                directory = "/tmp/templates"
                [web-ui.static]
                directory = "/tmp/static"
                hosted-path = "/assets"
                """);
        assertTrue(config.landingEnabled());
        assertTrue(config.landingPasswordProtectionEnabled());
        assertEquals("/tmp/pw.txt", config.landingPasswordFile());
    }

    @Test void legacyLandingSectionFallback() throws IOException {
        KonkinConfig config = loadToml(baseToml() + """
                [landing]
                enabled = true
                [landing.password-protection]
                enabled = false
                password-file = "/tmp/pw.txt"
                [landing.template]
                directory = "/tmp/templates"
                [landing.static]
                directory = "/tmp/static"
                hosted-path = "/assets"
                """);
        assertTrue(config.landingEnabled());
    }

    @Test void debugSection() throws IOException {
        KonkinConfig config = loadToml(baseToml() + """
                [debug]
                enabled = true
                seed-fake-data = true
                """);
        assertTrue(config.debugEnabled());
        assertTrue(config.debugSeedFakeData());
    }

    @Test void restApiSection() throws IOException {
        KonkinConfig config = loadToml(baseToml() + """
                [rest-api]
                enabled = true
                secret-file = "/tmp/api.secret"
                """);
        assertTrue(config.restApiEnabled());
        assertEquals("/tmp/api.secret", config.restApiSecretFile());
    }

    @Test void telegramSection() throws IOException {
        KonkinConfig config = loadToml(baseToml() + """
                [telegram]
                enabled = true
                secret-file = "/tmp/tg.secret"
                api-base-url = "https://api.telegram.org"
                chat-ids = ["111", "222"]
                auto-deny-timeout = "10m"
                """);
        assertTrue(config.telegramEnabled());
        assertEquals(2, config.telegramChatIds().size());
        assertEquals(Duration.ofMinutes(10), config.telegramAutoDenyTimeout());
    }

    @Test void telegramDefaultAutoDenyTimeout() throws IOException {
        KonkinConfig config = loadToml(baseToml() + """
                [telegram]
                enabled = true
                secret-file = "/tmp/tg.secret"
                """);
        assertEquals(Duration.ofMinutes(5), config.telegramAutoDenyTimeout());
    }

    @Test void bitcoinConfig() throws IOException {
        KonkinConfig config = loadToml(baseToml() + """
                [web-ui]
                enabled = true
                [coins.bitcoin]
                enabled = true
                [coins.bitcoin.secret-files]
                bitcoin-daemon-config-file = "/tmp/d.conf"
                bitcoin-wallet-config-file = "/tmp/w.conf"
                [coins.bitcoin.auth]
                web-ui = true
                rest-api = false
                telegram = false
                min-approvals-required = 1
                """);
        assertTrue(config.bitcoin().enabled());
        assertEquals("/tmp/d.conf", config.bitcoin().daemonConfigSecretFile());
        assertTrue(config.bitcoin().auth().webUi());
        assertFalse(config.bitcoin().auth().restApi());
        assertEquals(1, config.bitcoin().auth().minApprovalsRequired());
    }

    @Test void bitcoinAutoAcceptRules() throws IOException {
        KonkinConfig config = loadToml(baseToml() + """
                [web-ui]
                enabled = true
                [coins.bitcoin]
                enabled = true
                [coins.bitcoin.secret-files]
                bitcoin-daemon-config-file = "/tmp/d.conf"
                bitcoin-wallet-config-file = "/tmp/w.conf"
                [coins.bitcoin.auth]
                web-ui = true
                rest-api = false
                telegram = false
                [[coins.bitcoin.auth.auto-accept]]
                type = "value-gt"
                value = 0.01
                """);
        assertEquals(1, config.bitcoin().auth().autoAccept().size());
        assertEquals(CriteriaType.VALUE_GT, config.bitcoin().auth().autoAccept().getFirst().criteria().type());
        assertEquals(0.01, config.bitcoin().auth().autoAccept().getFirst().criteria().value(), 0.001);
    }

    @Test void bitcoinAutoDenyWithPeriod() throws IOException {
        KonkinConfig config = loadToml(baseToml() + """
                [web-ui]
                enabled = true
                [coins.bitcoin]
                enabled = true
                [coins.bitcoin.secret-files]
                bitcoin-daemon-config-file = "/tmp/d.conf"
                bitcoin-wallet-config-file = "/tmp/w.conf"
                [coins.bitcoin.auth]
                web-ui = true
                rest-api = false
                telegram = false
                [[coins.bitcoin.auth.auto-deny]]
                type = "cumulated-value-gt"
                value = 1.0
                period = "24h"
                """);
        assertEquals(1, config.bitcoin().auth().autoDeny().size());
        assertEquals(CriteriaType.CUMULATED_VALUE_GT, config.bitcoin().auth().autoDeny().getFirst().criteria().type());
        assertEquals(Duration.ofHours(24), config.bitcoin().auth().autoDeny().getFirst().criteria().period());
    }

    @Test void bitcoinVetoChannels() throws IOException {
        KonkinConfig config = loadToml(baseToml() + """
                [web-ui]
                enabled = true
                [coins.bitcoin]
                enabled = true
                [coins.bitcoin.secret-files]
                bitcoin-daemon-config-file = "/tmp/d.conf"
                bitcoin-wallet-config-file = "/tmp/w.conf"
                [coins.bitcoin.auth]
                web-ui = true
                rest-api = false
                telegram = false
                veto-channels = ["web-ui"]
                """);
        assertEquals(1, config.bitcoin().auth().vetoChannels().size());
        assertEquals("web-ui", config.bitcoin().auth().vetoChannels().getFirst());
    }

    @Test void moneroConfig() throws IOException {
        KonkinConfig config = loadToml(baseToml() + """
                [web-ui]
                enabled = true
                [coins.monero]
                enabled = true
                [coins.monero.secret-files]
                monero-daemon-config-file = "/tmp/d.conf"
                monero-wallet-rpc-config-file = "/tmp/w.conf"
                [coins.monero.auth]
                web-ui = true
                rest-api = false
                telegram = false
                """);
        assertTrue(config.monero().enabled());
    }

    @Test void testDummyCoinDisabledWithoutDebug() throws IOException {
        KonkinConfig config = loadToml(baseToml());
        assertFalse(config.testDummyCoin().enabled());
    }

    @Test void testDummyCoinWithDebug() throws IOException {
        KonkinConfig config = loadToml(baseToml() + """
                [debug]
                enabled = true
                [web-ui]
                enabled = true
                [coins.testdummycoin]
                enabled = true
                [coins.testdummycoin.auth]
                web-ui = true
                rest-api = false
                telegram = false
                """);
        assertTrue(config.testDummyCoin().enabled());
    }

    @Test void primaryAgentConfig() throws IOException {
        KonkinConfig config = loadToml(baseToml() + """
                [agents.primary]
                enabled = true
                bind = "0.0.0.0"
                port = 9550
                secret-file = "/tmp/agent.secret"
                """);
        assertNotNull(config.primaryAgent());
        assertTrue(config.primaryAgent().enabled());
        assertEquals("0.0.0.0", config.primaryAgent().bind());
        assertEquals(9550, config.primaryAgent().port());
    }

    @Test void secondaryAgentConfig() throws IOException {
        KonkinConfig config = loadToml(baseToml() + """
                [agents.secondary.auth1]
                enabled = true
                bind = "127.0.0.1"
                port = 9551
                secret-file = "/tmp/auth1.secret"
                """);
        assertEquals(1, config.secondaryAgents().size());
        assertTrue(config.secondaryAgents().containsKey("auth1"));
    }

    @Test void mcpAuthChannelsExplicit() throws IOException {
        KonkinConfig config = loadToml(baseToml() + """
                [web-ui]
                enabled = true
                [agents.secondary.auth1]
                enabled = true
                bind = "127.0.0.1"
                port = 9551
                secret-file = "/tmp/auth1.secret"
                [coins.bitcoin]
                enabled = true
                [coins.bitcoin.secret-files]
                bitcoin-daemon-config-file = "/tmp/d.conf"
                bitcoin-wallet-config-file = "/tmp/w.conf"
                [coins.bitcoin.auth]
                web-ui = true
                rest-api = false
                telegram = false
                mcp-auth-channels = ["auth1"]
                """);
        assertEquals(List.of("auth1"), config.bitcoin().auth().mcpAuthChannels());
    }

    @Test void litecoinConfig() throws IOException {
        KonkinConfig config = loadToml(baseToml() + """
                [web-ui]
                enabled = true
                [coins.litecoin]
                enabled = true
                [coins.litecoin.auth]
                web-ui = true
                rest-api = false
                telegram = false
                """);
        assertTrue(config.litecoin().enabled());
    }
}
