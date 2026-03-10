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

package io.konkin;

import io.konkin.config.ConfigManager;
import io.konkin.config.KonkinConfig;
import io.konkin.config.LoggingConfigurator;
import io.konkin.db.ConfigOverrideStore;
import io.konkin.db.DatabaseManager;
import io.konkin.db.KvStore;
import io.konkin.web.KonkinWebServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * KONKIN MCP Server — main entry point.
 * Boots Javalin, H2 + Flyway, and exposes /api/v1/health plus optional landing web routes.
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

        // 3. Create ConfigManager with DB-backed overrides
        ConfigOverrideStore overrideStore = new ConfigOverrideStore(dbManager.dataSource());
        ConfigManager configManager = new ConfigManager(config, overrideStore);

        // 4. Apply runtime logging settings from config
        LoggingConfigurator.applyLoggingConfig(config);
        configManager.addListener(new LoggingConfigurator());

        // 5. Start web server (services/controllers are assembled in KonkinWebServer)
        KonkinWebServer webServer = new KonkinWebServer(configManager, VERSION, dbManager.dataSource());
        webServer.start();

        if (!webServer.isRunning()) {
            log.warn("KONKIN did not start the HTTP server because startup prerequisites are not satisfied.");
            log.warn("Shutting down gracefully. Fix the reported configuration/secret issue and restart KONKIN.");
            dbManager.shutdown();
            return;
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("KONKIN shutting down... allowing up to 3 seconds for in-flight requests.");
            webServer.stop();
            dbManager.shutdown();
            log.info("KONKIN shutdown complete.");
        }));
    }
}