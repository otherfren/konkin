package io.konkin.config;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.file.FileConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads and holds all configuration from config.toml.
 */
public class KonkinConfig {

    private static final Logger log = LoggerFactory.getLogger(KonkinConfig.class);
    private static final int EXPECTED_CONFIG_VERSION = 1;
    private static final Pattern HUMAN_DURATION_PART_PATTERN = Pattern.compile(
            "(?i)(\\d+)\\s*(weeks?|w|days?|d|hours?|hrs?|hr|h|minutes?|mins?|min|m|seconds?|secs?|sec|s)"
    );

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

    private final boolean telegramEnabled;
    private final String telegramSecretFile;
    private final String telegramApiBaseUrl;
    private final List<String> telegramChatIds;

    private final CoinConfig bitcoin;
    private final CoinConfig litecoin;
    private final CoinConfig monero;

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

    public boolean telegramEnabled() { return telegramEnabled; }
    public String telegramSecretFile() { return telegramSecretFile; }
    public String telegramApiBaseUrl() { return telegramApiBaseUrl; }
    public List<String> telegramChatIds() { return telegramChatIds; }

    public CoinConfig bitcoin() { return bitcoin; }
    public CoinConfig litecoin() { return litecoin; }
    public CoinConfig monero() { return monero; }

    private KonkinConfig(FileConfig toml) {
        this.configVersion = toml.getIntOrElse("config-version", -1);
        this.host = toml.getOrElse("server.host", "127.0.0.1");
        this.port = toml.getIntOrElse("server.port", 7070);
        this.logLevel = toml.getOrElse("server.log-level", "info");
        this.logFile = toml.getOrElse("server.log-file", "./logs/konkin.log");
        this.logRotateMaxSizeMb = toml.getIntOrElse("server.log-rotate-max-size-mb", 10);
        this.dbUrl = toml.getOrElse("database.url", "jdbc:h2:./data/konkin");
        this.dbUser = toml.getOrElse("database.user", "konkin");
        this.dbPassword = toml.getOrElse("database.password", "konkin");
        this.dbPoolSize = toml.getIntOrElse("database.pool-size", 5);

        this.landingEnabled = getOrElseWithFallback(toml, "web-ui.enabled", "landing.enabled", false);
        this.landingPasswordProtectionEnabled = getOrElseWithFallback(
                toml,
                "web-ui.password-protection.enabled",
                "landing.password-protection.enabled",
                this.landingEnabled
        );
        this.landingPasswordFile = getOrElseWithFallback(
                toml,
                "web-ui.password-protection.password-file",
                "landing.password-protection.password-file",
                "./secrets/web-ui.password"
        );
        this.landingTemplateDirectory = getOrElseWithFallback(
                toml,
                "web-ui.template.directory",
                "landing.template.directory",
                "./src/main/resources/templates"
        );
        this.landingStaticDirectory = getOrElseWithFallback(
                toml,
                "web-ui.static.directory",
                "landing.static.directory",
                "./src/main/resources/static"
        );
        this.landingStaticHostedPath = getOrElseWithFallback(
                toml,
                "web-ui.static.hosted-path",
                "landing.static.hosted-path",
                "/assets"
        );
        this.landingAutoReloadEnabled = getOrElseWithFallback(
                toml,
                "web-ui.auto-reload.enabled",
                "landing.auto-reload.enabled",
                true
        );
        this.landingAssetsAutoReloadEnabled = getOrElseWithFallback(
                toml,
                "web-ui.auto-reload.assets-enabled",
                "landing.auto-reload.assets-enabled",
                true
        );

        this.debugEnabled = toml.getOrElse("debug.enabled", false);
        this.debugSeedFakeData = toml.getOrElse("debug.seed-fake-data", false);

        this.telegramEnabled = toml.getOrElse("telegram.enabled", false);
        this.telegramSecretFile = toml.getOrElse("telegram.secret-file", "./secrets/telegram.secret");
        this.telegramApiBaseUrl = toml.getOrElse("telegram.api-base-url", "https://api.telegram.org");

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
        this.telegramChatIds = List.copyOf(deduplicatedTelegramChatIds);

        this.bitcoin = loadBitcoinConfig(toml);
        this.litecoin = loadCoinConfig(toml, "litecoin", "ltc-main");
        this.monero = loadCoinConfig(toml, "monero", "xmr-main");
    }

