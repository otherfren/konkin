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

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Validates a fully populated {@link KonkinConfig} for logical consistency.
 */
final class KonkinConfigValidator {

    private KonkinConfigValidator() {
    }

    static void validate(KonkinConfig config) {
        validateConsistency(config);
    }

    private static void validateConsistency(KonkinConfig config) {
        if (config.logRotateMaxSizeMb() <= 0) {
            throw new IllegalStateException(
                    "Invalid config: server.log-rotate-max-size-mb must be > 0.");
        }

        if (config.debugSeedFakeData() && !config.debugEnabled()) {
            throw new IllegalStateException(
                    "Invalid config: debug.seed-fake-data=true requires debug.enabled=true.");
        }

        if (!config.landingEnabled() && config.landingPasswordProtectionEnabled()) {
            throw new IllegalStateException(
                    "Invalid config: web-ui.password-protection.enabled=true requires web-ui.enabled=true.");
        }

        if (config.landingEnabled()) {
            if (config.landingStaticHostedPath() == null || config.landingStaticHostedPath().isBlank() || !config.landingStaticHostedPath().startsWith("/")) {
                throw new IllegalStateException("Invalid config: web-ui.static.hosted-path must start with '/' when web-ui.enabled=true.");
            }

            validatePath(config.landingTemplateDirectory(), "web-ui.template.directory");
            validatePath(config.landingStaticDirectory(), "web-ui.static.directory");

            if (config.landingPasswordProtectionEnabled()) {
                if (config.landingPasswordFile() == null || config.landingPasswordFile().isBlank()) {
                    throw new IllegalStateException(
                            "Invalid config: web-ui.password-protection.password-file must be set when password protection is enabled.");
                }
                validatePath(config.landingPasswordFile(), "web-ui.password-protection.password-file");
            }
        }

        if (config.restApiEnabled()) {
            if (config.restApiSecretFile() == null || config.restApiSecretFile().isBlank()) {
                throw new IllegalStateException(
                        "Invalid config: rest-api.secret-file must be set when rest-api.enabled=true.");
            }
            validatePath(config.restApiSecretFile(), "rest-api.secret-file");
        }

        if (config.telegramEnabled()) {
            if (config.telegramSecretFile() == null || config.telegramSecretFile().isBlank()) {
                throw new IllegalStateException(
                        "Invalid config: telegram.secret-file must be set when telegram.enabled=true.");
            }
            validatePath(config.telegramSecretFile(), "telegram.secret-file");
            if (config.telegramApiBaseUrl() == null || config.telegramApiBaseUrl().isBlank()) {
                throw new IllegalStateException(
                        "Invalid config: telegram.api-base-url must be set when telegram.enabled=true.");
            }

            for (String chatId : config.telegramChatIds()) {
                if (chatId == null || chatId.isBlank()) {
                    throw new IllegalStateException(
                            "Invalid config: telegram.chat-ids must not contain blank entries.");
                }
            }
        }

        validateBitcoinConfig(config);
        validateNonBitcoinCoinConfig("litecoin", config.litecoin(), config);
        validateNonBitcoinCoinConfig("monero", config.monero(), config);
        validateNonBitcoinCoinConfig("testdummycoin", config.testDummyCoin(), config);
        validateAgentsConfig(config);
    }

