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