    private static <T> T getOrElseWithFallback(FileConfig toml, String primaryKey, String legacyKey, T defaultValue) {
        return toml.getOrElse(primaryKey, toml.getOrElse(legacyKey, defaultValue));
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
            if (version != EXPECTED_CONFIG_VERSION) {
                throw new IllegalStateException(
                        "config.toml has config-version=" + version +
                                " but this build expects config-version=" + EXPECTED_CONFIG_VERSION +
                                ". See CHANGELOG.md for migration instructions. Refusing to start.");
            }

            KonkinConfig config = new KonkinConfig(toml);
            config.validateConsistency();
            config.bootstrapSecretFiles();

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
            log.info("Telegram config — enabled={}, secretFile={}, configuredChatIds={}",
                    config.telegramEnabled,
                    config.telegramSecretFile,
                    config.telegramChatIds.size());
            log.info("Bitcoin config — enabled={}, daemonSecretFile={}, walletSecretFile={}, webUi={}, restApi={}, telegram={}, mcpId={}, mcpAuthChannels={}, autoAcceptRules={}, autoDenyRules={}",
                    config.bitcoin.enabled(),
                    config.bitcoin.bitcoinDaemonConfigSecretFile(),
                    config.bitcoin.bitcoinWalletConfigSecretFile(),
                    config.bitcoin.auth().webUi(),
                    config.bitcoin.auth().restApi(),
                    config.bitcoin.auth().telegram(),
                    config.bitcoin.auth().mcp(),
                    config.bitcoin.auth().mcpAuthChannels().size(),
                    config.bitcoin.auth().autoAccept().size(),
                    config.bitcoin.auth().autoDeny().size());
            return config;
        }
    }

    private void validateConsistency() {
        if (logRotateMaxSizeMb <= 0) {
            throw new IllegalStateException(
                    "Invalid config: server.log-rotate-max-size-mb must be > 0.");
        }

        if (debugSeedFakeData && !debugEnabled) {
            throw new IllegalStateException(
                    "Invalid config: debug.seed-fake-data=true requires debug.enabled=true.");
        }

        if (!landingEnabled && landingPasswordProtectionEnabled) {
            throw new IllegalStateException(
                    "Invalid config: web-ui.password-protection.enabled=true requires web-ui.enabled=true.");
        }

        if (landingEnabled) {
            if (landingStaticHostedPath == null || landingStaticHostedPath.isBlank() || !landingStaticHostedPath.startsWith("/")) {
                throw new IllegalStateException("Invalid config: web-ui.static.hosted-path must start with '/' when web-ui.enabled=true.");
            }

            validatePath(landingTemplateDirectory, "web-ui.template.directory");
            validatePath(landingStaticDirectory, "web-ui.static.directory");

            if (landingPasswordProtectionEnabled) {
                if (landingPasswordFile == null || landingPasswordFile.isBlank()) {
                    throw new IllegalStateException(
                            "Invalid config: web-ui.password-protection.password-file must be set when password protection is enabled.");
                }
                validatePath(landingPasswordFile, "web-ui.password-protection.password-file");
            }
        }

        if (telegramEnabled) {
            if (telegramSecretFile == null || telegramSecretFile.isBlank()) {
                throw new IllegalStateException(
                        "Invalid config: telegram.secret-file must be set when telegram.enabled=true.");
            }
            validatePath(telegramSecretFile, "telegram.secret-file");
            if (telegramApiBaseUrl == null || telegramApiBaseUrl.isBlank()) {
                throw new IllegalStateException(
                        "Invalid config: telegram.api-base-url must be set when telegram.enabled=true.");
            }

            for (String chatId : telegramChatIds) {
                if (chatId == null || chatId.isBlank()) {
                    throw new IllegalStateException(
                            "Invalid config: telegram.chat-ids must not contain blank entries.");
                }
            }
        }

        validateBitcoinConfig();
        validateNonBitcoinCoinConfig("litecoin", litecoin);
        validateNonBitcoinCoinConfig("monero", monero);
    }

    private CoinConfig loadBitcoinConfig(FileConfig toml) {
        boolean enabled = toml.getOrElse("coins.bitcoin.enabled", false);

        String daemonSecretFile = toml.getOrElse(
                "coins.bitcoin.secret-files.bitcoin-daemon-config-file",
                "./secrets/bitcoin-daemon.conf"
        );
        String walletSecretFile = toml.getOrElse(
                "coins.bitcoin.secret-files.bitcoin-wallet-config-file",
                "./secrets/bitcoin-wallet.conf"
        );

        boolean webUi = toml.getOrElse("coins.bitcoin.auth.web-ui", true);
        boolean restApi = toml.getOrElse("coins.bitcoin.auth.rest-api", true);
        boolean telegram = toml.getOrElse("coins.bitcoin.auth.telegram", false);
        String mcp = toml.getOrElse("coins.bitcoin.auth.mcp", "");
        List<String> mcpAuthChannels = loadMcpAuthChannels(toml, "coins.bitcoin.auth", mcp);

        List<ApprovalRule> autoAccept = readApprovalRules(toml, "coins.bitcoin.auth.auto-accept");
        List<ApprovalRule> autoDeny = readApprovalRules(toml, "coins.bitcoin.auth.auto-deny");

        return new CoinConfig(
                enabled,
                daemonSecretFile,
                walletSecretFile,
                new CoinAuthConfig(autoAccept, autoDeny, webUi, restApi, telegram, mcp, mcpAuthChannels)
        );
    }

    private CoinConfig loadCoinConfig(FileConfig toml, String coinId, String defaultMcp) {
        String coinPrefix = "coins." + coinId;

        boolean enabled = toml.getOrElse(coinPrefix + ".enabled", false);
        boolean webUi = toml.getOrElse(coinPrefix + ".auth.web-ui", true);
        boolean restApi = toml.getOrElse(coinPrefix + ".auth.rest-api", true);
        boolean telegram = toml.getOrElse(coinPrefix + ".auth.telegram", false);
        String mcp = toml.getOrElse(coinPrefix + ".auth.mcp", defaultMcp);
        List<String> mcpAuthChannels = loadMcpAuthChannels(toml, coinPrefix + ".auth", mcp);

        List<ApprovalRule> autoAccept = readApprovalRules(toml, coinPrefix + ".auth.auto-accept");
        List<ApprovalRule> autoDeny = readApprovalRules(toml, coinPrefix + ".auth.auto-deny");

        return new CoinConfig(
                enabled,
                "",
                "",
                new CoinAuthConfig(autoAccept, autoDeny, webUi, restApi, telegram, mcp, mcpAuthChannels)
        );
    }

    private List<String> loadMcpAuthChannels(FileConfig toml, String authPath, String fallbackMcp) {
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

    private List<ApprovalRule> readApprovalRules(FileConfig toml, String keyPath) {
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

    private ApprovalRule parseApprovalRule(Object rawRule, String keyPath, int index) {
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

    private ApprovalCriteria parseCriteria(Object rawCriteria, String keyPath) {
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

    private double parseDouble(Object value, String keyPath) {
        if (value instanceof Number numberValue) {
            return numberValue.doubleValue();
        }

        if (value instanceof String stringValue) {
            try {
                return Double.parseDouble(stringValue.trim());
            } catch (NumberFormatException e) {
                throw new IllegalStateException("Invalid config: " + keyPath + " must be a numeric value.", e);
            }
        }

        throw new IllegalStateException("Invalid config: " + keyPath + " must be a numeric value.");
    }

    private Duration parseDuration(String value, String keyPath) {
        String normalized = value == null ? "" : value.trim();

        Duration duration = parseIsoDurationOrNull(normalized);
        if (duration == null) {
            duration = parseHumanDurationOrNull(normalized);
        }

        if (duration == null) {
            throw new IllegalStateException(
                    "Invalid config: " + keyPath +
                            " must be a duration like '24h', '7d 2h', '7 days and 2 hours' or ISO-8601 (e.g. PT24H)."
            );
        }

        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalStateException("Invalid config: " + keyPath + " must be greater than 0 seconds.");
        }

        return duration;
    }

    private Duration parseIsoDurationOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Duration.parse(value);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private Duration parseHumanDurationOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        String normalized = value.toLowerCase(Locale.ROOT)
                .replace(',', ' ')
                .replaceAll("\\band\\b", " ");

        Matcher matcher = HUMAN_DURATION_PART_PATTERN.matcher(normalized);

        long totalSeconds = 0L;
        int cursor = 0;
        boolean foundAtLeastOnePart = false;

        while (matcher.find()) {
            String gap = normalized.substring(cursor, matcher.start()).trim();
            if (!gap.isEmpty()) {
                return null;
            }

            long amount;
            try {
                amount = Long.parseLong(matcher.group(1));
            } catch (NumberFormatException e) {
                return null;
            }
            if (amount <= 0L) {
                return null;
            }

            long unitSeconds = unitToSeconds(matcher.group(2));
            try {
                totalSeconds = Math.addExact(totalSeconds, Math.multiplyExact(amount, unitSeconds));
            } catch (ArithmeticException e) {
                throw new IllegalStateException("Invalid config: duration value is too large.", e);
            }

            cursor = matcher.end();
            foundAtLeastOnePart = true;
        }

        if (!foundAtLeastOnePart) {
            return null;
        }

        String tail = normalized.substring(cursor).trim();
        if (!tail.isEmpty()) {
            return null;
        }

        return Duration.ofSeconds(totalSeconds);
    }

    private long unitToSeconds(String rawUnit) {
        String unit = rawUnit.toLowerCase(Locale.ROOT);
        return switch (unit) {
            case "w", "week", "weeks" -> 7L * 24L * 60L * 60L;
            case "d", "day", "days" -> 24L * 60L * 60L;
            case "h", "hr", "hrs", "hour", "hours" -> 60L * 60L;
            case "m", "min", "mins", "minute", "minutes" -> 60L;
            case "s", "sec", "secs", "second", "seconds" -> 1L;
            default -> throw new IllegalStateException("Unsupported duration unit: " + rawUnit);
        };
    }

    private void validateBitcoinConfig() {
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
        if (!auth.webUi() && !auth.restApi() && !auth.telegram()) {
            throw new IllegalStateException(
                    "Invalid config: coins.bitcoin.auth must enable at least one channel (web-ui/rest-api/telegram).");
        }

        if (auth.mcpAuthChannels() == null || auth.mcpAuthChannels().isEmpty()) {
            throw new IllegalStateException(
                    "Invalid config: coins.bitcoin.auth must define at least one MCP auth channel via coins.bitcoin.auth.mcp or coins.bitcoin.auth.mcp-auth-channels.");
        }

        for (ApprovalRule rule : auth.autoAccept()) {
            ensureRuleConsistency(rule, "coins.bitcoin.auth.auto-accept");
        }
        for (ApprovalRule rule : auth.autoDeny()) {
            ensureRuleConsistency(rule, "coins.bitcoin.auth.auto-deny");
        }

        CoinAuthCriteriaValidator.validateChannelAvailability(
                "bitcoin",
                auth,
                landingEnabled,
                telegramEnabled
        );
        CoinAuthCriteriaValidator.validateNoContradictions("bitcoin", auth);
    }

    private void validateNonBitcoinCoinConfig(String coinName, CoinConfig coin) {
        if (!coin.enabled()) {
            return;
        }

        CoinAuthConfig auth = coin.auth();
        if (!auth.webUi() && !auth.restApi() && !auth.telegram()) {
            throw new IllegalStateException(
                    "Invalid config: coins." + coinName + ".auth must enable at least one channel (web-ui/rest-api/telegram)."
            );
        }

        if (auth.mcpAuthChannels() == null || auth.mcpAuthChannels().isEmpty()) {
            throw new IllegalStateException(
                    "Invalid config: coins." + coinName + ".auth must define at least one MCP auth channel via coins." + coinName + ".auth.mcp or coins." + coinName + ".auth.mcp-auth-channels."
            );
        }

        for (ApprovalRule rule : auth.autoAccept()) {
            ensureRuleConsistency(rule, "coins." + coinName + ".auth.auto-accept");
        }
        for (ApprovalRule rule : auth.autoDeny()) {
            ensureRuleConsistency(rule, "coins." + coinName + ".auth.auto-deny");
        }

        CoinAuthCriteriaValidator.validateChannelAvailability(
                coinName,
                auth,
                landingEnabled,
                telegramEnabled
        );
        CoinAuthCriteriaValidator.validateNoContradictions(coinName, auth);
    }

    private void ensureRuleConsistency(ApprovalRule rule, String keyPath) {
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

    private void bootstrapSecretFiles() {
        if (!bitcoin.enabled()) {
            return;
        }

        ensureSecretFileExists(
                Path.of(bitcoin.bitcoinDaemonConfigSecretFile()),
                "coins.bitcoin.secret-files.bitcoin-daemon-config-file",
                defaultBitcoinDaemonSecretContent()
        );
        ensureSecretFileExists(
                Path.of(bitcoin.bitcoinWalletConfigSecretFile()),
                "coins.bitcoin.secret-files.bitcoin-wallet-config-file",
                defaultBitcoinWalletSecretContent()
        );
    }

    private void ensureSecretFileExists(Path secretFile, String keyName, String content) {
        if (Files.exists(secretFile)) {
            return;
        }

        try {
            Path parent = secretFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(secretFile, content, StandardCharsets.UTF_8);
            log.warn("Created missing secret file for {} at {}", keyName, secretFile.toAbsolutePath());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create secret file for " + keyName + ": " + secretFile, e);
        }
    }

    private String defaultBitcoinDaemonSecretContent() {
        return """
                # KONKIN Bitcoin daemon secret config
                # Fill with your node RPC credentials.
                rpcuser=REPLACE_WITH_BITCOIN_RPC_USER
                rpcpassword=REPLACE_WITH_BITCOIN_RPC_PASSWORD
                rpcconnect=127.0.0.1
                rpcport=8332
                """;
    }

    private String defaultBitcoinWalletSecretContent() {
        return """
                # KONKIN Bitcoin wallet secret config
                # Fill with your wallet details.
                wallet=REPLACE_WITH_BITCOIN_WALLET_NAME
                wallet-passphrase=REPLACE_WITH_BITCOIN_WALLET_PASSPHRASE
                """;
    }

    private static String normalizeString(Object value) {
        if (value == null) {
            return null;
        }
        return value.toString().trim();
    }

    private void validatePath(String pathValue, String keyName) {
        try {
            Path.of(pathValue);
        } catch (InvalidPathException e) {
            throw new IllegalStateException(
                    "Invalid config: " + keyName + " is not a valid path: " + pathValue,
                    e
            );
        }
    }

    public record CoinConfig(
            boolean enabled,
            String bitcoinDaemonConfigSecretFile,
            String bitcoinWalletConfigSecretFile,
            CoinAuthConfig auth
    ) {
    }

    public record CoinAuthConfig(
            List<ApprovalRule> autoAccept,
            List<ApprovalRule> autoDeny,
            boolean webUi,
            boolean restApi,
            boolean telegram,
            String mcp,
            List<String> mcpAuthChannels
    ) {
    }

    public record ApprovalRule(ApprovalCriteria criteria) {
    }

    public record ApprovalCriteria(CriteriaType type, double value, Duration period) {
    }

    public enum CriteriaType {
        VALUE_GT("value-gt", false),
        VALUE_LT("value-lt", false),
        CUMULATED_VALUE_GT("cumulated-value-gt", true),
        CUMULATED_VALUE_LT("cumulated-value-lt", true);

        private final String tomlValue;
        private final boolean requiresPeriod;

        CriteriaType(String tomlValue, boolean requiresPeriod) {
            this.tomlValue = tomlValue;
            this.requiresPeriod = requiresPeriod;
        }

        public String tomlValue() {
            return tomlValue;
        }

        public boolean requiresPeriod() {
            return requiresPeriod;
        }

        public static CriteriaType fromTomlValue(String rawType) {
            String normalized = rawType.trim()
                    .toLowerCase(Locale.ROOT)
                    .replace("_", "-")
                    .replace(" ", "");

            return switch (normalized) {
                case "value-gt", "value>", "gt", "greater-than" -> VALUE_GT;
                case "value-lt", "value<", "lt", "less-than" -> VALUE_LT;
                case "cumulated-value-gt", "cumulatedvalue-gt", "cumulated>", "cumulated-greater-than" -> CUMULATED_VALUE_GT;
                case "cumulated-value-lt", "cumulatedvalue-lt", "cumulated<", "cumulated-less-than" -> CUMULATED_VALUE_LT;
                default -> throw new IllegalStateException(
                        "Invalid config: unsupported criteria.type='" + rawType +
                                "'. Supported: value-gt, value-lt, cumulated-value-gt, cumulated-value-lt."
                );
            };
        }
    }
}
