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
import java.util.Optional;

/**
 * Simple key/value store backed by the kv_store table.
 * <p>
 * Note: the column is named {@code "key"} (quoted in DDL) because {@code key} is a reserved
 * word in H2. All SQL here quotes it accordingly.
 */
public class KvStore {

    private static final String SELECT_VALUE  = "SELECT \"value\" FROM kv_store WHERE \"key\" = :key";
    private static final String UPSERT        = "MERGE INTO kv_store (\"key\", \"value\", last_edit) VALUES (:key, :value, :lastEdit)";
    private static final String DELETE_KEY    = "DELETE FROM kv_store WHERE \"key\" = :key";
    private static final String SELECT_ALL    = "SELECT \"key\", \"value\" FROM kv_store ORDER BY last_edit DESC";

    private final Jdbi jdbi;

    public KvStore(DataSource dataSource) {
        this.jdbi = JdbiFactory.create(dataSource);
    }

    public Optional<String> get(String key) {
        return jdbi.withHandle(h ->
                h.createQuery(SELECT_VALUE)
                        .bind("key", key)
                        .mapTo(String.class)
                        .findOne()
        );
    }

    public void put(String key, String value) {
        jdbi.useHandle(h ->
                h.createUpdate(UPSERT)
                        .bind("key", key)
                        .bind("value", value)
                        .bind("lastEdit", Instant.now())
                        .execute()
        );
    }

    public boolean delete(String key) {
        return jdbi.withHandle(h ->
                h.createUpdate(DELETE_KEY)
                        .bind("key", key)
                        .execute() > 0
        );
    }

    public Map<String, String> listAll() {
        return jdbi.withHandle(h -> {
            Map<String, String> result = new LinkedHashMap<>();
            h.createQuery(SELECT_ALL)
                    .map((rs, ctx) -> Map.entry(rs.getString(1), rs.getString("value")))
                    .forEach(e -> result.put(e.getKey(), e.getValue()));
            return result;
        });
    }
}