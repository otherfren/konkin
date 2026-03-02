package io.konkin.config;

import java.time.Duration;

public record ApprovalCriteria(CriteriaType type, double value, Duration period) {
}
