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
 */
public class DatabaseManager {

    private static final Logger log = LoggerFactory.getLogger(DatabaseManager.class);

    private final HikariDataSource dataSource;

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

        this.dataSource = new HikariDataSource(hikari);

        runMigrations();
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
        log.info("Shutting down database connection pool");
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }
}
