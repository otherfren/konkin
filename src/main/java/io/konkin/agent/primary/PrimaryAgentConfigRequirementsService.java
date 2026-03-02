package io.konkin.agent.primary;

import io.konkin.agent.mcp.entity.McpDataContracts.RequirementItem;
import io.konkin.agent.mcp.entity.McpDataContracts.RuntimeConfigRequirementsResponse;
import io.konkin.config.AgentConfig;
import io.konkin.config.CoinAuthConfig;
import io.konkin.config.KonkinConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Evaluates runtime config readiness for driver-agent operations.
 */
public class PrimaryAgentConfigRequirementsService {

    private static final String STATUS_READY = "READY";
    private static final String STATUS_NOT_READY = "NOT_READY";

    private static final String CHECK_OK = "ok";
    private static final String CHECK_MISSING = "missing";
    private static final String CHECK_INVALID = "invalid";

    private static final String PLACEHOLDER_BITCOIN_RPC_USER = "REPLACE_WITH_BITCOIN_RPC_USER";
    private static final String PLACEHOLDER_BITCOIN_RPC_PASSWORD = "REPLACE_WITH_BITCOIN_RPC_PASSWORD";
    private static final String PLACEHOLDER_BITCOIN_WALLET_NAME = "REPLACE_WITH_BITCOIN_WALLET_NAME";
    private static final String PLACEHOLDER_BITCOIN_WALLET_PASSPHRASE = "REPLACE_WITH_BITCOIN_WALLET_PASSPHRASE";

    private final KonkinConfig config;

    public PrimaryAgentConfigRequirementsService(KonkinConfig config) {
        this.config = config;
    }

    public RuntimeConfigRequirementsResponse evaluate(String coin) {
        if (coin == null || coin.isBlank()) {
            return evaluateServerReadiness();
        }

        String normalizedCoin = coin.trim().toLowerCase(Locale.ROOT);
        return evaluateCoinReadiness(normalizedCoin);
    }

    private RuntimeConfigRequirementsResponse evaluateServerReadiness() {
        List<RequirementItem> checks = new ArrayList<>();
        checks.add(checkAnyCoinRuntimeConfigured());
        checks.add(checkAnyAuthChannelConfigured());

        List<RequirementItem> missing = checks.stream()
                .filter(item -> CHECK_MISSING.equals(item.status()))
                .toList();
        List<RequirementItem> invalid = checks.stream()
                .filter(item -> CHECK_INVALID.equals(item.status()))
                .toList();

        String status = (missing.isEmpty() && invalid.isEmpty()) ? STATUS_READY : STATUS_NOT_READY;
        String coinHint = isTestDummyRuntimeReadyForServer() ? "bitcoin or testdummycoin" : "bitcoin";
        String message = STATUS_READY.equals(status)
                ? "Server readiness passed. You can now call /runtime/config/requirements?coin=" + coinHint + " for coin-specific checks."
                : "Server is not ready yet. Configure at least one wallet-connected coin and at least one auth channel (web-ui/rest-api/telegram chat).";

        return new RuntimeConfigRequirementsResponse("server", status, message, List.copyOf(checks), missing, invalid);
    }

    private RuntimeConfigRequirementsResponse evaluateCoinReadiness(String normalizedCoin) {
        return switch (normalizedCoin) {
            case "bitcoin" -> evaluateBitcoinReadiness(normalizedCoin);
            case "testdummycoin" -> evaluateTestDummyCoinReadiness(normalizedCoin);
            default -> {
                RequirementItem unsupported = item(
                        "runtime.coin",
                        CHECK_INVALID,
                        "Coin '" + normalizedCoin + "' is not supported for readiness checks.",
                        "Use coin=bitcoin (or coin=testdummycoin when debug mode is enabled).",
                        true
                );
                yield new RuntimeConfigRequirementsResponse(
                        normalizedCoin,
                        STATUS_NOT_READY,
                        "Unsupported coin requested for readiness checks.",
                        List.of(unsupported),
                        List.of(),
                        List.of(unsupported)
                );
            }
        };
    }

