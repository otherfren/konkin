package io.konkin.db.entity;

import java.util.List;

public record LogQueueFilterOptions(
        List<String> coins,
        List<String> tools,
        List<String> states
) {
}
