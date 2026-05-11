package com.skillforge.server.eval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("EvalScoreFormula")
class EvalScoreFormulaTest {

    @Test
    @DisplayName("calculate: combines quality efficiency latency and cost with M4_V2 weights")
    void calculate_combinesDimensionScoresWithM4Weights() {
        EvalScoreFormula.Result result = EvalScoreFormula.calculate(
                80.0,
                70.0,
                12_000L,
                10_000L,
                new BigDecimal("0.006"),
                new BigDecimal("0.010"),
                4,
                3
        );

        assertThat(result.formulaVersion()).isEqualTo("M4_V2");
        assertThat(result.qualityScore()).isEqualTo(80.0);
        assertThat(result.efficiencyScore()).isEqualTo(70.0);
        assertThat(result.latencyScore()).isEqualTo(80.0);
        assertThat(result.costScore()).isEqualTo(100.0);
        assertThat(result.compositeScore()).isEqualTo(80.0);
        assertThat(result.dimensionStatus()).containsEntry("latency", "measured");
        assertThat(result.breakdownJson()).contains("\"formulaVersion\":\"M4_V2\"");
        assertThat(result.breakdownJson()).contains("\"quality\":0.55");
        assertThat(result.breakdownJson()).contains("\"latencyMs\":12000");
        assertThat(result.breakdownJson()).contains("\"costUsd\":0.006");
        assertThat(result.breakdownJson()).contains("\"qualityFloorApplied\":false");
        assertThat(result.breakdownJson()).contains(
                "\"dimensionStatus\":{\"quality\":\"measured\",\"efficiency\":\"measured\",\"latency\":\"measured\",\"cost\":\"measured\"}");
    }

    @Test
    @DisplayName("calculate: low quality caps composite below pass threshold")
    void calculate_lowQualityCapsCompositeBelowPassThreshold() {
        EvalScoreFormula.Result result = EvalScoreFormula.calculate(
                20.0,
                100.0,
                1_000L,
                10_000L,
                new BigDecimal("0.001"),
                new BigDecimal("0.010"),
                1,
                1
        );

        assertThat(result.compositeScore()).isEqualTo(39.0);
        assertThat(result.breakdownJson()).contains("\"qualityFloorApplied\":true");
    }

    @Test
    @DisplayName("calculate: latency threshold null → latency omitted, composite re-weighted across 3 measured dims")
    void latencyThresholdNull_omitsLatencyDimension_compositeReweighted() {
        EvalScoreFormula.Result result = EvalScoreFormula.calculate(
                80.0,
                70.0,
                5_000L,
                null,                     // ← no performanceThresholdMs configured
                new BigDecimal("0.006"),
                new BigDecimal("0.010"),
                4,
                3
        );

        // Latency dimension is not_measured: score = null, dimensionStatus reflects it.
        assertThat(result.latencyScore()).isNull();
        assertThat(result.dimensionStatus()).containsEntry("latency", "not_measured");
        assertThat(result.dimensionStatus()).containsEntry("quality", "measured");
        assertThat(result.dimensionStatus()).containsEntry("efficiency", "measured");
        assertThat(result.dimensionStatus()).containsEntry("cost", "measured");

        // Composite re-normalised: (80*0.55 + 70*0.20 + 100*0.10) / 0.85
        //                       = (44 + 14 + 10) / 0.85 = 68 / 0.85 = 80.0
        assertThat(result.compositeScore()).isEqualTo(80.0);

        // breakdownJson writes latency score as JSON null, not 0 / 100, and includes dimensionStatus.
        assertThat(result.breakdownJson()).contains("\"latency\":null,");
        assertThat(result.breakdownJson()).contains("\"dimensionStatus\":");
        assertThat(result.breakdownJson()).contains("\"latency\":\"not_measured\"");
    }

    @Test
    @DisplayName("calculate: latency threshold 0 (primitive long default) → treated as not_measured")
    void latencyThresholdZero_omitsLatencyDimension() {
        EvalScoreFormula.Result result = EvalScoreFormula.calculate(
                80.0,
                70.0,
                5_000L,
                0L,                       // ← primitive long default, also unmeasurable
                new BigDecimal("0.006"),
                new BigDecimal("0.010"),
                4,
                3
        );

        assertThat(result.latencyScore()).isNull();
        assertThat(result.dimensionStatus()).containsEntry("latency", "not_measured");
        // Same re-normalised composite as the null-threshold case.
        assertThat(result.compositeScore()).isEqualTo(80.0);
    }

    @Test
    @DisplayName("calculate: latency threshold set → latency participates in full-weight composite")
    void latencyThresholdSet_includesLatency_compositeFullWeight() {
        EvalScoreFormula.Result result = EvalScoreFormula.calculate(
                80.0,
                70.0,
                5_000L,                   // observed
                10_000L,                  // threshold ⇒ latencyScore = 100
                new BigDecimal("0.006"),
                new BigDecimal("0.010"),
                4,
                3
        );

        assertThat(result.latencyScore()).isEqualTo(100.0);
        assertThat(result.dimensionStatus()).containsEntry("latency", "measured");
        // composite = 80*0.55 + 70*0.20 + 100*0.15 + 100*0.10 = 44 + 14 + 15 + 10 = 83.0
        assertThat(result.compositeScore()).isEqualTo(83.0);
    }

    @Test
    @DisplayName("calculate: quality floor still caps composite when latency is not_measured")
    void notMeasuredLatency_qualityFloorStillApplies() {
        EvalScoreFormula.Result result = EvalScoreFormula.calculate(
                20.0,                     // low quality (< 30 floor threshold)
                100.0,
                5_000L,
                null,                     // ← not measured
                new BigDecimal("0.001"),
                new BigDecimal("0.010"),
                1,
                1
        );

        // Without floor: composite = (20*0.55 + 100*0.20 + 100*0.10) / 0.85
        //              = (11 + 20 + 10) / 0.85 = 41 / 0.85 ≈ 48.24 → would be > 39
        // With quality floor (< 30): capped to 39.0.
        assertThat(result.latencyScore()).isNull();
        assertThat(result.compositeScore()).isEqualTo(39.0);
        assertThat(result.breakdownJson()).contains("\"qualityFloorApplied\":true");
    }
}
