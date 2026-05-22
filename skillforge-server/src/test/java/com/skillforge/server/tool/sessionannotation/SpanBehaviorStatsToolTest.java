package com.skillforge.server.tool.sessionannotation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.observability.entity.LlmTraceEntity;
import com.skillforge.observability.repository.LlmSpanRepository;
import com.skillforge.observability.repository.LlmTraceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Wiring-level tests for {@link SpanBehaviorStatsTool}. The repository
 * queries are simple JPQL counts; here we lock JSON output shape, threshold
 * derivation (hasToolOveruse / hasLoopInefficiency), and input validation.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SpanBehaviorStatsTool")
class SpanBehaviorStatsToolTest {

    private static final String SESSION_ID = "sess-xyz";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock private LlmSpanRepository spanRepository;
    @Mock private LlmTraceRepository traceRepository;

    private SpanBehaviorStatsTool newTool() {
        return new SpanBehaviorStatsTool(spanRepository, traceRepository, objectMapper);
    }

    @Test
    @DisplayName("with tool spans returns correct counts / topTool / perToolCounts (top 5)")
    void spanBehaviorStats_withToolSpans_returnsCorrectCounts() throws Exception {
        when(spanRepository.countBySessionIdAndKind(SESSION_ID, "llm")).thenReturn(12L);
        when(spanRepository.countBySessionIdAndErrorIsNotNull(SESSION_ID)).thenReturn(3L);
        // Repository returns rows already ORDER BY COUNT(s) DESC — mock honors that.
        when(spanRepository.countToolCallsByNameForSession(SESSION_ID)).thenReturn(List.<Object[]>of(
                new Object[]{"Bash", 8L},
                new Object[]{"ReadFile", 4L},
                new Object[]{"Grep", 2L},
                new Object[]{"WriteFile", 1L},
                new Object[]{"Glob", 1L},
                new Object[]{"WebSearch", 1L}    // 6th tool — must NOT appear in top-5 perToolCounts
        ));
        LlmTraceEntity trace = new LlmTraceEntity();
        trace.setTotalDurationMs(187_000L);
        when(traceRepository.findBySessionIdOrderByStartedAtDesc(SESSION_ID))
                .thenReturn(List.of(trace));

        SkillResult result = newTool().execute(Map.of("sessionId", SESSION_ID),
                new SkillContext(null, null, 0L));

        assertThat(result.isSuccess()).isTrue();
        JsonNode root = objectMapper.readTree(result.getOutput());
        assertThat(root.path("sessionId").asText()).isEqualTo(SESSION_ID);
        assertThat(root.path("totalTurns").asLong()).isEqualTo(12L);
        assertThat(root.path("totalDurationMs").asLong()).isEqualTo(187_000L);
        assertThat(root.path("errorSpanCount").asLong()).isEqualTo(3L);
        assertThat(root.path("topTool").asText()).isEqualTo("Bash");
        assertThat(root.path("topToolCount").asLong()).isEqualTo(8L);
        // 8 < threshold (10) → no overuse; 12 < threshold (20) → no loop inefficiency.
        assertThat(root.path("hasToolOveruse").asBoolean()).isFalse();
        assertThat(root.path("hasLoopInefficiency").asBoolean()).isFalse();

        JsonNode perTool = root.path("perToolCounts");
        assertThat(perTool.isArray()).isTrue();
        assertThat(perTool).hasSize(5);  // top-5 cap
        assertThat(perTool.get(0).path("tool").asText()).isEqualTo("Bash");
        assertThat(perTool.get(0).path("count").asLong()).isEqualTo(8L);
        assertThat(perTool.get(4).path("tool").asText()).isEqualTo("Glob");
    }

