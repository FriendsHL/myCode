package com.skillforge.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link WorkflowJsonExtractor#tolerantReadTree} — the shared tolerant JSON
 * extraction used by BOTH the live {@code agent()} path and the journal-replay
 * path. Covers pure JSON / code fences / prose-embedded JSON (incl. braces
 * inside string literals) / arrays / the exact reasoning-model regression / and
 * the null contract.
 */
@DisplayName("WorkflowJsonExtractor")
class WorkflowJsonExtractorTest {

    private final ObjectMapper om = new ObjectMapper();

    @Test
    @DisplayName("pure JSON parses (fast path)")
    void pureJson() {
        JsonNode n = WorkflowJsonExtractor.tolerantReadTree("{\"x\":\"hi\"}", om);
        assertThat(n).isNotNull();
        assertThat(n.get("x").asText()).isEqualTo("hi");
    }

    @Test
    @DisplayName("```json fenced JSON parses")
    void codeFence() {
        JsonNode n = WorkflowJsonExtractor.tolerantReadTree("```json\n{\"x\":\"hi\"}\n```", om);
        assertThat(n).isNotNull();
        assertThat(n.get("x").asText()).isEqualTo("hi");
    }

    @Test
    @DisplayName("JSON embedded in prose is extracted; braces inside strings don't break balance")
    void proseEmbedded() {
        JsonNode n = WorkflowJsonExtractor.tolerantReadTree(
                "Here is the result: {\"x\":\"a {nested} brace\"}. Done.", om);
        assertThat(n).isNotNull();
        assertThat(n.get("x").asText()).isEqualTo("a {nested} brace");
    }

    @Test
    @DisplayName("top-level array recovered from fence + prose")
    void arrayFromProse() {
        JsonNode n = WorkflowJsonExtractor.tolerantReadTree("Sure! ```json\n[1, 2, 3]\n``` done", om);
        assertThat(n).isNotNull();
        assertThat(n.isArray()).isTrue();
        assertThat(n.size()).isEqualTo(3);
    }

    @Test
    @DisplayName("exact mimo regression: 'Now let me analyze… {json}' is recovered")
    void reasoningPrefixRegression() {
        String raw = "Now let me analyze the data and produce the summaryJson.\n\n"
                + "{\"topIssues\":[{\"id\":\"issue-1\",\"title\":\"ReadFile bug\"}],\"summary\":\"2 issues\"}";
        JsonNode n = WorkflowJsonExtractor.tolerantReadTree(raw, om);
        assertThat(n).isNotNull();
        assertThat(n.get("summary").asText()).isEqualTo("2 issues");
        assertThat(n.get("topIssues").get(0).get("id").asText()).isEqualTo("issue-1");
    }

    @Test
    @DisplayName("completely non-JSON → null")
    void nonJson() {
        assertThat(WorkflowJsonExtractor.tolerantReadTree("not json at all", om)).isNull();
    }

    @Test
    @DisplayName("null / blank / null mapper → null")
    void nullContract() {
        assertThat(WorkflowJsonExtractor.tolerantReadTree(null, om)).isNull();
        assertThat(WorkflowJsonExtractor.tolerantReadTree("   ", om)).isNull();
        assertThat(WorkflowJsonExtractor.tolerantReadTree("{\"x\":1}", null)).isNull();
    }
}
