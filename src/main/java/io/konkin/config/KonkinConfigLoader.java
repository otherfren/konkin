/*
 * Copyright 2026 Peter Geschel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.konkin.config;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.file.FileConfig;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static io.konkin.config.ConfigParsingUtils.*;

/**
 * Parses a TOML file into a fully populated {@link KonkinConfig} instance.
 */
final class KonkinConfigLoader {

    static final int EXPECTED_CONFIG_VERSION = 1;
    private static final String PRIMARY_AGENT_CLIENT_ID = "konkin-primary";

    private KonkinConfigLoader() {
    }

    static KonkinConfig load(FileConfig toml) {
        int configVersion = toml.getIntOrElse("config-version", -1);
        String host = toml.getOrElse("server.host", "127.0.0.1");
        int port = toml.getIntOrElse("server.port", 7070);
        String secretsDir = normalizeSecretsDir(toml.getOrElse("server.secrets-dir", "./secrets"));
        String logLevel = toml.getOrElse("server.log-level", "info");
        String logFile = toml.getOrElse("server.log-file", "./logs/konkin.log");
        int logRotateMaxSizeMb = toml.getIntOrElse("server.log-rotate-max-size-mb", 10);
        String dbUrl = toml.getOrElse("database.url", "jdbc:h2:./data/konkin");
        String dbUser = toml.getOrElse("database.user", "konkin");
        // [M-3] Resolve DB password: replace default 'sa' with auto-generated secret
        String dbPassword = SecretFileBootstrapper.ensureDbPassword(
                toml.getOrElse("database.password", "konkin"), secretsDir);
        int dbPoolSize = toml.getIntOrElse("database.pool-size", 5);

        boolean landingEnabled = getOrElseWithFallback(toml, "web-ui.enabled", "landing.enabled", false);
        boolean landingPasswordProtectionEnabled = getOrElseWithFallback(
                toml,
                "web-ui.password-protection.enabled",
                "landing.password-protection.enabled",
                landingEnabled
        );
        String landingPasswordFile = getOrElseWithFallback(
                toml,
                "web-ui.password-protection.password-file",
                "landing.password-protection.password-file",
                secretsDir + "web-ui.password"
        );
        String landingTemplateDirectory = getOrElseWithFallback(
                toml,
                "web-ui.template.directory",
                "landing.template.directory",
                "./src/main/resources/templates"
        );
        String landingStaticDirectory = getOrElseWithFallback(
                toml,
                "web-ui.static.directory",
                "landing.static.directory",
                "./src/main/resources/static"
        );
        String landingStaticHostedPath = getOrElseWithFallback(
                toml,
                "web-ui.static.hosted-path",
                "landing.static.hosted-path",
                "/assets"
        );
        boolean landingAutoReloadEnabled = getOrElseWithFallback(
                toml,
                "web-ui.auto-reload.enabled",
                "landing.auto-reload.enabled",
                true
        );
        boolean landingAssetsAutoReloadEnabled = getOrElseWithFallback(
                toml,
                "web-ui.auto-reload.assets-enabled",
                "landing.auto-reload.assets-enabled",
                true
        );

        boolean debugEnabled = toml.getOrElse("debug.enabled", false);
        boolean debugSeedFakeData = toml.getOrElse("debug.seed-fake-data", false);

        boolean restApiEnabled = toml.getOrElse("rest-api.enabled", false);
        String restApiSecretFile = toml.getOrElse("rest-api.secret-file", secretsDir + "rest-api.secret");

        boolean telegramEnabled = toml.getOrElse("telegram.enabled", false);
        String telegramSecretFile = toml.getOrElse("telegram.secret-file", secretsDir + "telegram.secret");
        String telegramApiBaseUrl = toml.getOrElse("telegram.api-base-url", "https://api.telegram.org");

        String telegramAutoDenyTimeoutRaw = normalizeString(toml.get("telegram.auto-deny-timeout"));
        if (telegramAutoDenyTimeoutRaw == null || telegramAutoDenyTimeoutRaw.isBlank()) {
            telegramAutoDenyTimeoutRaw = "5m";
        }
        Duration telegramAutoDenyTimeout = parseDuration(telegramAutoDenyTimeoutRaw, "telegram.auto-deny-timeout");

        LinkedHashSet<String> deduplicatedTelegramChatIds = new LinkedHashSet<>();
        Object rawTelegramChatIds = toml.get("telegram.chat-ids");
        if (rawTelegramChatIds instanceof List<?> listValue) {
            for (Object candidate : listValue) {
                if (candidate == null) {
                    continue;
                }
                String chatId = candidate.toString().trim();
                if (!chatId.isEmpty()) {
                    deduplicatedTelegramChatIds.add(chatId);
                }
            }
        }
        List<String> telegramChatIds = List.copyOf(deduplicatedTelegramChatIds);

        AgentConfig primaryAgent = loadPrimaryAgentConfig(toml, secretsDir);
        Map<String, AgentConfig> secondaryAgents = loadSecondaryAgentConfigs(toml, secretsDir);

        CoinConfig bitcoin = loadBitcoinConfig(toml, secretsDir);
        CoinConfig litecoin = loadCoinConfig(toml, "litecoin", "ltc-main");
        CoinConfig monero = loadMoneroConfig(toml, secretsDir);
        CoinConfig testDummyCoin = loadTestDummyCoinConfig(toml, debugEnabled);

        return new KonkinConfig(
                configVersion, host, port, secretsDir, logLevel, logFile, logRotateMaxSizeMb,
                dbUrl, dbUser, dbPassword, dbPoolSize,
                landingEnabled, landingPasswordProtectionEnabled, landingPasswordFile,
                landingTemplateDirectory, landingStaticDirectory, landingStaticHostedPath,
                landingAutoReloadEnabled, landingAssetsAutoReloadEnabled,
                debugEnabled, debugSeedFakeData,
                restApiEnabled, restApiSecretFile,
                telegramEnabled, telegramSecretFile, telegramApiBaseUrl, telegramAutoDenyTimeout, telegramChatIds,
                primaryAgent, secondaryAgents,
                bitcoin, litecoin, monero, testDummyCoin
        );
    }