    private RuntimeConfigRequirementsResponse evaluateBitcoinReadiness(String normalizedCoin) {
        List<RequirementItem> checks = new ArrayList<>();

        checks.add(checkPrimaryAgentEnabled());
        checks.add(checkPrimaryAgentEndpoint());
        checks.add(checkPrimaryAgentSecretFile());

        checks.add(checkBitcoinEnabled());
        checks.add(checkBitcoinDaemonSecret());
        checks.add(checkBitcoinWalletSecret());
        checks.add(checkBitcoinAuthCoherence());

        return toCoinReadinessResponse(
                normalizedCoin,
                checks,
                "Coin '" + normalizedCoin + "' is ready for runtime operations.",
                "Coin '" + normalizedCoin + "' is not ready for runtime operations."
        );
    }

    private RuntimeConfigRequirementsResponse evaluateTestDummyCoinReadiness(String normalizedCoin) {
        List<RequirementItem> checks = new ArrayList<>();

        checks.add(checkPrimaryAgentEnabled());
        checks.add(checkPrimaryAgentEndpoint());
        checks.add(checkPrimaryAgentSecretFile());

        checks.add(checkTestDummyCoinEnabled());
        checks.add(checkTestDummyCoinAuthCoherence());

        return toCoinReadinessResponse(
                normalizedCoin,
                checks,
                "Coin '" + normalizedCoin + "' is ready for runtime operations.",
                "Coin '" + normalizedCoin + "' is not ready for runtime operations."
        );
    }

    private RuntimeConfigRequirementsResponse toCoinReadinessResponse(
            String normalizedCoin,
            List<RequirementItem> checks,
            String readyMessage,
            String notReadyMessage
    ) {
        List<RequirementItem> missing = checks.stream()
                .filter(item -> CHECK_MISSING.equals(item.status()))
                .toList();
        List<RequirementItem> invalid = checks.stream()
                .filter(item -> CHECK_INVALID.equals(item.status()))
                .toList();

        String status = (missing.isEmpty() && invalid.isEmpty()) ? STATUS_READY : STATUS_NOT_READY;
        String message = STATUS_READY.equals(status) ? readyMessage : notReadyMessage;
        return new RuntimeConfigRequirementsResponse(normalizedCoin, status, message, List.copyOf(checks), missing, invalid);
    }

    private RequirementItem checkAnyCoinRuntimeConfigured() {
        if (isBitcoinRuntimeReadyForServer()) {
            return item(
                    "runtime.server.coins",
                    CHECK_OK,
                    "At least one wallet-connected coin is configured (bitcoin).",
                    "",
                    true
            );
        }

        if (isTestDummyRuntimeReadyForServer()) {
            return item(
                    "runtime.server.coins",
                    CHECK_OK,
                    "At least one runtime coin is configured via debug mode (testdummycoin).",
                    "",
                    true
            );
        }

        if (config.bitcoin().enabled()) {
            RequirementItem daemonCheck = checkBitcoinDaemonSecret();
            if (!CHECK_OK.equals(daemonCheck.status())) {
                return item(
                        "runtime.server.coins",
                        daemonCheck.status(),
                        "Bitcoin is enabled but not wallet-connected: " + daemonCheck.message(),
                        daemonCheck.hint(),
                        true
                );
            }

            RequirementItem walletCheck = checkBitcoinWalletSecret();
            if (!CHECK_OK.equals(walletCheck.status())) {
                return item(
                        "runtime.server.coins",
                        walletCheck.status(),
                        "Bitcoin is enabled but wallet setup is incomplete: " + walletCheck.message(),
                        walletCheck.hint(),
                        true
                );
            }
        }

        return item(
                "runtime.server.coins",
                CHECK_MISSING,
                "No wallet-connected coin is configured yet.",
                "Enable [coins.bitcoin].enabled=true with valid daemon/wallet secrets, or enable [debug].enabled=true and [coins.testdummycoin].enabled=true.",
                true
        );
    }

