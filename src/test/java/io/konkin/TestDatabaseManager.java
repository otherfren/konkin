package io.konkin;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.konkin.db.JdbiFactory;
import org.flywaydb.core.Flyway;

import javax.sql.DataSource;

/**
 * Shared test database utility. Creates one H2 in-memory database with Flyway
 * migrations applied once in a static initializer. Use {@link #truncateAll()}
 * to reset all table data between tests without dropping the schema.
 */
public final class TestDatabaseManager {

    private static final HikariDataSource DATA_SOURCE;

    static {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:h2:mem:konkin-test;DB_CLOSE_DELAY=-1");
        cfg.setUsername("sa");
        cfg.setPassword("");
        cfg.setMaximumPoolSize(10);
        cfg.setMinimumIdle(1);
        cfg.setPoolName("konkin-test-pool");
        cfg.setConnectionTestQuery("SELECT 1");
        DATA_SOURCE = new HikariDataSource(cfg);

        Flyway.configure()
                .dataSource(DATA_SOURCE)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    private TestDatabaseManager() {}

    /** Returns the shared test DataSource (single in-memory H2 database). */
    public static DataSource dataSource() {
        return DATA_SOURCE;
    }

    /** Deletes all rows from every table, respecting foreign-key ordering. */
    public static void truncateAll() {
        JdbiFactory.create(DATA_SOURCE).useHandle(h -> {
            h.execute("DELETE FROM approval_execution_attempts");
            h.execute("DELETE FROM approval_votes");
            h.execute("DELETE FROM approval_request_channels");
            h.execute("DELETE FROM approval_state_transitions");
            h.execute("DELETE FROM approval_coin_runtime");
            h.execute("DELETE FROM approval_requests");
            h.execute("DELETE FROM approval_channels");
            h.execute("DELETE FROM agent_tokens");
            h.execute("DELETE FROM kv_store");
        });
    }
}
