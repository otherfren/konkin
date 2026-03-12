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

package io.konkin.approval;

import io.konkin.config.ApprovalCriteria;
import io.konkin.config.ApprovalRule;
import io.konkin.config.CoinAuthConfig;
import io.konkin.config.CriteriaType;
import io.konkin.db.ApprovalRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class ApprovalPolicyEvaluator {

    private static final Logger log = LoggerFactory.getLogger(ApprovalPolicyEvaluator.class);

    private ApprovalPolicyEvaluator() {}

    public record PolicyDecision(String action, String reasonCode, String reasonText) {
        public static final PolicyDecision MANUAL = new PolicyDecision("manual", "queued_for_approval", "Request accepted and queued for approval");

        public boolean isAutoResolved() {
            return !"manual".equals(action);
        }

        public String state() {
            return switch (action) {
                case "auto_denied" -> "DENIED";
                case "auto_accepted" -> "APPROVED";
                default -> "QUEUED";
            };
        }
    }

    /**
     * Evaluate auto-deny and auto-accept rules against a request.
     * Auto-deny rules are checked first (deny is stronger than accept).
     *
     * @param authConfig the coin's auth configuration containing rules
     * @param coin       coin identifier for cumulated queries
     * @param amount     request amount, or null for sweep requests
     * @param requestRepo repository for cumulated amount queries
     * @param now        current timestamp
     * @return policy decision indicating auto_denied, auto_accepted, or manual
     */
    public static PolicyDecision evaluate(
            CoinAuthConfig authConfig,
            String coin,
            BigDecimal amount,
            ApprovalRequestRepository requestRepo,
            Instant now
    ) {
        if (authConfig == null) {
            return PolicyDecision.MANUAL;
        }

        // Auto-deny is stronger than auto-accept — check deny rules first
        List<ApprovalRule> denyRules = authConfig.autoDeny();
        if (denyRules != null) {
            for (ApprovalRule rule : denyRules) {
                if (matches(rule.criteria(), coin, amount, requestRepo, now)) {
                    String description = describeRule(rule.criteria());
                    log.info("Auto-deny rule matched for {} {}: {}", coin, amount, description);
                    return new PolicyDecision("auto_denied", "policy_auto_denied",
                            "Auto-denied by rule: " + description);
                }
            }
        }

        // Then check auto-accept rules
        List<ApprovalRule> acceptRules = authConfig.autoAccept();
        if (acceptRules != null) {
            for (ApprovalRule rule : acceptRules) {
                if (matches(rule.criteria(), coin, amount, requestRepo, now)) {
                    String description = describeRule(rule.criteria());
                    log.info("Auto-accept rule matched for {} {}: {}", coin, amount, description);
                    return new PolicyDecision("auto_accepted", "policy_auto_accepted",
                            "Auto-accepted by rule: " + description);
                }
            }
        }

        return PolicyDecision.MANUAL;
    }

    static boolean matches(
            ApprovalCriteria criteria,
            String coin,
            BigDecimal amount,
            ApprovalRequestRepository requestRepo,
            Instant now
    ) {
        if (criteria == null) return false;

        CriteriaType type = criteria.type();
        BigDecimal threshold = BigDecimal.valueOf(criteria.value());

        return switch (type) {
            case VALUE_GT -> amount != null && amount.compareTo(threshold) > 0;
            case VALUE_LT -> amount != null && amount.compareTo(threshold) < 0;
            case CUMULATED_VALUE_GT -> {
                if (criteria.period() == null || requestRepo == null) yield false;
                Instant since = now.minus(criteria.period());
                BigDecimal cumulated = requestRepo.sumRecentAmounts(coin, since);
                BigDecimal total = amount != null ? cumulated.add(amount) : cumulated;
                yield total.compareTo(threshold) > 0;
            }
            case CUMULATED_VALUE_LT -> {
                if (criteria.period() == null || requestRepo == null) yield false;
                Instant since = now.minus(criteria.period());
                BigDecimal cumulated = requestRepo.sumRecentAmounts(coin, since);
                BigDecimal total = amount != null ? cumulated.add(amount) : cumulated;
                yield total.compareTo(threshold) < 0;
            }
        };
    }

    static String describeRule(ApprovalCriteria criteria) {
        String op = switch (criteria.type()) {
            case VALUE_GT -> "amount > " + criteria.value();
            case VALUE_LT -> "amount < " + criteria.value();
            case CUMULATED_VALUE_GT -> "cumulated amount > " + criteria.value() + " within " + criteria.period();
            case CUMULATED_VALUE_LT -> "cumulated amount < " + criteria.value() + " within " + criteria.period();
        };
        return op;
    }
}