    private static String normalizeSecretsDir(String dir) {
        if (dir == null || dir.isBlank()) {
            return "./secrets/";
        }
        return dir.endsWith("/") ? dir : dir + "/";
    }

    private static <T> T getOrElseWithFallback(FileConfig toml, String primaryKey, String legacyKey, T defaultValue) {
        return toml.getOrElse(primaryKey, toml.getOrElse(legacyKey, defaultValue));
    }

    private static AgentConfig loadPrimaryAgentConfig(FileConfig toml, String secretsDir) {
        Object rawPrimaryAgent = toml.get("agents.primary");
        if (rawPrimaryAgent == null) {
            return null;
        }

        if (!(rawPrimaryAgent instanceof Config primaryConfig)) {
            throw new IllegalStateException("Invalid config: agents.primary must be a TOML table.");
        }

        return parseAgentConfig(
                primaryConfig,
                "agents.primary",
                "127.0.0.1",
                9550,
                secretsDir + "agent-primary.secret"
        );
    }

    private static Map<String, AgentConfig> loadSecondaryAgentConfigs(FileConfig toml, String secretsDir) {
        Object rawSecondaryAgents = toml.get("agents.secondary");
        if (rawSecondaryAgents == null) {
            return Map.of();
        }

        if (!(rawSecondaryAgents instanceof Config secondaryConfig)) {
            throw new IllegalStateException("Invalid config: agents.secondary must be a TOML table.");
        }

        LinkedHashMap<String, AgentConfig> parsedAgents = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : secondaryConfig.valueMap().entrySet()) {
            String agentName = normalizeString(entry.getKey());
            if (agentName == null || agentName.isBlank()) {
                throw new IllegalStateException("Invalid config: agents.secondary contains a blank agent name.");
            }
            if (parsedAgents.containsKey(agentName)) {
                throw new IllegalStateException("Invalid config: duplicate agent name '" + agentName + "'.");
            }

            Object rawAgent = entry.getValue();
            if (!(rawAgent instanceof Config agentConfig)) {
                throw new IllegalStateException(
                        "Invalid config: agents.secondary." + agentName + " must be a TOML table."
                );
            }

            AgentConfig parsed = parseAgentConfig(
                    agentConfig,
                    "agents.secondary." + agentName,
                    "127.0.0.1",
                    0,
                    secretsDir + agentName + ".secret"
            );
            parsedAgents.put(agentName, parsed);
        }

