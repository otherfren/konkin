package io.konkin.config;

import java.time.Duration;
import java.util.List;

/**
 * Validates auto-accept/auto-deny criteria for contradictions.
 */
public final class CoinAuthCriteriaValidator {

    private CoinAuthCriteriaValidator() {
    }

    public static void validateChannelAvailability(
            String coinName,
            KonkinConfig.CoinAuthConfig auth,
            boolean webUiEnabled,
            boolean restApiEnabled,
            boolean telegramEnabled
    ) {
        if (auth.webUi() && !webUiEnabled) {
            throw new IllegalStateException(
                    "Invalid config: coins." + coinName + ".auth.web-ui=true requires web-ui.enabled=true."
            );
        }

        if (auth.restApi() && !restApiEnabled) {
            throw new IllegalStateException(
                    "Invalid config: coins." + coinName + ".auth.rest-api=true requires rest-api.enabled=true."
            );
        }

        if (auth.telegram() && !telegramEnabled) {
            throw new IllegalStateException(
                    "Invalid config: coins." + coinName + ".auth.telegram=true requires telegram.enabled=true."
            );
        }
    }

    public static void validateNoContradictions(String coinName, KonkinConfig.CoinAuthConfig auth) {
        List<KonkinConfig.ApprovalRule> autoAccept = auth.autoAccept();
        List<KonkinConfig.ApprovalRule> autoDeny = auth.autoDeny();

        for (int acceptIndex = 0; acceptIndex < autoAccept.size(); acceptIndex++) {
            NormalizedCriteria accept = NormalizedCriteria.from(
                    autoAccept.get(acceptIndex).criteria(),
                    "coins.%s.auth.auto-accept[%d].criteria".formatted(coinName, acceptIndex)
            );

            for (int denyIndex = 0; denyIndex < autoDeny.size(); denyIndex++) {
                NormalizedCriteria deny = NormalizedCriteria.from(
                        autoDeny.get(denyIndex).criteria(),
                        "coins.%s.auth.auto-deny[%d].criteria".formatted(coinName, denyIndex)
                );

                if (accept.contradicts(deny)) {
                    throw new IllegalStateException(
                            "Invalid config: contradictory auth criteria for coin '" + coinName +
                                    "' between " + accept.sourcePath + " and " + deny.sourcePath + "."
                    );
                }
            }
        }
    }

    private enum CriteriaDomain {
        VALUE,
        CUMULATED_VALUE
    }

    private record NormalizedCriteria(
            CriteriaDomain domain,
            double lowerExclusive,
            double upperExclusive,
            Duration period,
            String sourcePath
    ) {
        private static NormalizedCriteria from(KonkinConfig.ApprovalCriteria criteria, String sourcePath) {
            return switch (criteria.type()) {
                case VALUE_GT -> new NormalizedCriteria(
                        CriteriaDomain.VALUE,
                        criteria.value(),
                        Double.POSITIVE_INFINITY,
                        null,
                        sourcePath
                );
                case VALUE_LT -> new NormalizedCriteria(
                        CriteriaDomain.VALUE,
                        Double.NEGATIVE_INFINITY,
                        criteria.value(),
                        null,
                        sourcePath
                );
                case CUMULATED_VALUE_GT -> new NormalizedCriteria(
                        CriteriaDomain.CUMULATED_VALUE,
                        criteria.value(),
                        Double.POSITIVE_INFINITY,
                        criteria.period(),
                        sourcePath
                );
                case CUMULATED_VALUE_LT -> new NormalizedCriteria(
                        CriteriaDomain.CUMULATED_VALUE,
                        Double.NEGATIVE_INFINITY,
                        criteria.value(),
                        criteria.period(),
                        sourcePath
                );
            };
        }

        private boolean contradicts(NormalizedCriteria other) {
            if (domain != other.domain) {
                return false;
            }
            if (domain == CriteriaDomain.CUMULATED_VALUE && !period.equals(other.period)) {
                return false;
            }

            double overlapStart = Math.max(lowerExclusive, other.lowerExclusive);
            double overlapEnd = Math.min(upperExclusive, other.upperExclusive);
            return overlapStart < overlapEnd;
        }
    }
}