    private RequirementItem checkAnyAuthChannelConfigured() {
        List<String> channels = new ArrayList<>();

        if (config.landingEnabled()) {
            channels.add("web-ui");
        }
        if (config.restApiEnabled()) {
            channels.add("rest-api");
        }

        int telegramAgents = config.telegramChatIds() == null ? 0 : config.telegramChatIds().size();
        if (config.telegramEnabled() && telegramAgents > 0) {
            channels.add("telegram(" + telegramAgents + " chat agent(s))");
        }

        if (!channels.isEmpty()) {
            return item(
                    "runtime.server.auth-channels",
                    CHECK_OK,
                    "At least one auth channel is configured: " + String.join(", ", channels) + ".",
                    "",
                    true
            );
        }

        if (config.telegramEnabled() && telegramAgents == 0) {
            return item(
                    "runtime.server.auth-channels",
                    CHECK_INVALID,
                    "Telegram is enabled but no telegram chat agent is configured.",
                    "Set [telegram].chat-ids with at least one chat id, or enable web-ui/rest-api.",
                    true
            );
        }

        return item(
                "runtime.server.auth-channels",
                CHECK_MISSING,
                "No auth channel is configured yet.",
                "Enable at least one of [web-ui].enabled, [rest-api].enabled, or [telegram].enabled with [telegram].chat-ids.",
                true
        );
    }

    private boolean isBitcoinRuntimeReadyForServer() {
        if (!config.bitcoin().enabled()) {
            return false;
        }

        RequirementItem daemonCheck = checkBitcoinDaemonSecret();
        RequirementItem walletCheck = checkBitcoinWalletSecret();
        return CHECK_OK.equals(daemonCheck.status()) && CHECK_OK.equals(walletCheck.status());
    }

    private boolean isTestDummyRuntimeReadyForServer() {
        return config.debugEnabled() && config.testDummyCoin().enabled();
    }

    private RequirementItem checkPrimaryAgentEnabled() {
        AgentConfig primary = config.primaryAgent();
        if (primary == null || !primary.enabled()) {
            return item(
                    "agents.primary.enabled",
                    CHECK_MISSING,
                    "Driver agent endpoint is disabled or not configured.",
                    "Set [agents.primary].enabled=true in config.toml.",
                    true
            );
        }

        return item(
                "agents.primary.enabled",
                CHECK_OK,
                "Driver agent endpoint is enabled.",
                "",
                true
        );
    }

    private RequirementItem checkPrimaryAgentEndpoint() {
        AgentConfig primary = config.primaryAgent();
        if (primary == null || !primary.enabled()) {
            return item(
                    "agents.primary.endpoint",
                    CHECK_MISSING,
                    "Driver endpoint bind/port is unavailable because driver agent is disabled.",
                    "Enable [agents.primary] and provide bind/port.",
                    true
            );
        }

        if (primary.bind() == null || primary.bind().isBlank() || primary.port() <= 0) {
            return item(
                    "agents.primary.endpoint",
                    CHECK_INVALID,
                    "Driver endpoint bind/port is invalid.",
                    "Set non-empty bind and port > 0 under [agents.primary].",
                    true
            );
        }

        return item(
                "agents.primary.endpoint",
                CHECK_OK,
                "Driver endpoint bind/port is configured.",
                "",
                true
        );
    }

    private RequirementItem checkPrimaryAgentSecretFile() {
        AgentConfig primary = config.primaryAgent();
        if (primary == null || !primary.enabled()) {
            return item(
                    "agents.primary.secret-file",
                    CHECK_MISSING,
                    "Driver secret-file is unavailable because driver agent is disabled.",
                    "Enable [agents.primary] and configure secret-file.",
                    true
            );
        }

        Path secretPath = safePath(primary.secretFile());
        if (secretPath == null || !Files.exists(secretPath)) {
            return item(
                    "agents.primary.secret-file",
                    CHECK_MISSING,
                    "Driver agent secret file is missing.",
                    "Restart KONKIN once to auto-bootstrap or create the file manually.",
                    true
            );
        }

        Map<String, String> props = parseSimpleKeyValueFile(secretPath);
        String clientId = trimToEmpty(props.get("client-id"));
        String clientSecret = trimToEmpty(props.get("client-secret"));

        if (clientId.isEmpty() || clientSecret.isEmpty()) {
            return item(
                    "agents.primary.secret-file",
                    CHECK_INVALID,
                    "Driver agent secret file is missing client-id or client-secret values.",
                    "Set non-empty client-id and client-secret in the secret file.",
                    true
            );
        }

        return item(
                "agents.primary.secret-file",
                CHECK_OK,
                "Driver agent secret file looks valid.",
                "",
                true
        );
    }

    private RequirementItem checkBitcoinEnabled() {
        if (!config.bitcoin().enabled()) {
            return item(
                    "coins.bitcoin.enabled",
                    CHECK_MISSING,
                    "Bitcoin runtime is disabled.",
                    "Set [coins.bitcoin].enabled=true in config.toml.",
                    true
            );
        }

        return item(
                "coins.bitcoin.enabled",
                CHECK_OK,
                "Bitcoin runtime is enabled.",
                "",
                true
        );
    }

