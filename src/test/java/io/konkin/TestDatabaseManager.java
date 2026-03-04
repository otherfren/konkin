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

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.konkin.db.JdbiFactory;
import org.flywaydb.core.Flyway;

import javax.sql.DataSource;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Shared test database utility. Creates isolated H2 in-memory databases with
 * Flyway migrations applied. Each unique {@code dbName} gets its own pool,
 * making it safe for parallel test execution across classes.
 */
public final class TestDatabaseManager {

    private static final ConcurrentMap<String, HikariDataSource> POOLS = new ConcurrentHashMap<>();

    private TestDatabaseManager() {}

    /** Returns a DataSource for the given database name, creating it on first access. */
    public static DataSource dataSource(String dbName) {
        return POOLS.computeIfAbsent(dbName, TestDatabaseManager::createDataSource);
    }

    /** Deletes all rows from every table on the given DataSource. */
    public static void truncateAll(DataSource dataSource) {
        JdbiFactory.create(dataSource).useHandle(h -> {
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

    private static HikariDataSource createDataSource(String dbName) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:h2:mem:" + dbName + ";DB_CLOSE_DELAY=-1");
        cfg.setUsername("sa");
        cfg.setPassword("");
        cfg.setMaximumPoolSize(10);
        cfg.setMinimumIdle(1);
        cfg.setPoolName(dbName + "-pool");
        cfg.setConnectionTestQuery("SELECT 1");
        HikariDataSource ds = new HikariDataSource(cfg);

        Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        return ds;
    }
}