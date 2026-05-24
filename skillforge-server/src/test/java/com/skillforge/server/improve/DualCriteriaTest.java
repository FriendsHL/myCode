package com.skillforge.server.improve;

import com.skillforge.server.entity.BehaviorRuleAbRunEntity;
import com.skillforge.server.improve.behavior.BehaviorRuleAbEvalService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BEHAVIOR-RULE-AB-EVAL V1 — INV-5 dual-criteria gate formula coverage.
 *
 * <p>Formula:
 * {@code (target_delta >= TARGET_DELTA_THRESHOLD_PP OR target_delta IS NULL)
 *        AND regression_delta >= REGRESSION_DELTA_FLOOR_PP}
 *
 * <p>Constants source: {@link BehaviorRuleAbEvalService}
 * ({@code TARGET=10.0pp}, {@code REGRESSION_FLOOR=-3.0pp}).
 */
@DisplayName("Dual-criteria gate formula (INV-5)")
class DualCriteriaTest {

    @DisplayName("8 boundary cases — target × regression matrix")
    @ParameterizedTest(name = "[{index}] target={0} regression={1} → satisfied={2}")
    @CsvSource(value = {
            // target=NULL: only regression matters
            "NULL, -2.0, true",     // fallback OK: regression just above floor
            "NULL, -3.0, true",     // fallback OK: exactly at floor (inclusive)
            "NULL, -4.0, false",    // fallback fails: regression below floor

            // target present
            "11.0, 0.0,  true",     // target above threshold + regression neutral
            "10.0, 0.0,  true",     // exactly at target threshold (inclusive)
            "9.0,  0.0,  false",    // target below threshold
            "11.0, -4.0, false",    // target ok but regression too negative
            "9.0,  -4.0, false",    // both fail
    }, nullValues = {"NULL"})
    void dualCriteria_satisfied_per_matrix(Double targetDelta, Double regressionDelta, boolean expected) {
        BehaviorRuleAbRunEntity run = new BehaviorRuleAbRunEntity();
        run.setTargetDeltaPp(targetDelta);
        run.setRegressionDeltaPp(regressionDelta);

        boolean actual = BehaviorRulePromotionService.isDualCriteriaSatisfied(run);
        assertThat(actual).isEqualTo(expected);
    }

    @DisplayName("regression_delta = null → always false (cannot judge without regression signal)")
    @org.junit.jupiter.api.Test
    void null_regression_always_false() {
        BehaviorRuleAbRunEntity run = new BehaviorRuleAbRunEntity();
        run.setRegressionDeltaPp(null);
        run.setTargetDeltaPp(50.0); // target through the roof, doesn't matter

        assertThat(BehaviorRulePromotionService.isDualCriteriaSatisfied(run)).isFalse();
    }

    @DisplayName("null entity → false (defensive)")
    @org.junit.jupiter.api.Test
    void null_entity_returns_false() {
        assertThat(BehaviorRulePromotionService.isDualCriteriaSatisfied(null)).isFalse();
    }
}