    private RequirementItem checkBitcoinDaemonSecret() {
        Path daemonSecret = safePath(config.bitcoin().bitcoinDaemonConfigSecretFile());
        if (daemonSecret == null || !Files.exists(daemonSecret)) {
            return item(
                    "coins.bitcoin.secret-files.bitcoin-daemon-config-file",
                    CHECK_MISSING,
                    "Bitcoin daemon secret file is missing.",
                    "Set a valid file path and ensure the file exists with rpcuser/rpcpassword values.",
                    true
            );
        }

        Map<String, String> kv = parseSimpleKeyValueFile(daemonSecret);
        String rpcUser = trimToEmpty(kv.get("rpcuser"));
        String rpcPassword = trimToEmpty(kv.get("rpcpassword"));

        if (rpcUser.isEmpty() || rpcPassword.isEmpty()) {
            return item(
                    "coins.bitcoin.secret-files.bitcoin-daemon-config-file",
                    CHECK_INVALID,
                    "Bitcoin daemon secret is missing rpcuser or rpcpassword.",
                    "Provide real rpcuser and rpcpassword values.",
                    true
            );
        }

        if (PLACEHOLDER_BITCOIN_RPC_USER.equals(rpcUser) || PLACEHOLDER_BITCOIN_RPC_PASSWORD.equals(rpcPassword)) {
            return item(
                    "coins.bitcoin.secret-files.bitcoin-daemon-config-file",
                    CHECK_INVALID,
                    "Bitcoin daemon secret still contains placeholder credentials.",
                    "Replace placeholder rpcuser/rpcpassword with real node credentials.",
                    true
            );
        }

        return item(
                "coins.bitcoin.secret-files.bitcoin-daemon-config-file",
                CHECK_OK,
                "Bitcoin daemon secret looks configured.",
                "",
                true
        );
    }

    private RequirementItem checkBitcoinWalletSecret() {
        Path walletSecret = safePath(config.bitcoin().bitcoinWalletConfigSecretFile());
        if (walletSecret == null || !Files.exists(walletSecret)) {
            return item(
                    "coins.bitcoin.secret-files.bitcoin-wallet-config-file",
                    CHECK_MISSING,
                    "Bitcoin wallet secret file is missing.",
                    "Set a valid file path and ensure wallet and wallet-passphrase are configured.",
                    true
            );
        }

        Map<String, String> kv = parseSimpleKeyValueFile(walletSecret);
        String wallet = trimToEmpty(kv.get("wallet"));
        String walletPassphrase = trimToEmpty(kv.get("wallet-passphrase"));

        if (wallet.isEmpty() || walletPassphrase.isEmpty()) {
            return item(
                    "coins.bitcoin.secret-files.bitcoin-wallet-config-file",
                    CHECK_INVALID,
                    "Bitcoin wallet secret is missing wallet or wallet-passphrase.",
                    "Provide real wallet and wallet-passphrase values.",
                    true
            );
        }

        if (PLACEHOLDER_BITCOIN_WALLET_NAME.equals(wallet) || PLACEHOLDER_BITCOIN_WALLET_PASSPHRASE.equals(walletPassphrase)) {
            return item(
                    "coins.bitcoin.secret-files.bitcoin-wallet-config-file",
                    CHECK_INVALID,
                    "Bitcoin wallet secret still contains placeholder values.",
                    "Replace placeholder wallet and wallet-passphrase with real values.",
                    true
            );
        }

        return item(
                "coins.bitcoin.secret-files.bitcoin-wallet-config-file",
                CHECK_OK,
                "Bitcoin wallet secret looks configured.",
                "",
                true
        );
    }

    private RequirementItem checkBitcoinAuthCoherence() {
        return checkCoinAuthCoherence("bitcoin", config.bitcoin().auth(), "Bitcoin");
    }

