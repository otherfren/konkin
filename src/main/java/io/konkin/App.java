package io.konkin;

import io.konkin.config.KonkinConfig;
import io.konkin.db.DatabaseManager;
import io.konkin.db.KvStore;
import io.konkin.web.KonkinWebServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

        // 2. Initialize database (connection pool + Flyway migration)
        DatabaseManager dbManager = new DatabaseManager(config);
        new KvStore(dbManager.dataSource()); //will fail hard if something is broken

        // 3. Start web server (services/controllers are assembled in KonkinWebServer)
        KonkinWebServer webServer = new KonkinWebServer(config, VERSION);
        webServer.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("KONKIN shutting down... allowing up to 3 seconds for in-flight requests.");
            webServer.stop();
            dbManager.shutdown();
            log.info("KONKIN shutdown complete.");
        }));
    }
}
