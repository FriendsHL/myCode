package com.skillforge.server.improve;

import com.skillforge.server.entity.SkillEvalHistoryEntity;
import com.skillforge.server.eval.EvalScoreFormula;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * M4_V2 (eval-latency-not-measured): pins the null-safe aggregation contract
 * extracted from {@link SkillAbEvalService#runBaselineOnly} into
 * {@link SkillAbEvalService#aggregateBaselineHistory}. The end-to-end
 * happy-path remains covered by the BE-2 self-improve loop test (full mocked
 * scenario pipeline); this class isolates the aggregation maths so a
 * null-latency sample cannot regress the latency average to NaN / NPE without
 * a failing test.
 */
@DisplayName("SkillAbEvalService.aggregateBaselineHistory")
class SkillAbEvalServiceAggregateBaselineHistoryTest {

    /** Helper: build a M4_V2-shaped {@link EvalScoreFormula.Result} skipping the formula entirely. */
    private static EvalScoreFormula.Result result(double composite, double quality,
                                                  double efficiency, Double latency, double cost) {
        Map<String, String> dimensionStatus = Map.of(
                "quality", "measured",
                "efficiency", "measured",
                "latency", latency == null ? "not_measured" : "measured",
                "cost", "measured");
        return new EvalScoreFormula.Result(
                "M4_V2",
                quality,
                efficiency,
                latency,
                cost,
                composite,
                "{}",
                dimensionStatus);
    }

    @Test
    @DisplayName("empty input → composite=0, dimension scores left null (INV-4 evolve trigger)")
    void empty_setsZeroComposite() {
        SkillEvalHistoryEntity h = SkillAbEvalService.aggregateBaselineHistory(List.of());

        assertThat(h.getCompositeScore()).isEqualTo(0.0);
        assertThat(h.getQualityScore()).isNull();
        assertThat(h.getEfficiencyScore()).isNull();
        assertThat(h.getLatencyScore()).isNull();
        assertThat(h.getCostScore()).isNull();
    }

    @Test
    @DisplayName("mixed measured / not_measured latency: latency average uses measured count only, others use total count")
    void mixedLatency_averagesByMeasuredSamplesOnly() {
        SkillEvalHistoryEntity h = SkillAbEvalService.aggregateBaselineHistory(List.of(
                result(80.0, 80.0, 70.0, 100.0, 100.0),
                result(60.0, 60.0, 50.0, null,  80.0),   // ← not_measured latency
                result(70.0, 70.0, 60.0, 60.0,  90.0)
        ));

        // composite / quality / efficiency / cost: divide by 3.
        assertThat(h.getCompositeScore()).isEqualTo((80.0 + 60.0 + 70.0) / 3.0);
        assertThat(h.getQualityScore()).isEqualTo((80.0 + 60.0 + 70.0) / 3.0);
        assertThat(h.getEfficiencyScore()).isEqualTo((70.0 + 50.0 + 60.0) / 3.0);
        assertThat(h.getCostScore()).isEqualTo((100.0 + 80.0 + 90.0) / 3.0);

        // latency: only 2 measured samples → (100 + 60) / 2 = 80.0, NOT 53.33 (which would be the 3-sample wrong-divisor bug).
        assertThat(h.getLatencyScore()).isEqualTo(80.0);
    }

    @Test
    @DisplayName("every sample latency=null → latency average persisted as null (no NaN / no NPE)")
    void allNotMeasured_persistsNullLatency() {
        SkillEvalHistoryEntity h = SkillAbEvalService.aggregateBaselineHistory(List.of(
                result(60.0, 60.0, 50.0, null, 80.0),
                result(70.0, 70.0, 60.0, null, 90.0)
        ));

        // Other dimensions still computed from 2 samples.
        assertThat(h.getCompositeScore()).isEqualTo(65.0);
        assertThat(h.getQualityScore()).isEqualTo(65.0);
        assertThat(h.getEfficiencyScore()).isEqualTo(55.0);
        assertThat(h.getCostScore()).isEqualTo(85.0);

        // Latency: zero measured samples → null persisted, not 0.0, not NaN.
        assertThat(h.getLatencyScore()).isNull();
    }

    @Test
    @DisplayName("all measured: latency average uses sample count directly")
    void allMeasured_averagesNormally() {
        SkillEvalHistoryEntity h = SkillAbEvalService.aggregateBaselineHistory(List.of(
                result(80.0, 80.0, 70.0, 100.0, 100.0),
                result(70.0, 70.0, 60.0,  60.0,  90.0)
        ));

        assertThat(h.getLatencyScore()).isEqualTo(80.0, within(1e-9));
    }
}
