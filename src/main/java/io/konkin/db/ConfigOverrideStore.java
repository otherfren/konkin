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

package io.konkin.db;

import org.jdbi.v3.core.Jdbi;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Database-backed store for config overrides.
 * Each key is a dotted TOML path (e.g. "server.port"), each value is the JSON-serialized override.
 */
public class ConfigOverrideStore {

    private static final String UPSERT = "MERGE INTO config_overrides (\"key\", \"value\", last_edit) VALUES (:key, :value, :lastEdit)";
    private static final String SELECT_ALL = "SELECT \"key\", \"value\" FROM config_overrides ORDER BY \"key\"";
    private static final String DELETE_KEY = "DELETE FROM config_overrides WHERE \"key\" = :key";
    private static final String DELETE_BY_PREFIX = "DELETE FROM config_overrides WHERE \"key\" LIKE :prefix";
    private static final String DELETE_ALL = "DELETE FROM config_overrides";

    private final Jdbi jdbi;

    public ConfigOverrideStore(DataSource dataSource) {
        this.jdbi = JdbiFactory.create(dataSource);
    }

    public Map<String, String> getAll() {
        return jdbi.withHandle(h -> {
            Map<String, String> result = new LinkedHashMap<>();
            h.createQuery(SELECT_ALL)
                    .map((rs, ctx) -> Map.entry(rs.getString(1), rs.getString("value")))
                    .forEach(e -> result.put(e.getKey(), e.getValue()));
            return result;
        });
    }

    public void putAll(Map<String, String> entries) {
        Instant now = Instant.now();
        jdbi.useHandle(h -> {
            var batch = h.prepareBatch(UPSERT);
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                batch.bind("key", entry.getKey())
                        .bind("value", entry.getValue())
                        .bind("lastEdit", now)
                        .add();
            }
            batch.execute();
        });
    }

    public void delete(String key) {
        jdbi.useHandle(h ->
                h.createUpdate(DELETE_KEY)
                        .bind("key", key)
                        .execute()
        );
    }

    public void deleteByPrefix(String prefix) {
        jdbi.useHandle(h ->
                h.createUpdate(DELETE_BY_PREFIX)
                        .bind("prefix", prefix + "%")
                        .execute()
        );
    }

    public void deleteAll() {
        jdbi.useHandle(h -> h.createUpdate(DELETE_ALL).execute());
    }
}
