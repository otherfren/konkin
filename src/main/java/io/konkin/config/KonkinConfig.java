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

import com.electronwill.nightconfig.core.file.FileConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loads and holds all configuration from config.toml.
 */
public class KonkinConfig {

    private static final Logger log = LoggerFactory.getLogger(KonkinConfig.class);

    private final int configVersion;
    private final String host;
    private final int port;
    private final String logLevel;
    private final String logFile;
    private final int logRotateMaxSizeMb;
    private final String dbUrl;
    private final String dbUser;
    private final String dbPassword;
    private final int dbPoolSize;

    private final boolean landingEnabled;
    private final boolean landingPasswordProtectionEnabled;
    private final String landingPasswordFile;
    private final String landingTemplateDirectory;
    private final String landingStaticDirectory;
    private final String landingStaticHostedPath;
    private final boolean landingAutoReloadEnabled;
    private final boolean landingAssetsAutoReloadEnabled;

    private final boolean debugEnabled;
    private final boolean debugSeedFakeData;

    private final boolean restApiEnabled;
    private final String restApiSecretFile;

    private final boolean telegramEnabled;
    private final String telegramSecretFile;
    private final String telegramApiBaseUrl;
    private final Duration telegramAutoDenyTimeout;
    private final List<String> telegramChatIds;

    private final AgentConfig primaryAgent;
    private final Map<String, AgentConfig> secondaryAgents;

    private final CoinConfig bitcoin;
    private final CoinConfig litecoin;
    private final CoinConfig monero;
    private final CoinConfig testDummyCoin;
    private Set<String> freshlyCreatedAgentSecrets = Set.of();

    KonkinConfig(
            int configVersion, String host, int port, String logLevel, String logFile, int logRotateMaxSizeMb,
            String dbUrl, String dbUser, String dbPassword, int dbPoolSize,
            boolean landingEnabled, boolean landingPasswordProtectionEnabled, String landingPasswordFile,
            String landingTemplateDirectory, String landingStaticDirectory, String landingStaticHostedPath,
            boolean landingAutoReloadEnabled, boolean landingAssetsAutoReloadEnabled,
            boolean debugEnabled, boolean debugSeedFakeData,
            boolean restApiEnabled, String restApiSecretFile,
            boolean telegramEnabled, String telegramSecretFile, String telegramApiBaseUrl, Duration telegramAutoDenyTimeout, List<String> telegramChatIds,
            AgentConfig primaryAgent, Map<String, AgentConfig> secondaryAgents,
            CoinConfig bitcoin, CoinConfig litecoin, CoinConfig monero, CoinConfig testDummyCoin
    ) {
        this.configVersion = configVersion;
        this.host = host;
        this.port = port;
        this.logLevel = logLevel;
        this.logFile = logFile;
        this.logRotateMaxSizeMb = logRotateMaxSizeMb;
        this.dbUrl = dbUrl;
        this.dbUser = dbUser;
        this.dbPassword = dbPassword;
        this.dbPoolSize = dbPoolSize;
        this.landingEnabled = landingEnabled;
        this.landingPasswordProtectionEnabled = landingPasswordProtectionEnabled;
        this.landingPasswordFile = landingPasswordFile;
        this.landingTemplateDirectory = landingTemplateDirectory;
        this.landingStaticDirectory = landingStaticDirectory;
        this.landingStaticHostedPath = landingStaticHostedPath;
        this.landingAutoReloadEnabled = landingAutoReloadEnabled;
        this.landingAssetsAutoReloadEnabled = landingAssetsAutoReloadEnabled;
        this.debugEnabled = debugEnabled;
        this.debugSeedFakeData = debugSeedFakeData;
        this.restApiEnabled = restApiEnabled;
        this.restApiSecretFile = restApiSecretFile;
        this.telegramEnabled = telegramEnabled;
        this.telegramSecretFile = telegramSecretFile;
        this.telegramApiBaseUrl = telegramApiBaseUrl;
        this.telegramAutoDenyTimeout = telegramAutoDenyTimeout;
        this.telegramChatIds = telegramChatIds;
        this.primaryAgent = primaryAgent;
        this.secondaryAgents = secondaryAgents;
        this.bitcoin = bitcoin;
        this.litecoin = litecoin;
        this.monero = monero;
        this.testDummyCoin = testDummyCoin;
    }

