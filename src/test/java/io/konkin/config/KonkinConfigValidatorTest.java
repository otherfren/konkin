package io.konkin.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class KonkinConfigValidatorTest {

    private static final CoinAuthConfig AUTH_WEBUI = new CoinAuthConfig(
            List.of(), List.of(), true, false, false, null, List.of(), 1, List.of());
    private static final CoinConfig DISABLED_COIN = new CoinConfig(false, null, null, AUTH_WEBUI);
    private static final AgentConfig VALID_AGENT = new AgentConfig(true, "127.0.0.1", 9090, "/tmp/secret.txt");

    private KonkinConfig base(boolean landingEnabled, boolean landingPwdProtection, String landingPwdFile,
                              boolean debugEnabled, boolean debugSeedFakeData,
                              boolean restApiEnabled, String restApiSecretFile,
                              boolean telegramEnabled, String telegramSecretFile, String telegramApiBaseUrl,
                              List<String> telegramChatIds,
                              AgentConfig primaryAgent, Map<String, AgentConfig> secondaryAgents,
                              CoinConfig bitcoin, CoinConfig litecoin, CoinConfig monero, CoinConfig testDummyCoin) {
        return new KonkinConfig(
                1, "localhost", 8080, "./secrets/", "INFO", "log.txt", 10,
                "jdbc:h2:mem:test", "sa", "", 5,
                landingEnabled, landingPwdProtection, landingPwdFile,
                "/tmp/templates", "/tmp/static", "/static",
                false, false,
                debugEnabled, debugSeedFakeData,
                restApiEnabled, restApiSecretFile,
                telegramEnabled, telegramSecretFile, telegramApiBaseUrl, Duration.ofMinutes(5), telegramChatIds,
                primaryAgent, secondaryAgents,
                bitcoin, litecoin, monero, testDummyCoin
        );
    }

    private KonkinConfig minimal() {
        return base(false, false, null, false, false, false, null,
                false, null, null, List.of(), null, Map.of(),
                DISABLED_COIN, DISABLED_COIN, DISABLED_COIN, DISABLED_COIN);
    }

    @Test void validMinimalConfigPasses() {
        assertDoesNotThrow(() -> KonkinConfigValidator.validate(minimal()));
    }

    @Test void logRotateZeroThrows() {
        KonkinConfig config = new KonkinConfig(
                1, "localhost", 8080, "./secrets/", "INFO", "log.txt", 0,
                "jdbc:h2:mem:test", "sa", "", 5,
                false, false, null,
                "/tmp/templates", "/tmp/static", "/static",
                false, false, false, false, false, null,
                false, null, null, Duration.ofMinutes(5), List.of(), null, Map.of(),
                DISABLED_COIN, DISABLED_COIN, DISABLED_COIN, DISABLED_COIN
        );
        assertThrows(IllegalStateException.class, () -> KonkinConfigValidator.validate(config));
    }

    @Test void debugSeedWithoutDebugThrows() {
        KonkinConfig config = base(false, false, null, false, true,
                false, null, false, null, null, List.of(), null, Map.of(),
                DISABLED_COIN, DISABLED_COIN, DISABLED_COIN, DISABLED_COIN);
        assertThrows(IllegalStateException.class, () -> KonkinConfigValidator.validate(config));
    }

    @Test void debugSeedWithDebugPasses() {
        KonkinConfig config = base(false, false, null, true, true,
                false, null, false, null, null, List.of(), null, Map.of(),
                DISABLED_COIN, DISABLED_COIN, DISABLED_COIN, DISABLED_COIN);
        assertDoesNotThrow(() -> KonkinConfigValidator.validate(config));
    }

    @Test void passwordProtectionWithoutLandingThrows() {
        KonkinConfig config = base(false, true, "/tmp/pw",
                false, false, false, null, false, null, null, List.of(), null, Map.of(),
                DISABLED_COIN, DISABLED_COIN, DISABLED_COIN, DISABLED_COIN);
        assertThrows(IllegalStateException.class, () -> KonkinConfigValidator.validate(config));
    }

    @Test void landingEnabledBadHostedPathThrows() {
        KonkinConfig config = new KonkinConfig(
                1, "localhost", 8080, "./secrets/", "INFO", "log.txt", 10,
                "jdbc:h2:mem:test", "sa", "", 5,
                true, false, null,
                "/tmp/templates", "/tmp/static", "noslash",
                false, false, false, false, false, null,
                false, null, null, Duration.ofMinutes(5), List.of(), null, Map.of(),
                DISABLED_COIN, DISABLED_COIN, DISABLED_COIN, DISABLED_COIN
        );
        assertThrows(IllegalStateException.class, () -> KonkinConfigValidator.validate(config));
    }

    @Test void landingPasswordProtectionMissingFileThrows() {
        KonkinConfig config = base(true, true, null,
                false, false, false, null, false, null, null, List.of(), null, Map.of(),
                DISABLED_COIN, DISABLED_COIN, DISABLED_COIN, DISABLED_COIN);
        assertThrows(IllegalStateException.class, () -> KonkinConfigValidator.validate(config));
    }

    @Test void landingPasswordProtectionWithFilePasses() {
        KonkinConfig config = base(true, true, "/tmp/pw.txt",
                false, false, false, null, false, null, null, List.of(), null, Map.of(),
                DISABLED_COIN, DISABLED_COIN, DISABLED_COIN, DISABLED_COIN);
        assertDoesNotThrow(() -> KonkinConfigValidator.validate(config));
    }

    @Test void restApiEnabledMissingSecretThrows() {
        KonkinConfig config = base(false, false, null,
                false, false, true, null, false, null, null, List.of(), null, Map.of(),
                DISABLED_COIN, DISABLED_COIN, DISABLED_COIN, DISABLED_COIN);
        assertThrows(IllegalStateException.class, () -> KonkinConfigValidator.validate(config));
    }

    @Test void restApiEnabledWithSecretPasses() {
        KonkinConfig config = base(false, false, null,
                false, false, true, "/tmp/api.secret", false, null, null, List.of(), null, Map.of(),
                DISABLED_COIN, DISABLED_COIN, DISABLED_COIN, DISABLED_COIN);
        assertDoesNotThrow(() -> KonkinConfigValidator.validate(config));
    }

    @Test void telegramEnabledMissingSecretThrows() {
        KonkinConfig config = base(false, false, null,
                false, false, false, null, true, null, "https://api.telegram.org", List.of("123"), null, Map.of(),
                DISABLED_COIN, DISABLED_COIN, DISABLED_COIN, DISABLED_COIN);
        assertThrows(IllegalStateException.class, () -> KonkinConfigValidator.validate(config));
    }

    @Test void telegramEnabledMissingApiBaseUrlThrows() {
        KonkinConfig config = base(false, false, null,
                false, false, false, null, true, "/tmp/tg.secret", null, List.of("123"), null, Map.of(),
                DISABLED_COIN, DISABLED_COIN, DISABLED_COIN, DISABLED_COIN);
        assertThrows(IllegalStateException.class, () -> KonkinConfigValidator.validate(config));
    }

    @Test void telegramEnabledBlankChatIdThrows() {
        KonkinConfig config = base(false, false, null,
                false, false, false, null, true, "/tmp/tg.secret", "https://api.telegram.org",
                List.of("  "), null, Map.of(),
                DISABLED_COIN, DISABLED_COIN, DISABLED_COIN, DISABLED_COIN);
        assertThrows(IllegalStateException.class, () -> KonkinConfigValidator.validate(config));
    }

    @Test void telegramEnabledValidPasses() {
        KonkinConfig config = base(false, false, null,
                false, false, false, null, true, "/tmp/tg.secret", "https://api.telegram.org",
                List.of("123"), null, Map.of(),
                DISABLED_COIN, DISABLED_COIN, DISABLED_COIN, DISABLED_COIN);
        assertDoesNotThrow(() -> KonkinConfigValidator.validate(config));
    }

    // ── Bitcoin validation ──

    @Test void bitcoinEnabledMissingDaemonSecretThrows() {
        CoinConfig btc = new CoinConfig(true, null, "/tmp/wallet.conf", AUTH_WEBUI);
        KonkinConfig config = base(true, false, null,
                false, false, false, null, false, null, null, List.of(), null, Map.of(),
                btc, DISABLED_COIN, DISABLED_COIN, DISABLED_COIN);
        assertThrows(IllegalStateException.class, () -> KonkinConfigValidator.validate(config));
    }

    @Test void bitcoinEnabledMissingWalletSecretThrows() {
        CoinConfig btc = new CoinConfig(true, "/tmp/daemon.conf", null, AUTH_WEBUI);
        KonkinConfig config = base(true, false, null,
                false, false, false, null, false, null, null, List.of(), null, Map.of(),
                btc, DISABLED_COIN, DISABLED_COIN, DISABLED_COIN);
        assertThrows(IllegalStateException.class, () -> KonkinConfigValidator.validate(config));
    }

    @Test void bitcoinNoAuthChannelsThrows() {
        CoinAuthConfig noAuth = new CoinAuthConfig(
                List.of(), List.of(), false, false, false, null, List.of(), 1, List.of());
        CoinConfig btc = new CoinConfig(true, "/tmp/d.conf", "/tmp/w.conf", noAuth);
        KonkinConfig config = base(true, false, null,
                false, false, false, null, false, null, null, List.of(), null, Map.of(),
                btc, DISABLED_COIN, DISABLED_COIN, DISABLED_COIN);
        assertThrows(IllegalStateException.class, () -> KonkinConfigValidator.validate(config));
    }

    @Test void bitcoinMinApprovalsZeroThrows() {
        CoinAuthConfig auth = new CoinAuthConfig(
                List.of(), List.of(), true, false, false, null, List.of(), 0, List.of());
        CoinConfig btc = new CoinConfig(true, "/tmp/d.conf", "/tmp/w.conf", auth);
        KonkinConfig config = base(true, false, null,
                false, false, false, null, false, null, null, List.of(), null, Map.of(),
                btc, DISABLED_COIN, DISABLED_COIN, DISABLED_COIN);
        assertThrows(IllegalStateException.class, () -> KonkinConfigValidator.validate(config));
    }

    @Test void bitcoinMinApprovalsExceedsChannelsThrows() {
        CoinAuthConfig auth = new CoinAuthConfig(
                List.of(), List.of(), true, false, false, null, List.of(), 5, List.of());
        CoinConfig btc = new CoinConfig(true, "/tmp/d.conf", "/tmp/w.conf", auth);
        KonkinConfig config = base(true, false, null,
                false, false, false, null, false, null, null, List.of(), null, Map.of(),
                btc, DISABLED_COIN, DISABLED_COIN, DISABLED_COIN);
        assertThrows(IllegalStateException.class, () -> KonkinConfigValidator.validate(config));
    }

    @Test void bitcoinValidPasses() {
        CoinConfig btc = new CoinConfig(true, "/tmp/d.conf", "/tmp/w.conf", AUTH_WEBUI);
        KonkinConfig config = base(true, false, null,
                false, false, false, null, false, null, null, List.of(), null, Map.of(),
                btc, DISABLED_COIN, DISABLED_COIN, DISABLED_COIN);
        assertDoesNotThrow(() -> KonkinConfigValidator.validate(config));
    }

    // ── Monero validation ──

    @Test void moneroEnabledMissingDaemonSecretThrows() {
        CoinConfig xmr = new CoinConfig(true, null, "/tmp/w.conf", AUTH_WEBUI);
        KonkinConfig config = base(true, false, null,
                false, false, false, null, false, null, null, List.of(), null, Map.of(),
                DISABLED_COIN, DISABLED_COIN, xmr, DISABLED_COIN);
        assertThrows(IllegalStateException.class, () -> KonkinConfigValidator.validate(config));
    }

    @Test void moneroEnabledMissingWalletSecretThrows() {
        CoinConfig xmr = new CoinConfig(true, "/tmp/d.conf", null, AUTH_WEBUI);
        KonkinConfig config = base(true, false, null,
                false, false, false, null, false, null, null, List.of(), null, Map.of(),
                DISABLED_COIN, DISABLED_COIN, xmr, DISABLED_COIN);
        assertThrows(IllegalStateException.class, () -> KonkinConfigValidator.validate(config));
    }

    @Test void moneroNoAuthChannelsThrows() {
        CoinAuthConfig noAuth = new CoinAuthConfig(
                List.of(), List.of(), false, false, false, null, List.of(), 1, List.of());
        CoinConfig xmr = new CoinConfig(true, "/tmp/d.conf", "/tmp/w.conf", noAuth);
        KonkinConfig config = base(true, false, null,
                false, false, false, null, false, null, null, List.of(), null, Map.of(),
                DISABLED_COIN, DISABLED_COIN, xmr, DISABLED_COIN);
        assertThrows(IllegalStateException.class, () -> KonkinConfigValidator.validate(config));
    }

    @Test void moneroValidPasses() {
        CoinConfig xmr = new CoinConfig(true, "/tmp/d.conf", "/tmp/w.conf", AUTH_WEBUI);
        KonkinConfig config = base(true, false, null,
                false, false, false, null, false, null, null, List.of(), null, Map.of(),
                DISABLED_COIN, DISABLED_COIN, xmr, DISABLED_COIN);
        assertDoesNotThrow(() -> KonkinConfigValidator.validate(config));
    }

    // ── Veto channels ──

    @Test void vetoChannelNotEnabledThrows() {
        CoinAuthConfig auth = new CoinAuthConfig(
                List.of(), List.of(), true, false, false, null, List.of(), 1, List.of("rest-api"));
        CoinConfig btc = new CoinConfig(true, "/tmp/d.conf", "/tmp/w.conf", auth);
        KonkinConfig config = base(true, false, null,
                false, false, false, null, false, null, null, List.of(), null, Map.of(),
                btc, DISABLED_COIN, DISABLED_COIN, DISABLED_COIN);
        assertThrows(IllegalStateException.class, () -> KonkinConfigValidator.validate(config));
    }

    @Test void vetoChannelBlankThrows() {
        CoinAuthConfig auth = new CoinAuthConfig(
                List.of(), List.of(), true, false, false, null, List.of(), 1, List.of("  "));
        CoinConfig btc = new CoinConfig(true, "/tmp/d.conf", "/tmp/w.conf", auth);
        KonkinConfig config = base(true, false, null,
                false, false, false, null, false, null, null, List.of(), null, Map.of(),
                btc, DISABLED_COIN, DISABLED_COIN, DISABLED_COIN);
        assertThrows(IllegalStateException.class, () -> KonkinConfigValidator.validate(config));
    }

    @Test void vetoChannelValidPasses() {
        CoinAuthConfig auth = new CoinAuthConfig(
                List.of(), List.of(), true, false, false, null, List.of(), 1, List.of("web-ui"));
        CoinConfig btc = new CoinConfig(true, "/tmp/d.conf", "/tmp/w.conf", auth);
        KonkinConfig config = base(true, false, null,
                false, false, false, null, false, null, null, List.of(), null, Map.of(),
                btc, DISABLED_COIN, DISABLED_COIN, DISABLED_COIN);
        assertDoesNotThrow(() -> KonkinConfigValidator.validate(config));
    }

    // ── Auto-accept/deny rules ──

    @Test void autoAcceptNullCriteriaThrows() {
        CoinAuthConfig auth = new CoinAuthConfig(
                List.of(new ApprovalRule(null)), List.of(), true, false, false, null, List.of(), 1, List.of());
        CoinConfig btc = new CoinConfig(true, "/tmp/d.conf", "/tmp/w.conf", auth);
        KonkinConfig config = base(true, false, null,
                false, false, false, null, false, null, null, List.of(), null, Map.of(),
                btc, DISABLED_COIN, DISABLED_COIN, DISABLED_COIN);
        assertThrows(IllegalStateException.class, () -> KonkinConfigValidator.validate(config));
    }

    @Test void autoAcceptZeroValueThrows() {
        ApprovalCriteria criteria = new ApprovalCriteria(CriteriaType.VALUE_GT, 0, null);
        CoinAuthConfig auth = new CoinAuthConfig(
                List.of(new ApprovalRule(criteria)), List.of(), true, false, false, null, List.of(), 1, List.of());
        CoinConfig btc = new CoinConfig(true, "/tmp/d.conf", "/tmp/w.conf", auth);
        KonkinConfig config = base(true, false, null,
                false, false, false, null, false, null, null, List.of(), null, Map.of(),
                btc, DISABLED_COIN, DISABLED_COIN, DISABLED_COIN);
        assertThrows(IllegalStateException.class, () -> KonkinConfigValidator.validate(config));
    }

    @Test void autoAcceptCumulatedMissingPeriodThrows() {
        ApprovalCriteria criteria = new ApprovalCriteria(CriteriaType.CUMULATED_VALUE_GT, 1.0, null);
        CoinAuthConfig auth = new CoinAuthConfig(
                List.of(new ApprovalRule(criteria)), List.of(), true, false, false, null, List.of(), 1, List.of());
        CoinConfig btc = new CoinConfig(true, "/tmp/d.conf", "/tmp/w.conf", auth);
        KonkinConfig config = base(true, false, null,
                false, false, false, null, false, null, null, List.of(), null, Map.of(),
                btc, DISABLED_COIN, DISABLED_COIN, DISABLED_COIN);
        assertThrows(IllegalStateException.class, () -> KonkinConfigValidator.validate(config));
    }

    @Test void autoAcceptCumulatedWithPeriodPasses() {
        ApprovalCriteria criteria = new ApprovalCriteria(CriteriaType.CUMULATED_VALUE_GT, 1.0, Duration.ofHours(24));
        CoinAuthConfig auth = new CoinAuthConfig(
                List.of(new ApprovalRule(criteria)), List.of(), true, false, false, null, List.of(), 1, List.of());
        CoinConfig btc = new CoinConfig(true, "/tmp/d.conf", "/tmp/w.conf", auth);
        KonkinConfig config = base(true, false, null,
                false, false, false, null, false, null, null, List.of(), null, Map.of(),
                btc, DISABLED_COIN, DISABLED_COIN, DISABLED_COIN);
        assertDoesNotThrow(() -> KonkinConfigValidator.validate(config));
    }

    @Test void autoDenyRuleValidatedToo() {
        ApprovalCriteria criteria = new ApprovalCriteria(CriteriaType.VALUE_GT, 0, null);
        CoinAuthConfig auth = new CoinAuthConfig(
                List.of(), List.of(new ApprovalRule(criteria)), true, false, false, null, List.of(), 1, List.of());
        CoinConfig btc = new CoinConfig(true, "/tmp/d.conf", "/tmp/w.conf", auth);
        KonkinConfig config = base(true, false, null,
                false, false, false, null, false, null, null, List.of(), null, Map.of(),
                btc, DISABLED_COIN, DISABLED_COIN, DISABLED_COIN);
        assertThrows(IllegalStateException.class, () -> KonkinConfigValidator.validate(config));
    }

    // ── Agent validation ──

    @Test void agentMissingBindThrows() {
        AgentConfig agent = new AgentConfig(true, null, 9090, "/tmp/secret");
        KonkinConfig config = base(false, false, null,
                false, false, false, null, false, null, null, List.of(), agent, Map.of(),
                DISABLED_COIN, DISABLED_COIN, DISABLED_COIN, DISABLED_COIN);
        assertThrows(IllegalStateException.class, () -> KonkinConfigValidator.validate(config));
    }

    @Test void agentInvalidPortThrows() {
        AgentConfig agent = new AgentConfig(true, "127.0.0.1", 0, "/tmp/secret");
        KonkinConfig config = base(false, false, null,
                false, false, false, null, false, null, null, List.of(), agent, Map.of(),
                DISABLED_COIN, DISABLED_COIN, DISABLED_COIN, DISABLED_COIN);
        assertThrows(IllegalStateException.class, () -> KonkinConfigValidator.validate(config));
    }

    @Test void agentMissingSecretFileThrows() {
        AgentConfig agent = new AgentConfig(true, "127.0.0.1", 9090, null);
        KonkinConfig config = base(false, false, null,
                false, false, false, null, false, null, null, List.of(), agent, Map.of(),
                DISABLED_COIN, DISABLED_COIN, DISABLED_COIN, DISABLED_COIN);
        assertThrows(IllegalStateException.class, () -> KonkinConfigValidator.validate(config));
    }

    @Test void duplicatePortsThrow() {
        AgentConfig agent = new AgentConfig(true, "127.0.0.1", 8080, "/tmp/secret");
        KonkinConfig config = base(false, false, null,
                false, false, false, null, false, null, null, List.of(), agent, Map.of(),
                DISABLED_COIN, DISABLED_COIN, DISABLED_COIN, DISABLED_COIN);
        assertThrows(IllegalStateException.class, () -> KonkinConfigValidator.validate(config));
    }

    @Test void secondaryAgentValidatedToo() {
        AgentConfig secondary = new AgentConfig(true, null, 9091, "/tmp/secret");
        KonkinConfig config = base(false, false, null,
                false, false, false, null, false, null, null, List.of(), null,
                Map.of("auth1", secondary),
                DISABLED_COIN, DISABLED_COIN, DISABLED_COIN, DISABLED_COIN);
        assertThrows(IllegalStateException.class, () -> KonkinConfigValidator.validate(config));
    }

    @Test void secondaryAgentDuplicatePortThrows() {
        AgentConfig primary = new AgentConfig(true, "127.0.0.1", 9090, "/tmp/secret1");
        AgentConfig secondary = new AgentConfig(true, "127.0.0.1", 9090, "/tmp/secret2");
        KonkinConfig config = base(false, false, null,
                false, false, false, null, false, null, null, List.of(), primary,
                Map.of("auth1", secondary),
                DISABLED_COIN, DISABLED_COIN, DISABLED_COIN, DISABLED_COIN);
        assertThrows(IllegalStateException.class, () -> KonkinConfigValidator.validate(config));
    }

    // ── MCP auth channel references ──

    @Test void mcpAuthChannelReferencesUndefinedAgentThrows() {
        CoinAuthConfig auth = new CoinAuthConfig(
                List.of(), List.of(), true, false, false, null, List.of("nonexistent"), 1, List.of());
        CoinConfig btc = new CoinConfig(true, "/tmp/d.conf", "/tmp/w.conf", auth);
        KonkinConfig config = base(true, false, null,
                false, false, false, null, false, null, null, List.of(), null, Map.of(),
                btc, DISABLED_COIN, DISABLED_COIN, DISABLED_COIN);
        assertThrows(IllegalStateException.class, () -> KonkinConfigValidator.validate(config));
    }

    @Test void mcpAuthChannelReferencesDefinedAgentPasses() {
        AgentConfig secondary = new AgentConfig(true, "127.0.0.1", 9091, "/tmp/secret");
        CoinAuthConfig auth = new CoinAuthConfig(
                List.of(), List.of(), false, false, false, null, List.of("auth1"), 1, List.of());
        CoinConfig btc = new CoinConfig(true, "/tmp/d.conf", "/tmp/w.conf", auth);
        KonkinConfig config = base(true, false, null,
                false, false, false, null, false, null, null, List.of(), null,
                Map.of("auth1", secondary),
                btc, DISABLED_COIN, DISABLED_COIN, DISABLED_COIN);
        assertDoesNotThrow(() -> KonkinConfigValidator.validate(config));
    }

    @Test void mcpLegacyFallbackSkipsValidation() {
        CoinAuthConfig auth = new CoinAuthConfig(
                List.of(), List.of(), true, false, false, "legacy", List.of("legacy"), 1, List.of());
        CoinConfig btc = new CoinConfig(true, "/tmp/d.conf", "/tmp/w.conf", auth);
        KonkinConfig config = base(true, false, null,
                false, false, false, null, false, null, null, List.of(), null, Map.of(),
                btc, DISABLED_COIN, DISABLED_COIN, DISABLED_COIN);
        assertDoesNotThrow(() -> KonkinConfigValidator.validate(config));
    }

    // ── Non-bitcoin coin (litecoin) ──

    @Test void litecoinNoAuthChannelsThrows() {
        CoinAuthConfig noAuth = new CoinAuthConfig(
                List.of(), List.of(), false, false, false, null, List.of(), 1, List.of());
        CoinConfig ltc = new CoinConfig(true, "/tmp/d.conf", "/tmp/w.conf", noAuth);
        KonkinConfig config = base(true, false, null,
                false, false, false, null, false, null, null, List.of(), null, Map.of(),
                DISABLED_COIN, ltc, DISABLED_COIN, DISABLED_COIN);
        assertThrows(IllegalStateException.class, () -> KonkinConfigValidator.validate(config));
    }

    @Test void litecoinMinApprovalsExceedsChannelsThrows() {
        CoinAuthConfig auth = new CoinAuthConfig(
                List.of(), List.of(), true, false, false, null, List.of(), 5, List.of());
        CoinConfig ltc = new CoinConfig(true, "/tmp/d.conf", "/tmp/w.conf", auth);
        KonkinConfig config = base(true, false, null,
                false, false, false, null, false, null, null, List.of(), null, Map.of(),
                DISABLED_COIN, ltc, DISABLED_COIN, DISABLED_COIN);
        assertThrows(IllegalStateException.class, () -> KonkinConfigValidator.validate(config));
    }
}