    @Test
    @DisplayName("high tool count (>10) sets hasToolOveruse=true")
    void spanBehaviorStats_highToolCount_setsHasToolOveruse() throws Exception {
        when(spanRepository.countBySessionIdAndKind(SESSION_ID, "llm")).thenReturn(5L);
        when(spanRepository.countBySessionIdAndErrorIsNotNull(SESSION_ID)).thenReturn(0L);
        when(spanRepository.countToolCallsByNameForSession(SESSION_ID)).thenReturn(List.<Object[]>of(
                new Object[]{"Bash", 18L},
                new Object[]{"ReadFile", 2L}
        ));
        when(traceRepository.findBySessionIdOrderByStartedAtDesc(SESSION_ID)).thenReturn(List.of());

        SkillResult result = newTool().execute(Map.of("sessionId", SESSION_ID),
                new SkillContext(null, null, 0L));

        assertThat(result.isSuccess()).isTrue();
        JsonNode root = objectMapper.readTree(result.getOutput());
        assertThat(root.path("topTool").asText()).isEqualTo("Bash");
        assertThat(root.path("topToolCount").asLong()).isEqualTo(18L);
        assertThat(root.path("hasToolOveruse").asBoolean()).isTrue();
        // Boundary sanity: turns still under 20 → loop inefficiency stays false.
        assertThat(root.path("hasLoopInefficiency").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("high turn count (>20) sets hasLoopInefficiency=true")
    void spanBehaviorStats_highTurnCount_setsHasLoopInefficiency() throws Exception {
        when(spanRepository.countBySessionIdAndKind(SESSION_ID, "llm")).thenReturn(35L);
        when(spanRepository.countBySessionIdAndErrorIsNotNull(SESSION_ID)).thenReturn(0L);
        when(spanRepository.countToolCallsByNameForSession(SESSION_ID)).thenReturn(List.<Object[]>of(
                new Object[]{"ReadFile", 5L}
        ));
        LlmTraceEntity trace = new LlmTraceEntity();
        trace.setTotalDurationMs(450_000L);  // 7.5 min
        when(traceRepository.findBySessionIdOrderByStartedAtDesc(SESSION_ID))
                .thenReturn(List.of(trace));

        SkillResult result = newTool().execute(Map.of("sessionId", SESSION_ID),
                new SkillContext(null, null, 0L));

        assertThat(result.isSuccess()).isTrue();
        JsonNode root = objectMapper.readTree(result.getOutput());
        assertThat(root.path("totalTurns").asLong()).isEqualTo(35L);
        assertThat(root.path("hasLoopInefficiency").asBoolean()).isTrue();
        // 5 calls < 10 threshold → no overuse.
        assertThat(root.path("hasToolOveruse").asBoolean()).isFalse();
        assertThat(root.path("totalDurationMs").asLong()).isEqualTo(450_000L);
    }

    @Test
    @DisplayName("no spans (empty queries) → all stats zero, flags false, no error")
    void spanBehaviorStats_noSpans_returnsZeroStats() throws Exception {
        when(spanRepository.countBySessionIdAndKind(SESSION_ID, "llm")).thenReturn(0L);
        when(spanRepository.countBySessionIdAndErrorIsNotNull(SESSION_ID)).thenReturn(0L);
        when(spanRepository.countToolCallsByNameForSession(SESSION_ID)).thenReturn(List.<Object[]>of());
        when(traceRepository.findBySessionIdOrderByStartedAtDesc(SESSION_ID)).thenReturn(List.of());

        SkillResult result = newTool().execute(Map.of("sessionId", SESSION_ID),
                new SkillContext(null, null, 0L));

        assertThat(result.isSuccess()).isTrue();
        JsonNode root = objectMapper.readTree(result.getOutput());
        assertThat(root.path("totalTurns").asLong()).isZero();
        assertThat(root.path("totalDurationMs").asLong()).isZero();
        assertThat(root.path("errorSpanCount").asLong()).isZero();
        assertThat(root.path("topTool").isNull()).isTrue();
        assertThat(root.path("topToolCount").asLong()).isZero();
        assertThat(root.path("hasToolOveruse").asBoolean()).isFalse();
        assertThat(root.path("hasLoopInefficiency").asBoolean()).isFalse();
        assertThat(root.path("perToolCounts").isArray()).isTrue();
        assertThat(root.path("perToolCounts")).isEmpty();
    }

    @Test
    @DisplayName("missing / blank sessionId → validation error, no repo calls")
    void spanBehaviorStats_sessionId_validation() {
        // No when(...) stubs — execute must not touch the repos before validating.
        SpanBehaviorStatsTool tool = newTool();

        SkillResult missing = tool.execute(Map.of(), new SkillContext(null, null, 0L));
        assertThat(missing.isSuccess()).isFalse();
        assertThat(missing.getErrorType()).isEqualTo(SkillResult.ErrorType.VALIDATION);
        assertThat(missing.getError()).contains("sessionId");

        SkillResult blank = tool.execute(Map.of("sessionId", "   "),
                new SkillContext(null, null, 0L));
        assertThat(blank.isSuccess()).isFalse();
        assertThat(blank.getErrorType()).isEqualTo(SkillResult.ErrorType.VALIDATION);

        SkillResult nullInput = tool.execute(null, new SkillContext(null, null, 0L));
        assertThat(nullInput.isSuccess()).isFalse();
        assertThat(nullInput.getErrorType()).isEqualTo(SkillResult.ErrorType.VALIDATION);
    }

    @Test
    @DisplayName("NULL tool names from legacy spans are filtered out of perToolCounts")
    void spanBehaviorStats_nullToolNames_filtered() throws Exception {
        when(spanRepository.countBySessionIdAndKind(SESSION_ID, "llm")).thenReturn(3L);
        when(spanRepository.countBySessionIdAndErrorIsNotNull(SESSION_ID)).thenReturn(0L);
        // Explicitly typed so the {null, 5L} row doesn't collapse to List<Object>.
        List<Object[]> legacyRows = java.util.Arrays.asList(
                new Object[]{null, 5L},          // legacy pre-OBS-2 row — skip
                new Object[]{"Bash", 4L}
        );
        when(spanRepository.countToolCallsByNameForSession(SESSION_ID)).thenReturn(legacyRows);
        // unused for this case but lenient stub avoids the "unnecessary stubbing" strict check.
        lenient().when(traceRepository.findBySessionIdOrderByStartedAtDesc(any())).thenReturn(List.of());

        SkillResult result = newTool().execute(Map.of("sessionId", SESSION_ID),
                new SkillContext(null, null, 0L));

        assertThat(result.isSuccess()).isTrue();
        JsonNode root = objectMapper.readTree(result.getOutput());
        JsonNode perTool = root.path("perToolCounts");
        assertThat(perTool).hasSize(1);
        assertThat(perTool.get(0).path("tool").asText()).isEqualTo("Bash");
        // topTool / topToolCount also derived from filtered list.
        assertThat(root.path("topTool").asText()).isEqualTo("Bash");
        assertThat(root.path("topToolCount").asLong()).isEqualTo(4L);
    }

    @Test
    @DisplayName("tool schema declares sessionId required")
    void spanBehaviorStats_schema_declaresRequired() {
        SpanBehaviorStatsTool tool = newTool();
        assertThat(tool.getName()).isEqualTo("SpanBehaviorStats");
        assertThat(tool.isReadOnly()).isTrue();
        Map<String, Object> schema = tool.getToolSchema().getInputSchema();
        assertThat(schema.get("type")).isEqualTo("object");
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) schema.get("required");
        assertThat(required).containsExactly("sessionId");
    }
}
