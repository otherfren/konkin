package io.konkin;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import io.konkin.config.KonkinConfig;
import io.konkin.db.DatabaseManager;
import io.konkin.db.KvStore;
import io.konkin.web.KonkinWebServer;
import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;

/**
 * KONKIN MCP Server — main entry point.
 * Boots Javalin, H2 + Flyway, and exposes /api/v1/health and optional /api/v1/auth_queue.
 */
public class App {

    private static final Logger log = LoggerFactory.getLogger(App.class);
    private static final String VERSION = "0.1.0";

    public static void main(String[] args) {
        if (args.length != 1 || args[0] == null || args[0].isBlank()) {
            System.err.println("Usage: java -jar konkin-server.jar <config.toml>");
            System.exit(1);
            return;
        }

        String configPath = args[0];

        log.info("KONKIN mcp — starting up");

        // 1. Load config (including consistency checks)
        KonkinConfig config = KonkinConfig.load(configPath);

        // 2. Apply runtime logging settings from config
        applyLoggingConfig(config);

        // 3. Initialize database (connection pool + Flyway migration)
        DatabaseManager dbManager = new DatabaseManager(config);
        new KvStore(dbManager.dataSource()); //will fail hard if something is broken

        // 4. Start web server (services/controllers are assembled in KonkinWebServer)
        KonkinWebServer webServer = new KonkinWebServer(config, VERSION);
        webServer.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("KONKIN shutting down... allowing up to 3 seconds for in-flight requests.");
            webServer.stop();
            dbManager.shutdown();
            log.info("KONKIN shutdown complete.");
        }));
    }

    private static void applyLoggingConfig(KonkinConfig config) {
        System.setProperty("LOG_LEVEL", config.logLevel().toUpperCase());
        System.setProperty("LOG_FILE", config.logFile());
        System.setProperty("LOG_ROTATE_MAX_SIZE_MB", Integer.toString(config.logRotateMaxSizeMb()));

        ILoggerFactory factory = LoggerFactory.getILoggerFactory();
        if (!(factory instanceof LoggerContext loggerContext)) {
            return;
        }

        URL logbackConfig = App.class.getClassLoader().getResource("logback.xml");
        if (logbackConfig == null) {
            return;
        }

        try {
            JoranConfigurator configurator = new JoranConfigurator();
            configurator.setContext(loggerContext);
            loggerContext.reset();
            configurator.doConfigure(logbackConfig);
            log.info("Logging configured from config.toml (file={}, rotateMaxSizeMb={})",
                    config.logFile(),
                    config.logRotateMaxSizeMb());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to apply logback configuration from config.toml", e);
        }
    }
}