    private static void validateBitcoinConfig(KonkinConfig config) {
        CoinConfig bitcoin = config.bitcoin();
        if (!bitcoin.enabled()) {
            return;
        }

        if (bitcoin.bitcoinDaemonConfigSecretFile() == null || bitcoin.bitcoinDaemonConfigSecretFile().isBlank()) {
            throw new IllegalStateException(
                    "Invalid config: coins.bitcoin.secret-files.bitcoin-daemon-config-file must be set when coins.bitcoin.enabled=true.");
        }
        if (bitcoin.bitcoinWalletConfigSecretFile() == null || bitcoin.bitcoinWalletConfigSecretFile().isBlank()) {
            throw new IllegalStateException(
                    "Invalid config: coins.bitcoin.secret-files.bitcoin-wallet-config-file must be set when coins.bitcoin.enabled=true.");
        }

        validatePath(bitcoin.bitcoinDaemonConfigSecretFile(), "coins.bitcoin.secret-files.bitcoin-daemon-config-file");
        validatePath(bitcoin.bitcoinWalletConfigSecretFile(), "coins.bitcoin.secret-files.bitcoin-wallet-config-file");

        CoinAuthConfig auth = bitcoin.auth();
        int configuredChannelCount = countConfiguredChannels(auth);
        if (configuredChannelCount == 0) {
            throw new IllegalStateException(
                    "Invalid config: coins.bitcoin.auth must enable at least one channel (web-ui/rest-api/telegram/mcp-auth-channels)."
            );
        }

        if (auth.minApprovalsRequired() <= 0) {
            throw new IllegalStateException(
                    "Invalid config: coins.bitcoin.auth.min-approvals-required must be > 0."
            );
        }

        if (auth.minApprovalsRequired() > configuredChannelCount) {
            throw new IllegalStateException(
                    "Invalid config: coins.bitcoin.auth.min-approvals-required=" + auth.minApprovalsRequired()
                            + " exceeds configured auth channels=" + configuredChannelCount + "."
            );
        }

        validateVetoChannels("bitcoin", auth);

        for (ApprovalRule rule : auth.autoAccept()) {
            ensureRuleConsistency(rule, "coins.bitcoin.auth.auto-accept");
        }
        for (ApprovalRule rule : auth.autoDeny()) {
            ensureRuleConsistency(rule, "coins.bitcoin.auth.auto-deny");
        }

        CoinAuthCriteriaValidator.validateChannelAvailability(
                "bitcoin",
                auth,
                config.landingEnabled(),
                config.restApiEnabled(),
                config.telegramEnabled()
        );
        CoinAuthCriteriaValidator.validateNoContradictions("bitcoin", auth);
    }

    private static void validateNonBitcoinCoinConfig(String coinName, CoinConfig coin, KonkinConfig config) {
        if (!coin.enabled()) {
            return;
        }

        CoinAuthConfig auth = coin.auth();
        int configuredChannelCount = countConfiguredChannels(auth);
        if (configuredChannelCount == 0) {
            throw new IllegalStateException(
                    "Invalid config: coins." + coinName + ".auth must enable at least one channel (web-ui/rest-api/telegram/mcp-auth-channels)."
            );
        }

        if (auth.minApprovalsRequired() <= 0) {
            throw new IllegalStateException(
                    "Invalid config: coins." + coinName + ".auth.min-approvals-required must be > 0."
            );
        }

        if (auth.minApprovalsRequired() > configuredChannelCount) {
            throw new IllegalStateException(
                    "Invalid config: coins." + coinName + ".auth.min-approvals-required=" + auth.minApprovalsRequired()
                            + " exceeds configured auth channels=" + configuredChannelCount + "."
            );
        }

        validateVetoChannels(coinName, auth);

        for (ApprovalRule rule : auth.autoAccept()) {
            ensureRuleConsistency(rule, "coins." + coinName + ".auth.auto-accept");
        }
        for (ApprovalRule rule : auth.autoDeny()) {
            ensureRuleConsistency(rule, "coins." + coinName + ".auth.auto-deny");
        }

        CoinAuthCriteriaValidator.validateChannelAvailability(
                coinName,
                auth,
                config.landingEnabled(),
                config.restApiEnabled(),
                config.telegramEnabled()
        );
        CoinAuthCriteriaValidator.validateNoContradictions(coinName, auth);
    }

    private static void validateAgentsConfig(KonkinConfig config) {
        if (config.primaryAgent() != null) {
            validateAgentConfig(config.primaryAgent(), "agents.primary");
        }

        for (Map.Entry<String, AgentConfig> entry : config.secondaryAgents().entrySet()) {
            validateAgentConfig(entry.getValue(), "agents.secondary." + entry.getKey());
        }

        validateAgentPortUniqueness(config);
        validateMcpAuthChannelReferences(config);
    }

    private static void validateAgentConfig(AgentConfig agentConfig, String keyPrefix) {
        if (agentConfig == null) {
            return;
        }

        if (agentConfig.bind() == null || agentConfig.bind().isBlank()) {
            throw new IllegalStateException("Invalid config: " + keyPrefix + ".bind must be set.");
        }

        if (agentConfig.port() <= 0) {
            throw new IllegalStateException("Invalid config: " + keyPrefix + ".port must be > 0.");
        }

        if (agentConfig.secretFile() == null || agentConfig.secretFile().isBlank()) {
            throw new IllegalStateException("Invalid config: " + keyPrefix + ".secret-file must be set.");
        }

        validatePath(agentConfig.secretFile(), keyPrefix + ".secret-file");
    }

    private static void validateAgentPortUniqueness(KonkinConfig config) {
        LinkedHashMap<Integer, String> portOwners = new LinkedHashMap<>();
        portOwners.put(config.port(), "server");

        if (config.primaryAgent() != null) {
            ensureUniquePort(portOwners, config.primaryAgent().port(), "agent 'driver'");
        }

        for (Map.Entry<String, AgentConfig> entry : config.secondaryAgents().entrySet()) {
            ensureUniquePort(portOwners, entry.getValue().port(), "agent '" + entry.getKey() + "'");
        }
    }

