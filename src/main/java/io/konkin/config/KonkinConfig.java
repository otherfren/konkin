package io.konkin.config;

import com.electronwill.nightconfig.core.file.FileConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Loads and holds all configuration from config.toml.
 */
public class KonkinConfig {

    private static final Logger log = LoggerFactory.getLogger(KonkinConfig.class);
    private static final int EXPECTED_CONFIG_VERSION = 1;

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

    private final boolean authQueueEnabled;
    private final boolean authQueuePasswordProtectionEnabled;
    private final String authQueuePasswordFile;

    private final boolean landingEnabled;
    private final boolean landingPasswordProtectionEnabled;
    private final String landingPasswordFile;
    private final String landingTemplateDirectory;
    private final String landingTemplateName;
    private final String landingStaticDirectory;
    private final String landingStaticHostedPath;
    private final boolean landingAutoReloadEnabled;
    private final boolean landingAssetsAutoReloadEnabled;

    private final boolean telegramEnabled;
    private final String telegramSecretFile;
    private final String telegramApiBaseUrl;
    private final List<String> telegramChatIds;

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

        this.authQueueEnabled = toml.getOrElse("auth_queue.enabled", true);
        this.authQueuePasswordProtectionEnabled = toml.getOrElse("auth_queue.password-protection.enabled", true);
        this.authQueuePasswordFile = toml.getOrElse("auth_queue.password-protection.password-file", "./secrets/auth_queue.password");

        this.landingEnabled = toml.getOrElse("landing.enabled", false);
        this.landingPasswordProtectionEnabled = toml.getOrElse("landing.password-protection.enabled", this.landingEnabled);
        this.landingPasswordFile = toml.getOrElse("landing.password-protection.password-file", "./secrets/landing.password");
        this.landingTemplateDirectory = toml.getOrElse("landing.template.directory", "./src/main/resources/templates");
        this.landingTemplateName = toml.getOrElse("landing.template.name", "landing.ftl");
        this.landingStaticDirectory = toml.getOrElse("landing.static.directory", "./src/main/resources/static");
        this.landingStaticHostedPath = toml.getOrElse("landing.static.hosted-path", "/assets");
        this.landingAutoReloadEnabled = toml.getOrElse("landing.auto-reload.enabled", true);
        this.landingAssetsAutoReloadEnabled = toml.getOrElse("landing.auto-reload.assets-enabled", true);

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

            log.info("Configuration loaded — host={}, port={}, db={}", config.host, config.port, config.dbUrl);
            log.info("Logging config — level={}, file={}, rotateMaxSizeMb={}",
                    config.logLevel,
                    config.logFile,
                    config.logRotateMaxSizeMb);
            log.info("Auth queue config — enabled={}, passwordProtection={}", config.authQueueEnabled, config.authQueuePasswordProtectionEnabled);
            log.info("Landing page config — enabled={}, passwordProtection={}, templateDir={}, staticDir={}",
                    config.landingEnabled,
                    config.landingPasswordProtectionEnabled,
                    config.landingTemplateDirectory,
                    config.landingStaticDirectory
            );
            log.info("Telegram config — enabled={}, secretFile={}, configuredChatIds={}",
                    config.telegramEnabled,
                    config.telegramSecretFile,
                    config.telegramChatIds.size());
            return config;
        }
    }

    private void validateConsistency() {
        if (logRotateMaxSizeMb <= 0) {
            throw new IllegalStateException(
                    "Invalid config: server.log-rotate-max-size-mb must be > 0.");
        }

        if (!authQueueEnabled && authQueuePasswordProtectionEnabled) {
            throw new IllegalStateException(
                    "Invalid config: auth_queue.password-protection.enabled=true requires auth_queue.enabled=true.");
        }

        if (authQueuePasswordProtectionEnabled) {
            if (authQueuePasswordFile == null || authQueuePasswordFile.isBlank()) {
                throw new IllegalStateException(
                        "Invalid config: auth_queue.password-protection.password-file must be set when password protection is enabled.");
            }
            validatePath(authQueuePasswordFile, "auth_queue.password-protection.password-file");
        }

        if (!landingEnabled && landingPasswordProtectionEnabled) {
            throw new IllegalStateException(
                    "Invalid config: landing.password-protection.enabled=true requires landing.enabled=true.");
        }

        if (landingEnabled) {
            if (landingTemplateName == null || landingTemplateName.isBlank()) {
                throw new IllegalStateException("Invalid config: landing.template.name must not be blank when landing.enabled=true.");
            }
            if (landingStaticHostedPath == null || landingStaticHostedPath.isBlank() || !landingStaticHostedPath.startsWith("/")) {
                throw new IllegalStateException("Invalid config: landing.static.hosted-path must start with '/' when landing.enabled=true.");
            }

            validatePath(landingTemplateDirectory, "landing.template.directory");
            validatePath(landingStaticDirectory, "landing.static.directory");

            if (landingPasswordProtectionEnabled) {
                if (landingPasswordFile == null || landingPasswordFile.isBlank()) {
                    throw new IllegalStateException(
                            "Invalid config: landing.password-protection.password-file must be set when password protection is enabled.");
                }
                validatePath(landingPasswordFile, "landing.password-protection.password-file");
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

    public boolean authQueueEnabled() { return authQueueEnabled; }
    public boolean authQueuePasswordProtectionEnabled() { return authQueuePasswordProtectionEnabled; }
    public String authQueuePasswordFile() { return authQueuePasswordFile; }

    public boolean landingEnabled() { return landingEnabled; }
    public boolean landingPasswordProtectionEnabled() { return landingPasswordProtectionEnabled; }
    public String landingPasswordFile() { return landingPasswordFile; }
    public String landingTemplateDirectory() { return landingTemplateDirectory; }
    public String landingTemplateName() { return landingTemplateName; }
    public String landingStaticDirectory() { return landingStaticDirectory; }
    public String landingStaticHostedPath() { return landingStaticHostedPath; }
    public boolean landingAutoReloadEnabled() { return landingAutoReloadEnabled; }
    public boolean landingAssetsAutoReloadEnabled() { return landingAssetsAutoReloadEnabled; }

    public boolean telegramEnabled() { return telegramEnabled; }
    public String telegramSecretFile() { return telegramSecretFile; }
    public String telegramApiBaseUrl() { return telegramApiBaseUrl; }
    public List<String> telegramChatIds() { return telegramChatIds; }
}
