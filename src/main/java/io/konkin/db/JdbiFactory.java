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
import org.jdbi.v3.core.argument.AbstractArgumentFactory;
import org.jdbi.v3.core.argument.Argument;
import org.jdbi.v3.core.config.ConfigRegistry;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;

/**
 * Creates a configured {@link Jdbi} instance for the given {@link DataSource}.
 * Registers Instant ↔ TIMESTAMP mapping so all DAOs can use {@link Instant} directly.
 */
public final class JdbiFactory {

    private JdbiFactory() {
    }

    public static Jdbi create(DataSource dataSource) {
        Jdbi jdbi = Jdbi.create(dataSource);

        jdbi.registerColumnMapper(Instant.class, (rs, col, ctx) -> {
            Timestamp ts = rs.getTimestamp(col);
            return ts == null ? null : ts.toInstant();
        });

        jdbi.registerArgument(new InstantArgumentFactory());

        return jdbi;
    }

    private static final class InstantArgumentFactory extends AbstractArgumentFactory<Instant> {

        InstantArgumentFactory() {
            super(Types.TIMESTAMP);
        }

        @Override
        protected Argument build(Instant value, ConfigRegistry config) {
            return (pos, stmt, ctx) -> stmt.setTimestamp(pos, value == null ? null : Timestamp.from(value));
        }
    }
}