    /**
     * Load configuration from a TOML file path.
     * Fails fast on missing/incompatible config-version and logical inconsistencies.
     */
    public static KonkinConfig load(String path) {
        log.info("Loading configuration from: {}", path);
        try (FileConfig toml = FileConfig.of(Path.of(path))) {
            toml.load();

            int version = toml.getIntOrElse("config-version", -1);
            if (version == -1) {
                throw new IllegalStateException("config.toml is missing mandatory 'config-version' key. Refusing to start.");
            }
            if (version != KonkinConfigLoader.EXPECTED_CONFIG_VERSION) {
                throw new IllegalStateException(
                        "config.toml has config-version=" + version +
                                " but this build expects config-version=" + KonkinConfigLoader.EXPECTED_CONFIG_VERSION +
                                ". See CHANGELOG.md for migration instructions. Refusing to start.");
            }

            KonkinConfig config = KonkinConfigLoader.load(toml);
            KonkinConfigValidator.validate(config);
            config.freshlyCreatedAgentSecrets = SecretFileBootstrapper.bootstrap(config);

            log.info("Configuration loaded — host={}, port={}, db={}", config.host, config.port, config.dbUrl);
            log.info("Logging config — level={}, file={}, rotateMaxSizeMb={}",
                    config.logLevel,
                    config.logFile,
                    config.logRotateMaxSizeMb);
            log.info("Web UI config — enabled={}, passwordProtection={}, templateDir={}, staticDir={}",
                    config.landingEnabled,
                    config.landingPasswordProtectionEnabled,
                    config.landingTemplateDirectory,
                    config.landingStaticDirectory
            );
            log.info("Debug config — enabled={}, seedFakeData={}", config.debugEnabled, config.debugSeedFakeData);
            log.info("REST API config — enabled={}, secretFile={}",
                    config.restApiEnabled,
                    config.restApiSecretFile);
            log.info("Telegram config — enabled={}, secretFile={}, autoDenyTimeout={}, configuredChatIds={}",
                    config.telegramEnabled,
                    config.telegramSecretFile,
                    config.telegramAutoDenyTimeout,
                    config.telegramChatIds.size());
            log.info("Bitcoin config — enabled={}, daemonSecretFile={}, walletSecretFile={}, webUi={}, restApi={}, telegram={}, mcpId={}, mcpAuthChannels={}, autoAcceptRules={}, autoDenyRules={}",
                    config.bitcoin.enabled(),
                    config.bitcoin.daemonConfigSecretFile(),
                    config.bitcoin.walletConfigSecretFile(),
                    config.bitcoin.auth().webUi(),
                    config.bitcoin.auth().restApi(),
                    config.bitcoin.auth().telegram(),
                    config.bitcoin.auth().mcp(),
                    config.bitcoin.auth().mcpAuthChannels().size(),
                    config.bitcoin.auth().autoAccept().size(),
                    config.bitcoin.auth().autoDeny().size());
            log.info("TestDummyCoin config — enabled={}, debugEnabled={}, webUi={}, restApi={}, telegram={}, mcpId={}, mcpAuthChannels={}, autoAcceptRules={}, autoDenyRules={}",
                    config.testDummyCoin.enabled(),
                    config.debugEnabled,
                    config.testDummyCoin.auth().webUi(),
                    config.testDummyCoin.auth().restApi(),
                    config.testDummyCoin.auth().telegram(),
                    config.testDummyCoin.auth().mcp(),
                    config.testDummyCoin.auth().mcpAuthChannels().size(),
                    config.testDummyCoin.auth().autoAccept().size(),
                    config.testDummyCoin.auth().autoDeny().size());
            return config;
        }
    }

    public int configVersion() { return configVersion; }
    public String host() { return host; }
    public int port() { return port; }
    public String logLevel() { return logLevel; }
    public String logFile() { return logFile; }
    public int logRotateMaxSizeMb() { return logRotateMaxSizeMb; }
    public String dbUrl() { return dbUrl; }
    public String dbUser() { return dbUser; }
    public String dbPassword() { return dbPassword; }
    public int dbPoolSize() { return dbPoolSize; }

    public boolean landingEnabled() { return landingEnabled; }
    public boolean landingPasswordProtectionEnabled() { return landingPasswordProtectionEnabled; }
    public String landingPasswordFile() { return landingPasswordFile; }
    public String landingTemplateDirectory() { return landingTemplateDirectory; }
    public String landingStaticDirectory() { return landingStaticDirectory; }
    public String landingStaticHostedPath() { return landingStaticHostedPath; }
    public boolean landingAutoReloadEnabled() { return landingAutoReloadEnabled; }
    public boolean landingAssetsAutoReloadEnabled() { return landingAssetsAutoReloadEnabled; }

    public boolean debugEnabled() { return debugEnabled; }
    public boolean debugSeedFakeData() { return debugSeedFakeData; }

    public boolean restApiEnabled() { return restApiEnabled; }
    public String restApiSecretFile() { return restApiSecretFile; }

    public boolean telegramEnabled() { return telegramEnabled; }
    public String telegramSecretFile() { return telegramSecretFile; }
    public String telegramApiBaseUrl() { return telegramApiBaseUrl; }
    public Duration telegramAutoDenyTimeout() { return telegramAutoDenyTimeout; }
    public List<String> telegramChatIds() { return telegramChatIds; }

    public AgentConfig primaryAgent() { return primaryAgent; }
    public Map<String, AgentConfig> secondaryAgents() { return secondaryAgents; }
    public AgentConfig secondaryAgent(String name) {
        return secondaryAgents.get(name);
    }

    public CoinConfig bitcoin() { return bitcoin; }
    public CoinConfig litecoin() { return litecoin; }
    public CoinConfig monero() { return monero; }
    public CoinConfig testDummyCoin() { return testDummyCoin; }

    /**
     * Returns agent names whose secret files were freshly created during bootstrap.
     * These agents' former tokens should be revoked from the database since the
     * old client credentials are no longer valid.
     */
    public Set<String> freshlyCreatedAgentSecrets() { return freshlyCreatedAgentSecrets; }
}