package io.konkin.agent.primary;

import io.konkin.agent.mcp.entity.McpDataContracts.RequirementItem;
import io.konkin.agent.mcp.entity.McpDataContracts.RuntimeConfigRequirementsResponse;
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
        String normalizedCoin = normalizeCoin(coin);
        if (!"bitcoin".equals(normalizedCoin)) {
            RequirementItem unsupported = item(
                    "runtime.coin",
                    CHECK_INVALID,
                    "Coin '" + normalizedCoin + "' is not supported in phase 1.",
                    "Use coin=bitcoin for phase 1 readiness checks.",
                    true
            );
            return new RuntimeConfigRequirementsResponse(
                    normalizedCoin,
                    STATUS_NOT_READY,
                    List.of(unsupported),
                    List.of(),
                    List.of(unsupported)
            );
        }

        List<RequirementItem> checks = new ArrayList<>();

        checks.add(checkPrimaryAgentEnabled());
        checks.add(checkPrimaryAgentEndpoint());
        checks.add(checkPrimaryAgentSecretFile());

        checks.add(checkBitcoinEnabled());
        checks.add(checkBitcoinDaemonSecret());
        checks.add(checkBitcoinWalletSecret());
        checks.add(checkBitcoinAuthCoherence());

        List<RequirementItem> missing = checks.stream()
                .filter(item -> CHECK_MISSING.equals(item.status()))
                .toList();
        List<RequirementItem> invalid = checks.stream()
                .filter(item -> CHECK_INVALID.equals(item.status()))
                .toList();

        String status = (missing.isEmpty() && invalid.isEmpty()) ? STATUS_READY : STATUS_NOT_READY;
        return new RuntimeConfigRequirementsResponse(normalizedCoin, status, List.copyOf(checks), missing, invalid);
    }

    private RequirementItem checkPrimaryAgentEnabled() {
        KonkinConfig.AgentConfig primary = config.primaryAgent();
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
        KonkinConfig.AgentConfig primary = config.primaryAgent();
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
        KonkinConfig.AgentConfig primary = config.primaryAgent();
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
        KonkinConfig.CoinAuthConfig auth = config.bitcoin().auth();
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
                    "coins.bitcoin.auth.channels",
                    CHECK_INVALID,
                    "Bitcoin auth has zero enabled channels.",
                    "Enable at least one of web-ui/rest-api/telegram/mcp-auth-channels.",
                    true
            );
        }

        if (auth.minApprovalsRequired() <= 0) {
            return item(
                    "coins.bitcoin.auth.min-approvals-required",
                    CHECK_INVALID,
                    "min-approvals-required must be > 0.",
                    "Set [coins.bitcoin.auth].min-approvals-required to a positive value.",
                    true
            );
        }

        if (auth.minApprovalsRequired() > configuredChannels) {
            return item(
                    "coins.bitcoin.auth.min-approvals-required",
                    CHECK_INVALID,
                    "min-approvals-required exceeds number of configured auth channels.",
                    "Lower min-approvals-required or enable additional channels.",
                    true
            );
        }

        if (auth.webUi() && !config.landingEnabled()) {
            return item(
                    "coins.bitcoin.auth.web-ui",
                    CHECK_INVALID,
                    "Bitcoin auth enables web-ui but web-ui is disabled globally.",
                    "Enable [web-ui].enabled or disable [coins.bitcoin.auth].web-ui.",
                    true
            );
        }

        if (auth.restApi() && !config.restApiEnabled()) {
            return item(
                    "coins.bitcoin.auth.rest-api",
                    CHECK_INVALID,
                    "Bitcoin auth enables rest-api but rest-api is disabled globally.",
                    "Enable [rest-api].enabled or disable [coins.bitcoin.auth].rest-api.",
                    true
            );
        }

        if (auth.telegram() && !config.telegramEnabled()) {
            return item(
                    "coins.bitcoin.auth.telegram",
                    CHECK_INVALID,
                    "Bitcoin auth enables telegram but telegram is disabled globally.",
                    "Enable [telegram].enabled or disable [coins.bitcoin.auth].telegram.",
                    true
            );
        }

        return item(
                "coins.bitcoin.auth",
                CHECK_OK,
                "Bitcoin auth channel configuration is coherent.",
                "",
                true
        );
    }

    private static String normalizeCoin(String coin) {
        if (coin == null || coin.isBlank()) {
            return "bitcoin";
        }
        return coin.trim().toLowerCase(Locale.ROOT);
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