    private static void ensureUniquePort(Map<Integer, String> portOwners, int candidatePort, String owner) {
        String existingOwner = portOwners.putIfAbsent(candidatePort, owner);
        if (existingOwner != null) {
            throw new IllegalStateException(
                    "Invalid config: port " + candidatePort + " used by both " + existingOwner + " and " + owner + "."
            );
        }
    }

    private static void validateMcpAuthChannelReferences(KonkinConfig config) {
        validateCoinMcpAuthChannelReferences(config, "bitcoin", config.bitcoin().auth().mcp(), config.bitcoin().auth().mcpAuthChannels());
        validateCoinMcpAuthChannelReferences(config, "litecoin", config.litecoin().auth().mcp(), config.litecoin().auth().mcpAuthChannels());
        validateCoinMcpAuthChannelReferences(config, "monero", config.monero().auth().mcp(), config.monero().auth().mcpAuthChannels());
        validateCoinMcpAuthChannelReferences(config, "testdummycoin", config.testDummyCoin().auth().mcp(), config.testDummyCoin().auth().mcpAuthChannels());
    }

    private static void validateCoinMcpAuthChannelReferences(KonkinConfig config, String coinName, String mcpValue, List<String> channels) {
        for (String channel : channels) {
            boolean appearsToBeLegacyFallback = channels.size() == 1
                    && mcpValue != null
                    && !mcpValue.isBlank()
                    && channel.equals(mcpValue);
            if (appearsToBeLegacyFallback) {
                continue;
            }

            if (!config.secondaryAgents().containsKey(channel)) {
                throw new IllegalStateException(
                        "Invalid config: mcp-auth-channel '" + channel
                                + "' references undefined agent for coin '" + coinName + "'."
                );
            }
        }
    }

    private static int countConfiguredChannels(CoinAuthConfig auth) {
        int count = 0;
        if (auth.webUi()) {
            count++;
        }
        if (auth.restApi()) {
            count++;
        }
        if (auth.telegram()) {
            count++;
        }
        if (auth.mcpAuthChannels() != null) {
            count += auth.mcpAuthChannels().size();
        }
        return count;
    }

    private static void validateVetoChannels(String coinName, CoinAuthConfig auth) {
        if (auth.vetoChannels() == null || auth.vetoChannels().isEmpty()) {
            return;
        }

        LinkedHashSet<String> enabledChannels = new LinkedHashSet<>();
        if (auth.webUi()) {
            enabledChannels.add("web-ui");
        }
        if (auth.restApi()) {
            enabledChannels.add("rest-api");
        }
        if (auth.telegram()) {
            enabledChannels.add("telegram");
        }
        if (auth.mcpAuthChannels() != null) {
            enabledChannels.addAll(auth.mcpAuthChannels());
        }

        for (String vetoChannel : auth.vetoChannels()) {
            if (vetoChannel == null || vetoChannel.isBlank()) {
                throw new IllegalStateException(
                        "Invalid config: coins." + coinName + ".auth.veto-channels must not contain blank entries."
                );
            }
            if (!enabledChannels.contains(vetoChannel)) {
                throw new IllegalStateException(
                        "Invalid config: coins." + coinName + ".auth.veto-channels contains '" + vetoChannel
                                + "' which is not an enabled auth channel."
                );
            }
        }
    }

    private static void ensureRuleConsistency(ApprovalRule rule, String keyPath) {
        if (rule == null || rule.criteria() == null) {
            throw new IllegalStateException("Invalid config: " + keyPath + " contains an empty rule.");
        }

        ApprovalCriteria criteria = rule.criteria();
        if (criteria.value() <= 0d) {
            throw new IllegalStateException("Invalid config: " + keyPath + " criteria.value must be > 0.");
        }

        if (criteria.type().requiresPeriod()) {
            if (criteria.period() == null || criteria.period().isZero() || criteria.period().isNegative()) {
                throw new IllegalStateException(
                        "Invalid config: " + keyPath + " requires criteria.period > PT0S for cumulated types.");
            }
        }
    }

    private static void validatePath(String pathValue, String keyName) {
        try {
            Path.of(pathValue);
        } catch (InvalidPathException e) {
            throw new IllegalStateException(
                    "Invalid config: " + keyName + " is not a valid path: " + pathValue,
                    e
            );
        }
    }
}