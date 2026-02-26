package io.konkin;

import io.javalin.Javalin;
import io.konkin.config.KonkinConfig;
import io.konkin.db.DatabaseManager;
import io.konkin.db.KvStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * KONKIN MCP Server — main entry point.
 * Boots Javalin, H2 + Flyway, and exposes /health and /auth_queue.
 */
public class App {

    private static final Logger log = LoggerFactory.getLogger(App.class);
    private static final String VERSION = "0.1.0";

    public static void main(String[] args) {
        String configPath = args.length > 0 ? args[0] : "config.toml";

        log.info("KONKIN mcp — starting up");

        // 1. Load config
        KonkinConfig config = KonkinConfig.load(configPath);

        // 2. Initialize a database (connection pool + Flyway migration)
        DatabaseManager dbManager = new DatabaseManager(config);
        KvStore kvStore = new KvStore(dbManager.dataSource());

        // 3. Start Javalin server
        Javalin app = Javalin.create(javalinConfig -> {
            javalinConfig.showJavalinBanner = false;
        });

        // /health — basic health check
        app.get("/health", ctx -> {
            ctx.json(Map.of(
                    "status", "healthy",
                    "version", VERSION,
                    "database", "connected"
            ));
        });

        // /auth_queue — placeholder for the HITL approval queue
        app.get("/auth_queue", ctx -> {
            ctx.json(Map.of(
                    "pending", 0,
                    "lockdown_active", false,
                    "message", "Authorization queue is empty. No pending approvals."
            ));
        });

        app.start(config.host(), config.port());

        log.info("KONKIN server running at http://{}:{}", config.host(), config.port());
        log.info("  /health       — health check");
        log.info("  /auth_queue   — approval queue status");

        // Graceful shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("KONKIN shutting down...");
            app.stop();
            dbManager.shutdown();
            log.info("KONKIN shutdown complete.");
        }));
    }
}