        return Map.copyOf(parsedAgents);
    }

    private static AgentConfig parseAgentConfig(
            Config config,
            String keyPrefix,
            String defaultBind,
            int defaultPort,
            String defaultSecretFile
    ) {
        boolean enabled = parseBooleanOrDefault(config.get("enabled"), false, keyPrefix + ".enabled");

        String bind = normalizeString(config.get("bind"));
        if (bind == null || bind.isBlank()) {
            bind = defaultBind;
        }

        int port = parseIntOrDefault(config.get("port"), defaultPort, keyPrefix + ".port");

        String secretFile = normalizeString(config.get("secret-file"));
        if (secretFile == null || secretFile.isBlank()) {
            secretFile = defaultSecretFile;
        }

        return new AgentConfig(enabled, bind, port, secretFile);
    }

    private static CoinConfig loadBitcoinConfig(FileConfig toml, String secretsDir) {
        boolean enabled = toml.getOrElse("coins.bitcoin.enabled", false);

        String daemonSecretFile = toml.getOrElse(
                "coins.bitcoin.secret-files.bitcoin-daemon-config-file",
                secretsDir + "bitcoin-daemon.conf"
        );
        String walletSecretFile = toml.getOrElse(
                "coins.bitcoin.secret-files.bitcoin-wallet-config-file",
                secretsDir + "bitcoin-wallet.conf"
        );

        String signingAddress = toml.getOrElse("coins.bitcoin.signing-address", "");

        boolean webUi = toml.getOrElse("coins.bitcoin.auth.web-ui", true);
        boolean restApi = toml.getOrElse("coins.bitcoin.auth.rest-api", true);
        boolean telegram = toml.getOrElse("coins.bitcoin.auth.telegram", false);
        String mcp = toml.getOrElse("coins.bitcoin.auth.mcp", "");
        List<String> mcpAuthChannels = loadMcpAuthChannels(toml, "coins.bitcoin.auth", mcp);

        List<ApprovalRule> autoAccept = readApprovalRules(toml, "coins.bitcoin.auth.auto-accept");
        List<ApprovalRule> autoDeny = readApprovalRules(toml, "coins.bitcoin.auth.auto-deny");

        int minApprovalsRequired = toml.getIntOrElse("coins.bitcoin.auth.min-approvals-required", 1);
        List<String> vetoChannels = loadVetoChannels(toml, "coins.bitcoin.auth");

        return new CoinConfig(
                enabled,
                daemonSecretFile,
                walletSecretFile,
                signingAddress,
                new CoinAuthConfig(autoAccept, autoDeny, webUi, restApi, telegram, mcp, mcpAuthChannels, minApprovalsRequired, vetoChannels)
        );
    }

    private static CoinConfig loadMoneroConfig(FileConfig toml, String secretsDir) {
        String coinPrefix = "coins.monero";
        boolean enabled = toml.getOrElse(coinPrefix + ".enabled", false);

        String daemonSecretFile = toml.getOrElse(
                coinPrefix + ".secret-files.monero-daemon-config-file",
                secretsDir + "monero-daemon.conf");
        String walletRpcSecretFile = toml.getOrElse(
                coinPrefix + ".secret-files.monero-wallet-rpc-config-file",
                secretsDir + "monero-wallet-rpc.conf");

        boolean webUi = toml.getOrElse(coinPrefix + ".auth.web-ui", true);
        boolean restApi = toml.getOrElse(coinPrefix + ".auth.rest-api", true);
        boolean telegram = toml.getOrElse(coinPrefix + ".auth.telegram", false);
        String mcp = toml.getOrElse(coinPrefix + ".auth.mcp", "xmr-main");
        List<String> mcpAuthChannels = loadMcpAuthChannels(toml, coinPrefix + ".auth", mcp);

        List<ApprovalRule> autoAccept = readApprovalRules(toml, coinPrefix + ".auth.auto-accept");
        List<ApprovalRule> autoDeny = readApprovalRules(toml, coinPrefix + ".auth.auto-deny");

        int minApprovalsRequired = toml.getIntOrElse(coinPrefix + ".auth.min-approvals-required", 1);
        List<String> vetoChannels = loadVetoChannels(toml, coinPrefix + ".auth");

        return new CoinConfig(
                enabled,
                daemonSecretFile,
                walletRpcSecretFile,
                new CoinAuthConfig(autoAccept, autoDeny, webUi, restApi, telegram, mcp, mcpAuthChannels, minApprovalsRequired, vetoChannels)
        );
    }

    private static CoinConfig loadTestDummyCoinConfig(FileConfig toml, boolean debugEnabled) {
        if (!debugEnabled) {
            return new CoinConfig(
                    false,
                    "",
                    "",
                    new CoinAuthConfig(List.of(), List.of(), false, false, false, "", List.of(), 1, List.of())
            );
        }
        return loadCoinConfig(toml, "testdummycoin", "tdc-main");
    }

    private static CoinConfig loadCoinConfig(FileConfig toml, String coinId, String defaultMcp) {
        String coinPrefix = "coins." + coinId;

        boolean enabled = toml.getOrElse(coinPrefix + ".enabled", false);
        boolean webUi = toml.getOrElse(coinPrefix + ".auth.web-ui", true);
        boolean restApi = toml.getOrElse(coinPrefix + ".auth.rest-api", true);
        boolean telegram = toml.getOrElse(coinPrefix + ".auth.telegram", false);
        String mcp = toml.getOrElse(coinPrefix + ".auth.mcp", defaultMcp);
        List<String> mcpAuthChannels = loadMcpAuthChannels(toml, coinPrefix + ".auth", mcp);

        List<ApprovalRule> autoAccept = readApprovalRules(toml, coinPrefix + ".auth.auto-accept");
        List<ApprovalRule> autoDeny = readApprovalRules(toml, coinPrefix + ".auth.auto-deny");

        int minApprovalsRequired = toml.getIntOrElse(coinPrefix + ".auth.min-approvals-required", 1);
        List<String> vetoChannels = loadVetoChannels(toml, coinPrefix + ".auth");

        return new CoinConfig(
                enabled,
                "",
                "",
                new CoinAuthConfig(autoAccept, autoDeny, webUi, restApi, telegram, mcp, mcpAuthChannels, minApprovalsRequired, vetoChannels)
        );
    }

    private static List<String> loadMcpAuthChannels(FileConfig toml, String authPath, String fallbackMcp) {
        LinkedHashSet<String> channels = new LinkedHashSet<>();

        Object raw = toml.get(authPath + ".mcp-auth-channels");
        if (raw != null) {
            if (!(raw instanceof List<?> rawList)) {
                throw new IllegalStateException("Invalid config: " + authPath + ".mcp-auth-channels must be a TOML list.");
            }
            for (Object item : rawList) {
                if (item == null) {
                    continue;
                }
                String channel = item.toString().trim();
                if (!channel.isEmpty()) {
                    channels.add(channel);
                }
            }
        }

        if (channels.isEmpty() && fallbackMcp != null && !fallbackMcp.isBlank()) {
            channels.add(fallbackMcp.trim());
        }

        return List.copyOf(channels);
    }

    private static List<String> loadVetoChannels(FileConfig toml, String authPath) {
        Object raw = toml.get(authPath + ".veto-channels");
        if (raw == null) {
            return List.of();
        }

        if (!(raw instanceof List<?> rawList)) {
            throw new IllegalStateException("Invalid config: " + authPath + ".veto-channels must be a TOML list.");
        }

        LinkedHashSet<String> vetoChannels = new LinkedHashSet<>();
        for (Object item : rawList) {
            if (item == null) {
                continue;
            }
            String normalized = item.toString().trim();
            if (!normalized.isEmpty()) {
                vetoChannels.add(normalized);
            }
        }

        return List.copyOf(vetoChannels);
    }

    private static List<ApprovalRule> readApprovalRules(FileConfig toml, String keyPath) {
        Object rawRules = toml.get(keyPath);
        if (rawRules == null) {
            return List.of();
        }

        if (!(rawRules instanceof List<?> listRules)) {
            throw new IllegalStateException("Invalid config: " + keyPath + " must be a TOML list.");
        }

        List<ApprovalRule> parsedRules = new ArrayList<>();
        int index = 0;
        for (Object rawRule : listRules) {
            parsedRules.add(parseApprovalRule(rawRule, keyPath, index));
            index++;
        }

        return List.copyOf(parsedRules);
    }

    private static ApprovalRule parseApprovalRule(Object rawRule, String keyPath, int index) {
        if (!(rawRule instanceof Config ruleConfig)) {
            throw new IllegalStateException(
                    "Invalid config: " + keyPath + "[" + index + "] must be a TOML table with criteria.");
        }

        Object rawCriteria = ruleConfig.get("criteria");
        ApprovalCriteria criteria = rawCriteria == null
                ? parseCriteria(ruleConfig, keyPath + "[" + index + "]")
                : parseCriteria(rawCriteria, keyPath + "[" + index + "].criteria");

        return new ApprovalRule(criteria);
    }

    private static ApprovalCriteria parseCriteria(Object rawCriteria, String keyPath) {
        if (!(rawCriteria instanceof Config criteriaConfig)) {
            throw new IllegalStateException("Invalid config: " + keyPath + " must be a TOML table.");
        }

        String typeRaw = normalizeString(criteriaConfig.get("type"));
        if (typeRaw == null || typeRaw.isBlank()) {
            throw new IllegalStateException("Invalid config: " + keyPath + ".type must be set.");
        }

        CriteriaType type = CriteriaType.fromTomlValue(typeRaw);

        double value = parseDouble(criteriaConfig.get("value"), keyPath + ".value");
        if (value <= 0d) {
            throw new IllegalStateException("Invalid config: " + keyPath + ".value must be > 0.");
        }

        String periodRaw = normalizeString(criteriaConfig.get("period"));
        Duration period = null;

        if (type.requiresPeriod()) {
            if (periodRaw == null || periodRaw.isBlank()) {
                throw new IllegalStateException(
                        "Invalid config: " + keyPath + ".period is required for type=" + type.tomlValue() + "."
                );
            }
            period = parseDuration(periodRaw, keyPath + ".period");
        } else if (periodRaw != null && !periodRaw.isBlank()) {
            period = parseDuration(periodRaw, keyPath + ".period");
        }

        return new ApprovalCriteria(type, value, period);
    }
}