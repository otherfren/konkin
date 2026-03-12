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

import io.konkin.approval.ApprovalPolicyEvaluator.PolicyDecision;
import io.konkin.config.ApprovalCriteria;
import io.konkin.config.ApprovalRule;
import io.konkin.config.CoinAuthConfig;
import io.konkin.config.CriteriaType;
import io.konkin.db.ApprovalRequestRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApprovalPolicyEvaluatorTest {

    private static final Instant NOW = Instant.now();

    // --- No rules → manual ---

    @Test
    void noRules_returnsManual() {
        CoinAuthConfig auth = authConfig(List.of(), List.of());
        PolicyDecision decision = ApprovalPolicyEvaluator.evaluate(auth, "litecoin", new BigDecimal("1.0"), null, NOW);
        assertEquals("manual", decision.action());
        assertEquals("QUEUED", decision.state());
        assertFalse(decision.isAutoResolved());
    }

    @Test
    void nullAuthConfig_returnsManual() {
        PolicyDecision decision = ApprovalPolicyEvaluator.evaluate(null, "litecoin", new BigDecimal("1.0"), null, NOW);
        assertEquals("manual", decision.action());
    }

    @Test
    void nullRuleLists_returnsManual() {
        CoinAuthConfig auth = new CoinAuthConfig(null, null, false, false, false, null, List.of(), 1, List.of());
        PolicyDecision decision = ApprovalPolicyEvaluator.evaluate(auth, "litecoin", new BigDecimal("1.0"), null, NOW);
        assertEquals("manual", decision.action());
    }

    // --- VALUE_GT auto-deny ---

    @Test
    void autoDeny_valueGt_matchesWhenAboveThreshold() {
        CoinAuthConfig auth = authConfig(List.of(), List.of(rule(CriteriaType.VALUE_GT, 0.5)));
        PolicyDecision decision = ApprovalPolicyEvaluator.evaluate(auth, "litecoin", new BigDecimal("0.6"), null, NOW);
        assertEquals("auto_denied", decision.action());
        assertEquals("DENIED", decision.state());
        assertTrue(decision.isAutoResolved());
        assertTrue(decision.reasonText().contains("amount > 0.5"));
    }

    @Test
    void autoDeny_valueGt_doesNotMatchWhenBelowThreshold() {
        CoinAuthConfig auth = authConfig(List.of(), List.of(rule(CriteriaType.VALUE_GT, 0.5)));
        PolicyDecision decision = ApprovalPolicyEvaluator.evaluate(auth, "litecoin", new BigDecimal("0.3"), null, NOW);
        assertEquals("manual", decision.action());
    }

    @Test
    void autoDeny_valueGt_doesNotMatchWhenEqualToThreshold() {
        CoinAuthConfig auth = authConfig(List.of(), List.of(rule(CriteriaType.VALUE_GT, 0.5)));
        PolicyDecision decision = ApprovalPolicyEvaluator.evaluate(auth, "litecoin", new BigDecimal("0.5"), null, NOW);
        assertEquals("manual", decision.action());
    }

    // --- VALUE_LT auto-accept ---

    @Test
    void autoAccept_valueLt_matchesWhenBelowThreshold() {
        CoinAuthConfig auth = authConfig(List.of(rule(CriteriaType.VALUE_LT, 0.1)), List.of());
        PolicyDecision decision = ApprovalPolicyEvaluator.evaluate(auth, "bitcoin", new BigDecimal("0.05"), null, NOW);
        assertEquals("auto_accepted", decision.action());
        assertEquals("APPROVED", decision.state());
        assertTrue(decision.isAutoResolved());
    }

    @Test
    void autoAccept_valueLt_doesNotMatchWhenAboveThreshold() {
        CoinAuthConfig auth = authConfig(List.of(rule(CriteriaType.VALUE_LT, 0.1)), List.of());
        PolicyDecision decision = ApprovalPolicyEvaluator.evaluate(auth, "bitcoin", new BigDecimal("0.2"), null, NOW);
        assertEquals("manual", decision.action());
    }

    // --- Deny takes precedence over accept ---

    @Test
    void autoDeny_takesPrecedenceOverAutoAccept() {
        // Both rules match: deny > 0.01 AND accept < 1.0
        ApprovalRule denyRule = rule(CriteriaType.VALUE_GT, 0.01);
        ApprovalRule acceptRule = rule(CriteriaType.VALUE_LT, 1.0);
        CoinAuthConfig auth = authConfig(List.of(acceptRule), List.of(denyRule));

        PolicyDecision decision = ApprovalPolicyEvaluator.evaluate(auth, "monero", new BigDecimal("0.5"), null, NOW);
        assertEquals("auto_denied", decision.action());
    }

    // --- CUMULATED_VALUE_GT ---

    @Test
    void autoDeny_cumulatedValueGt_matchesWhenTotalExceedsThreshold() {
        ApprovalRequestRepository repo = mock(ApprovalRequestRepository.class);
        when(repo.sumRecentAmounts(eq("litecoin"), any(Instant.class)))
                .thenReturn(new BigDecimal("0.10"));

        ApprovalRule denyRule = cumulatedRule(CriteriaType.CUMULATED_VALUE_GT, 0.13, Duration.ofHours(1));
        CoinAuthConfig auth = authConfig(List.of(), List.of(denyRule));

        // 0.10 existing + 0.05 new = 0.15 > 0.13
        PolicyDecision decision = ApprovalPolicyEvaluator.evaluate(auth, "litecoin", new BigDecimal("0.05"), repo, NOW);
        assertEquals("auto_denied", decision.action());
        assertTrue(decision.reasonText().contains("cumulated amount > 0.13"));
    }

    @Test
    void autoDeny_cumulatedValueGt_doesNotMatchWhenBelowThreshold() {
        ApprovalRequestRepository repo = mock(ApprovalRequestRepository.class);
        when(repo.sumRecentAmounts(eq("litecoin"), any(Instant.class)))
                .thenReturn(new BigDecimal("0.02"));

        ApprovalRule denyRule = cumulatedRule(CriteriaType.CUMULATED_VALUE_GT, 0.13, Duration.ofHours(1));
        CoinAuthConfig auth = authConfig(List.of(), List.of(denyRule));

        // 0.02 existing + 0.05 new = 0.07 < 0.13
        PolicyDecision decision = ApprovalPolicyEvaluator.evaluate(auth, "litecoin", new BigDecimal("0.05"), repo, NOW);
        assertEquals("manual", decision.action());
    }

    // --- CUMULATED_VALUE_LT ---

    @Test
    void autoAccept_cumulatedValueLt_matchesWhenBelowThreshold() {
        ApprovalRequestRepository repo = mock(ApprovalRequestRepository.class);
        when(repo.sumRecentAmounts(eq("bitcoin"), any(Instant.class)))
                .thenReturn(new BigDecimal("0.01"));

        ApprovalRule acceptRule = cumulatedRule(CriteriaType.CUMULATED_VALUE_LT, 0.5, Duration.ofHours(24));
        CoinAuthConfig auth = authConfig(List.of(acceptRule), List.of());

        // 0.01 existing + 0.02 new = 0.03 < 0.5
        PolicyDecision decision = ApprovalPolicyEvaluator.evaluate(auth, "bitcoin", new BigDecimal("0.02"), repo, NOW);
        assertEquals("auto_accepted", decision.action());
    }

    @Test
    void autoAccept_cumulatedValueLt_doesNotMatchWhenAboveThreshold() {
        ApprovalRequestRepository repo = mock(ApprovalRequestRepository.class);
        when(repo.sumRecentAmounts(eq("bitcoin"), any(Instant.class)))
                .thenReturn(new BigDecimal("0.48"));

        ApprovalRule acceptRule = cumulatedRule(CriteriaType.CUMULATED_VALUE_LT, 0.5, Duration.ofHours(24));
        CoinAuthConfig auth = authConfig(List.of(acceptRule), List.of());

        // 0.48 existing + 0.05 new = 0.53 > 0.5
        PolicyDecision decision = ApprovalPolicyEvaluator.evaluate(auth, "bitcoin", new BigDecimal("0.05"), repo, NOW);
        assertEquals("manual", decision.action());
    }

    // --- Sweep requests (null amount) ---

    @Test
    void sweep_nullAmount_skipsValueGtRule() {
        CoinAuthConfig auth = authConfig(List.of(), List.of(rule(CriteriaType.VALUE_GT, 0.01)));
        PolicyDecision decision = ApprovalPolicyEvaluator.evaluate(auth, "litecoin", null, null, NOW);
        assertEquals("manual", decision.action());
    }

    @Test
    void sweep_nullAmount_skipsValueLtRule() {
        CoinAuthConfig auth = authConfig(List.of(rule(CriteriaType.VALUE_LT, 100.0)), List.of());
        PolicyDecision decision = ApprovalPolicyEvaluator.evaluate(auth, "litecoin", null, null, NOW);
        assertEquals("manual", decision.action());
    }

    @Test
    void sweep_nullAmount_cumulatedRulesStillWork() {
        ApprovalRequestRepository repo = mock(ApprovalRequestRepository.class);
        when(repo.sumRecentAmounts(eq("litecoin"), any(Instant.class)))
                .thenReturn(new BigDecimal("5.0"));

        ApprovalRule denyRule = cumulatedRule(CriteriaType.CUMULATED_VALUE_GT, 1.0, Duration.ofHours(1));
        CoinAuthConfig auth = authConfig(List.of(), List.of(denyRule));

        // sweep with null amount: cumulated 5.0 + 0 = 5.0 > 1.0
        PolicyDecision decision = ApprovalPolicyEvaluator.evaluate(auth, "litecoin", null, repo, NOW);
        assertEquals("auto_denied", decision.action());
    }

    // --- Multiple rules: first matching wins ---

    @Test
    void multipleRules_firstMatchingWins() {
        ApprovalRule rule1 = rule(CriteriaType.VALUE_GT, 10.0); // won't match 0.5
        ApprovalRule rule2 = rule(CriteriaType.VALUE_GT, 0.1);  // matches 0.5
        CoinAuthConfig auth = authConfig(List.of(), List.of(rule1, rule2));

        PolicyDecision decision = ApprovalPolicyEvaluator.evaluate(auth, "bitcoin", new BigDecimal("0.5"), null, NOW);
        assertEquals("auto_denied", decision.action());
        assertTrue(decision.reasonText().contains("0.1"));
    }

    // --- Cumulated with null period or null repo ---

    @Test
    void cumulatedRule_withNullPeriod_doesNotMatch() {
        ApprovalRule broken = new ApprovalRule(new ApprovalCriteria(CriteriaType.CUMULATED_VALUE_GT, 0.1, null));
        CoinAuthConfig auth = authConfig(List.of(), List.of(broken));
        PolicyDecision decision = ApprovalPolicyEvaluator.evaluate(auth, "litecoin", new BigDecimal("1.0"), null, NOW);
        assertEquals("manual", decision.action());
    }

    @Test
    void cumulatedRule_withNullRepo_doesNotMatch() {
        ApprovalRule denyRule = cumulatedRule(CriteriaType.CUMULATED_VALUE_GT, 0.1, Duration.ofHours(1));
        CoinAuthConfig auth = authConfig(List.of(), List.of(denyRule));
        PolicyDecision decision = ApprovalPolicyEvaluator.evaluate(auth, "litecoin", new BigDecimal("1.0"), null, NOW);
        assertEquals("manual", decision.action());
    }

    // --- PolicyDecision state mapping ---

    @Test
    void policyDecision_stateMapping() {
        assertEquals("DENIED", new PolicyDecision("auto_denied", "", "").state());
        assertEquals("APPROVED", new PolicyDecision("auto_accepted", "", "").state());
        assertEquals("QUEUED", PolicyDecision.MANUAL.state());
    }

    // --- Helpers ---

    private static ApprovalRule rule(CriteriaType type, double value) {
        return new ApprovalRule(new ApprovalCriteria(type, value, null));
    }

    private static ApprovalRule cumulatedRule(CriteriaType type, double value, Duration period) {
        return new ApprovalRule(new ApprovalCriteria(type, value, period));
    }

    private static CoinAuthConfig authConfig(List<ApprovalRule> accept, List<ApprovalRule> deny) {
        return new CoinAuthConfig(accept, deny, false, false, false, null, List.of(), 1, List.of());
    }
}