    private RequirementItem checkTestDummyCoinEnabled() {
        if (!config.debugEnabled()) {
            return item(
                    "coins.testdummycoin.enabled",
                    CHECK_MISSING,
                    "TestDummyCoin runtime requires debug mode and is unavailable while debug mode is disabled.",
                    "Set [debug].enabled=true and [coins.testdummycoin].enabled=true in config.toml.",
                    true
            );
        }

        if (!config.testDummyCoin().enabled()) {
            return item(
                    "coins.testdummycoin.enabled",
                    CHECK_MISSING,
                    "TestDummyCoin runtime is disabled.",
                    "Set [coins.testdummycoin].enabled=true in config.toml.",
                    true
            );
        }

        return item(
                "coins.testdummycoin.enabled",
                CHECK_OK,
                "TestDummyCoin runtime is enabled.",
                "",
                true
        );
    }

    private RequirementItem checkTestDummyCoinAuthCoherence() {
        return checkCoinAuthCoherence("testdummycoin", config.testDummyCoin().auth(), "TestDummyCoin");
    }

    private RequirementItem checkCoinAuthCoherence(String coinId, CoinAuthConfig auth, String coinLabel) {
        int configuredChannels = 0;
        if (auth.webUi()) {
            configuredChannels++;
        }
        if (auth.restApi()) {
            configuredChannels++;
        }
        if (auth.telegram()) {
            configuredChannels++;
        }
        if (auth.mcpAuthChannels() != null) {
            configuredChannels += auth.mcpAuthChannels().size();
        }

        if (configuredChannels <= 0) {
            return item(
                    "coins." + coinId + ".auth.channels",
                    CHECK_INVALID,
                    coinLabel + " auth has zero enabled channels.",
                    "Enable at least one of web-ui/rest-api/telegram/mcp-auth-channels.",
                    true
            );
        }

        if (auth.minApprovalsRequired() <= 0) {
            return item(
                    "coins." + coinId + ".auth.min-approvals-required",
                    CHECK_INVALID,
                    "min-approvals-required must be > 0.",
                    "Set [coins." + coinId + ".auth].min-approvals-required to a positive value.",
                    true
            );
        }

        if (auth.minApprovalsRequired() > configuredChannels) {
            return item(
                    "coins." + coinId + ".auth.min-approvals-required",
                    CHECK_INVALID,
                    "min-approvals-required exceeds number of configured auth channels.",
                    "Lower min-approvals-required or enable additional channels.",
                    true
            );
        }

        if (auth.webUi() && !config.landingEnabled()) {
            return item(
                    "coins." + coinId + ".auth.web-ui",
                    CHECK_INVALID,
                    coinLabel + " auth enables web-ui but web-ui is disabled globally.",
                    "Enable [web-ui].enabled or disable [coins." + coinId + ".auth].web-ui.",
                    true
            );
        }

        if (auth.restApi() && !config.restApiEnabled()) {
            return item(
                    "coins." + coinId + ".auth.rest-api",
                    CHECK_INVALID,
                    coinLabel + " auth enables rest-api but rest-api is disabled globally.",
                    "Enable [rest-api].enabled or disable [coins." + coinId + ".auth].rest-api.",
                    true
            );
        }

        if (auth.telegram() && !config.telegramEnabled()) {
            return item(
                    "coins." + coinId + ".auth.telegram",
                    CHECK_INVALID,
                    coinLabel + " auth enables telegram but telegram is disabled globally.",
                    "Enable [telegram].enabled or disable [coins." + coinId + ".auth].telegram.",
                    true
            );
        }

        return item(
                "coins." + coinId + ".auth",
                CHECK_OK,
                coinLabel + " auth channel configuration is coherent.",
                "",
                true
        );
    }

    private static RequirementItem item(String key, String status, String message, String hint, boolean blocking) {
        return new RequirementItem(key, status, message, hint, blocking);
    }

    private static Path safePath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return null;
        }
        try {
            return Path.of(rawPath);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Map<String, String> parseSimpleKeyValueFile(Path path) {
        Map<String, String> values = new LinkedHashMap<>();
        List<String> lines;
        try {
            lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return values;
        }

        for (String line : lines) {
            if (line == null) {
                continue;
            }
            String candidate = line.trim();
            if (candidate.isEmpty() || candidate.startsWith("#")) {
                continue;
            }

            int separator = candidate.indexOf('=');
            if (separator <= 0) {
                continue;
            }

            String key = candidate.substring(0, separator).trim().toLowerCase(Locale.ROOT);
            String value = candidate.substring(separator + 1).trim();
            values.put(key, value);
        }

        return values;
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
