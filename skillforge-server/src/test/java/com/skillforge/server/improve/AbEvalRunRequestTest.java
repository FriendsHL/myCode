package com.skillforge.server.improve;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EVAL-DATASET-LAYER V1 (★ r4 D3 fix ★): record validation tests for
 * {@link AbEvalRunRequest}. Locks the mutual-exclusivity invariant between
 * {@link AbEvalRunRequest#evalScenarioIds()} and
 * {@link AbEvalRunRequest#datasetVersionId()}.
 */
@DisplayName("AbEvalRunRequest record")
class AbEvalRunRequestTest {

    @Test
    @DisplayName("happy path: dataset-version only")
    void datasetVersionOnly() {
        AbEvalRunRequest req = new AbEvalRunRequest(
                "5", null, "candidate-v1", null, "ds-version-abc");
        assertThat(req.datasetVersionId()).isEqualTo("ds-version-abc");
        assertThat(req.evalScenarioIds()).isNull();
    }

    @Test
    @DisplayName("happy path: explicit scenarios only")
    void scenariosOnly() {
        AbEvalRunRequest req = new AbEvalRunRequest(
                "5", null, "candidate-v1", List.of("s1", "s2"), null);
        assertThat(req.evalScenarioIds()).hasSize(2);
        assertThat(req.datasetVersionId()).isNull();
    }

    @Test
    @DisplayName("happy path: both null → ephemeral fallback signal")
    void bothNullIsAllowed() {
        AbEvalRunRequest req = new AbEvalRunRequest(
                "5", null, "candidate-v1", null, null);
        assertThat(req.evalScenarioIds()).isNull();
        assertThat(req.datasetVersionId()).isNull();
    }

    @Test
    @DisplayName("agentId null → NPE")
    void agentIdRequired() {
        assertThatThrownBy(() -> new AbEvalRunRequest(
                null, null, "candidate-v1", null, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("agentId blank → IllegalArgumentException")
    void agentIdBlankRejected() {
        assertThatThrownBy(() -> new AbEvalRunRequest(
                "   ", null, "candidate-v1", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("agentId");
    }

    @Test
    @DisplayName("candidateVersionId null → NPE")
    void candidateVersionIdRequired() {
        assertThatThrownBy(() -> new AbEvalRunRequest(
                "5", null, null, null, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("evalScenarioIds AND datasetVersionId both supplied → IllegalArgumentException")
    void mutuallyExclusiveRejected() {
        assertThatThrownBy(() -> new AbEvalRunRequest(
                "5", null, "candidate-v1", List.of("s1"), "ds-version-abc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mutually exclusive");
    }

    @Test
    @DisplayName("empty scenario list + non-null datasetVersionId is OK (not ambiguous)")
    void emptyScenarioListPlusDatasetVersionAllowed() {
        AbEvalRunRequest req = new AbEvalRunRequest(
                "5", null, "candidate-v1", List.of(), "ds-version-abc");
        assertThat(req.datasetVersionId()).isEqualTo("ds-version-abc");
    }
}
