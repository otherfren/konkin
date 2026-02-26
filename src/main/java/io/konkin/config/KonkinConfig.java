package io.konkin.config;

import com.electronwill.nightconfig.core.file.FileConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

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
    private final String dbUrl;
    private final String dbUser;
    private final String dbPassword;
    private final int dbPoolSize;

    private KonkinConfig(FileConfig toml) {
        this.configVersion = toml.getIntOrElse("config-version", -1);
        this.host = toml.getOrElse("server.host", "127.0.0.1");
        this.port = toml.getIntOrElse("server.port", 7070);
        this.logLevel = toml.getOrElse("server.log-level", "info");
        this.logFile = toml.getOrElse("server.log-file", "./logs/konkin.log");
        this.dbUrl = toml.getOrElse("database.url", "jdbc:h2:./data/konkin");
        this.dbUser = toml.getOrElse("database.user", "konkin");
        this.dbPassword = toml.getOrElse("database.password", "konkin");
        this.dbPoolSize = toml.getIntOrElse("database.pool-size", 5);
    }

    /**
     * Load configuration from a TOML file path.
     * Fails fast on missing or incompatible config-version.
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
            log.info("Configuration loaded — host={}, port={}, db={}", config.host, config.port, config.dbUrl);
            return config;
        }
    }

    public int configVersion() { return configVersion; }
    public String host() { return host; }
    public int port() { return port; }
    public String logLevel() { return logLevel; }
    public String logFile() { return logFile; }
    public String dbUrl() { return dbUrl; }
    public String dbUser() { return dbUser; }
    public String dbPassword() { return dbPassword; }
    public int dbPoolSize() { return dbPoolSize; }
}
