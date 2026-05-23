package com.skillforge.server.service;

import com.skillforge.server.entity.EvalScenarioEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import org.assertj.core.data.Offset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EVAL-DATASET-LAYER V1: unit tests for the static per-source-type heuristic.
 * Verifies the rate table + averaging behaviour stay in sync with the
 * documented baseline expectations (FE displays "estimated, may be ±30%").
 */
@DisplayName("StaticSourceTypeHeuristic")
class StaticSourceTypeHeuristicTest {

    private final StaticSourceTypeHeuristic heuristic = new StaticSourceTypeHeuristic();

    @Test
    @DisplayName("empty list → 0.0 (defensive; service layer rejects empty)")
    void emptyListReturnsZero() {
        assertThat(heuristic.estimate(List.of())).isEqualTo(0.0);
        assertThat(heuristic.estimate(null)).isEqualTo(0.0);
    }

    @Test
    @DisplayName("all benchmark scenarios → 0.40")
    void allBenchmarkScores040() {
        List<EvalScenarioEntity> scenarios = List.of(
                scenarioOf(EvalScenarioEntity.SOURCE_TYPE_BENCHMARK),
                scenarioOf(EvalScenarioEntity.SOURCE_TYPE_BENCHMARK),
                scenarioOf(EvalScenarioEntity.SOURCE_TYPE_BENCHMARK));
        assertThat(heuristic.estimate(scenarios)).isCloseTo(0.40, Offset.offset(1e-9));
    }

    @Test
    @DisplayName("all session_derived → 0.05")
    void allSessionDerivedScores005() {
        List<EvalScenarioEntity> scenarios = List.of(
                scenarioOf(EvalScenarioEntity.SOURCE_TYPE_SESSION_DERIVED),
                scenarioOf(EvalScenarioEntity.SOURCE_TYPE_SESSION_DERIVED));
        assertThat(heuristic.estimate(scenarios)).isCloseTo(0.05, Offset.offset(1e-9));
    }

    @Test
    @DisplayName("all manual → 0.30")
    void allManualScores030() {
        List<EvalScenarioEntity> scenarios = List.of(
                scenarioOf(EvalScenarioEntity.SOURCE_TYPE_MANUAL),
                scenarioOf(EvalScenarioEntity.SOURCE_TYPE_MANUAL));
        assertThat(heuristic.estimate(scenarios)).isCloseTo(0.30, Offset.offset(1e-9));
    }

    @Test
    @DisplayName("mixed source_types average correctly (3 benchmark + 1 session_derived)")
    void mixedSourceTypesAverage() {
        List<EvalScenarioEntity> scenarios = List.of(
                scenarioOf(EvalScenarioEntity.SOURCE_TYPE_BENCHMARK),
                scenarioOf(EvalScenarioEntity.SOURCE_TYPE_BENCHMARK),
                scenarioOf(EvalScenarioEntity.SOURCE_TYPE_BENCHMARK),
                scenarioOf(EvalScenarioEntity.SOURCE_TYPE_SESSION_DERIVED));
        // (0.40 + 0.40 + 0.40 + 0.05) / 4 = 0.3125
        assertThat(heuristic.estimate(scenarios)).isCloseTo(0.3125, Offset.offset(1e-9));
    }

    @Test
    @DisplayName("unknown source_type contributes 0 (graceful fallback)")
    void unknownSourceTypeContributesZero() {
        List<EvalScenarioEntity> scenarios = List.of(
                scenarioOf(EvalScenarioEntity.SOURCE_TYPE_BENCHMARK),
                scenarioOf("WHATEVER"));
        // (0.40 + 0.0) / 2 = 0.20
        assertThat(heuristic.estimate(scenarios)).isCloseTo(0.20, Offset.offset(1e-9));
    }

    private static EvalScenarioEntity scenarioOf(String sourceType) {
        EvalScenarioEntity s = new EvalScenarioEntity();
        s.setSourceType(sourceType);
        return s;
    }
}
