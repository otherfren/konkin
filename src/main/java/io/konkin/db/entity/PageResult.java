package io.konkin.db.entity;

import java.util.List;

public record PageResult<T>(
        List<T> rows,
        int page,
        int pageSize,
        long totalRows,
        int totalPages,
        String sortBy,
        String sortDir
) {
}
