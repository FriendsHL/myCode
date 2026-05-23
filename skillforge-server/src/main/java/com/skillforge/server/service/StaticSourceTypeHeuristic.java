package com.skillforge.server.service;

import com.skillforge.server.entity.EvalScenarioEntity;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * EVAL-DATASET-LAYER V1: hand-tuned static per-source-type pass-rate estimate.
 *
 * <p>Numbers come from the project's empirical observation of where each
 * source_type sits in difficulty distribution:
 * <ul>
 *   <li>{@code benchmark} → 0.40 (GAIA Lv1 / τ-bench / AgentBench typical
 *       range 30-50% for a competent baseline)</li>
 *   <li>{@code session_derived} → 0.05 (these are failure-extracted by
 *       construction — baseline by definition struggles with them)</li>
 *   <li>{@code manual} → 0.30 (dogfood scenarios written with known
 *       difficulty, typical 20-40% range)</li>
 * </ul>
 *
 * <p>Expected to be within ±30% of actual observed pass rate. The FE shows
 * this as "30-50% (estimated, may be ±30% off)" until an actual A/B run
 * back-writes a precise value.
 */
@Component
public class StaticSourceTypeHeuristic implements BaselinePassRateHeuristic {

    private static final double RATE_BENCHMARK = 0.40;
    private static final double RATE_SESSION_DERIVED = 0.05;
    private static final double RATE_MANUAL = 0.30;
    private static final double RATE_UNKNOWN = 0.0;

    @Override
    public double estimate(List<EvalScenarioEntity> scenarios) {
        if (scenarios == null || scenarios.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        for (EvalScenarioEntity s : scenarios) {
            sum += rateFor(s.getSourceType());
        }
        return sum / scenarios.size();
    }

    private double rateFor(String sourceType) {
        if (sourceType == null) return RATE_UNKNOWN;
        return switch (sourceType) {
            case EvalScenarioEntity.SOURCE_TYPE_BENCHMARK -> RATE_BENCHMARK;
            case EvalScenarioEntity.SOURCE_TYPE_SESSION_DERIVED -> RATE_SESSION_DERIVED;
            case EvalScenarioEntity.SOURCE_TYPE_MANUAL -> RATE_MANUAL;
            default -> RATE_UNKNOWN;
        };
    }
}
