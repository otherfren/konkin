package io.konkin.db;

import org.jdbi.v3.core.Jdbi;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Package-private SQL utility methods shared across repository classes.
 */
final class SqlUtils {

    static final int DEFAULT_PAGE_SIZE = 25;
    static final int MAX_PAGE_SIZE = 200;

    private SqlUtils() {
    }

    static int normalizePageSize(int pageSize) {
        if (pageSize <= 0) return DEFAULT_PAGE_SIZE;
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }

    static int normalizePage(int page, int totalPages) {
        if (totalPages <= 0) return 1;
        if (page <= 0) return 1;
        return Math.min(page, totalPages);
    }

    static String normalizeSortDirection(String sortDir) {
        return "asc".equalsIgnoreCase(sortDir) ? "ASC" : "DESC";
    }

    static String normalizeExactFilter(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    static long queryCount(Jdbi jdbi, String sql) {
        return queryCount(jdbi, sql, List.of());
    }

    static long queryCount(Jdbi jdbi, String sql, List<String> params) {
        return jdbi.withHandle(h -> {
            var query = h.createQuery(sql);
            for (int i = 0; i < params.size(); i++) {
                query.bind(i, params.get(i));
            }
            return query.mapTo(Long.class).one();
        });
    }

    static <T> void append(Map<String, List<T>> map, String key, T item) {
        map.computeIfAbsent(key, ignored -> new ArrayList<>()).add(item);
    }
}
