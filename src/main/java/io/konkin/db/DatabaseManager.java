package io.konkin.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.konkin.config.KonkinConfig;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;

/**
 * Manages the H2 database connection pool (HikariCP) and Flyway migrations.
 * <p>
 * Use the {@link #DatabaseManager(KonkinConfig)} constructor for production, which creates
 * its own HikariCP pool and runs Flyway. Use {@link #DatabaseManager(DataSource)} to wrap
 * an externally-managed DataSource (e.g. a shared test pool) — no pool or migration work
 * is performed, and {@link #shutdown()} becomes a no-op.
 */
public class DatabaseManager {

    private static final Logger log = LoggerFactory.getLogger(DatabaseManager.class);

    private final DataSource dataSource;
    private final HikariDataSource ownedPool;

    public DatabaseManager(KonkinConfig config) {
        log.info("Initializing database connection pool — url={}, poolSize={}", config.dbUrl(), config.dbPoolSize());

        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(config.dbUrl());
        hikari.setUsername(config.dbUser());
        hikari.setPassword(config.dbPassword());
        hikari.setMaximumPoolSize(config.dbPoolSize());
        hikari.setMinimumIdle(1);
        hikari.setPoolName("konkin-pool");
        hikari.setConnectionTestQuery("SELECT 1");

        this.ownedPool = new HikariDataSource(hikari);
        this.dataSource = this.ownedPool;

        runMigrations();
    }

    /**
     * Wraps an externally-managed DataSource. No pool is created and no Flyway
     * migrations are executed. {@link #shutdown()} is a no-op for this instance.
     */
    public DatabaseManager(DataSource externalDataSource) {
        this.dataSource = externalDataSource;
        this.ownedPool = null;
    }

    private void runMigrations() {
        log.info("Running Flyway database migrations...");
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load();
        var result = flyway.migrate();
        log.info("Flyway migrations complete — {} migrations applied", result.migrationsExecuted);
    }

    public DataSource dataSource() {
        return dataSource;
    }

    public void shutdown() {
        if (ownedPool == null) {
            return;
        }
        log.info("Shutting down database connection pool");
        if (!ownedPool.isClosed()) {
            ownedPool.close();
        }
    }
}
