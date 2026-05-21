package com.skillforge.server.tool.attribution;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.server.attribution.AttributionDispatcherService;
import com.skillforge.server.attribution.AttributionDispatcherService.CandidateEntry;
import com.skillforge.server.attribution.AttributionDispatcherService.CandidateListResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ListAttributionCandidatesTool}. Covers:
 * <ul>
 *   <li>default {@code max} = 10 when input omits the param;</li>
 *   <li>input clamping at both bounds ({@link
 *       ListAttributionCandidatesTool#MIN_MAX} = 1 floor for the {@code 0}
 *       edge case, {@link ListAttributionCandidatesTool#MAX_MAX} = 20 ceiling
 *       for the over-large case);</li>
 *   <li>JSON output shape: top-level keys ({@code ok}, {@code candidates},
 *       {@code total_scanned}, {@code filtered_out}, {@code reserved_count})
 *       plus per-candidate entry keys ({@code patternId},
 *       {@code sentinelEventId}, {@code signature}, {@code outcome},
 *       {@code surface}, {@code memberCount}, {@code lastSeenAt});</li>
 *   <li>W4 fix: service-throws-exception → {@link SkillResult#error(String)}
 *       with prefix {@code "ListAttributionCandidates: "} so the dispatcher
 *       LLM's STEP 4 summary step still has structured output to react to;</li>
 *   <li>empty candidates list: service returns no candidates → tool still emits
 *       a well-formed JSON envelope without crashing.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ListAttributionCandidatesToolTest {

    @Mock private AttributionDispatcherService dispatcherService;

    // JavaTimeModule registered to mirror the project-wide Spring ObjectMapper
    // — keeps the wire shape on Instant fields identical between unit tests
    // and what the LLM actually sees at runtime (footgun #1).
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private ListAttributionCandidatesTool tool;

    @BeforeEach
    void setUp() {
        tool = new ListAttributionCandidatesTool(dispatcherService, objectMapper);
    }

    @Test
    @DisplayName("default: empty input → service called with max=10 + JSON output carries top-level + per-candidate fields")
    void execute_defaultMax_callsServiceWithTen_andEmitsFullJson() throws Exception {
        Instant ts = Instant.parse("2026-05-21T09:30:00Z");
        CandidateEntry entry = new CandidateEntry(42L, 999L, "sig-42",
                "failure", "prompt", 5, ts);
        when(dispatcherService.listAndReserveCandidates(anyInt(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new CandidateListResult(List.of(entry), 12, 7));

        SkillResult result = tool.execute(Map.of(), null);

        ArgumentCaptor<Integer> cap = ArgumentCaptor.forClass(Integer.class);
        verify(dispatcherService).listAndReserveCandidates(cap.capture(), org.mockito.ArgumentMatchers.any());
        assertThat(cap.getValue()).isEqualTo(ListAttributionCandidatesTool.DEFAULT_MAX);

        assertThat(result.isSuccess()).isTrue();
        Map<String, Object> payload = objectMapper.readValue(
                result.getOutput(), new TypeReference<Map<String, Object>>() {});
        assertThat(payload).containsEntry("ok", true);
        assertThat(payload).containsEntry("total_scanned", 12);
        assertThat(payload).containsEntry("filtered_out", 7);
        assertThat(payload).containsEntry("reserved_count", 1);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) payload.get("candidates");
        assertThat(candidates).hasSize(1);
        Map<String, Object> row = candidates.get(0);
        assertThat(row).containsEntry("patternId", 42);
        assertThat(row).containsEntry("sentinelEventId", 999);
        assertThat(row).containsEntry("signature", "sig-42");
        assertThat(row).containsEntry("outcome", "failure");
        assertThat(row).containsEntry("surface", "prompt");
        assertThat(row).containsEntry("memberCount", 5);
        // Instant is serialised via JavaTimeModule as ISO-8601 (configured on
        // the project ObjectMapper); we only assert the row carries the key
        // — exact wire shape is a Jackson contract, not this tool's surface.
        assertThat(row).containsKey("lastSeenAt");
    }

    @Test
    @DisplayName("clamping ceiling: max=999 → service called with MAX_MAX=20")
    void execute_overlargeMax_clampsToMaxMax() {
        when(dispatcherService.listAndReserveCandidates(anyInt(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new CandidateListResult(List.of(), 0, 0));

        tool.execute(Map.of("max", 999), null);

        ArgumentCaptor<Integer> cap = ArgumentCaptor.forClass(Integer.class);
        verify(dispatcherService).listAndReserveCandidates(cap.capture(), org.mockito.ArgumentMatchers.any());
        assertThat(cap.getValue()).isEqualTo(ListAttributionCandidatesTool.MAX_MAX);
    }

    @Test
    @DisplayName("clamping floor: max=0 → service called with MIN_MAX=1 (NOT silently rewritten to service default 5)")
    void execute_zeroMax_clampsToMinMax() {
        when(dispatcherService.listAndReserveCandidates(anyInt(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new CandidateListResult(List.of(), 0, 0));

        tool.execute(Map.of("max", 0), null);

        ArgumentCaptor<Integer> cap = ArgumentCaptor.forClass(Integer.class);
        verify(dispatcherService).listAndReserveCandidates(cap.capture(), org.mockito.ArgumentMatchers.any());
        // Caller asked "small" — honored with smallest legal cap (1), not the
        // service-side "0 → fall back to default" branch. Same predictability
        // contract as the predecessor DispatchAttributionPatternsTool.
        assertThat(cap.getValue()).isEqualTo(ListAttributionCandidatesTool.MIN_MAX);
    }

    @Test
    @DisplayName("output shape: all 5 top-level keys present even when service returns empty candidates")
    void execute_emptyCandidates_stillCarriesAllTopLevelFields() throws Exception {
        when(dispatcherService.listAndReserveCandidates(anyInt(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new CandidateListResult(List.of(), 0, 0));

        SkillResult result = tool.execute(Map.of("max", 7), null);

        assertThat(result.isSuccess()).isTrue();
        Map<String, Object> payload = objectMapper.readValue(
                result.getOutput(), new TypeReference<Map<String, Object>>() {});
        // FLYWHEEL-PER-AGENT-RUN-NOW (2026-05-21): agent_id_filter key always
        // present in the payload (null on cron path, populated on on-demand).
        assertThat(payload.keySet()).containsExactlyInAnyOrder(
                "ok", "candidates", "total_scanned", "filtered_out", "reserved_count", "agent_id_filter");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) payload.get("candidates");
        assertThat(candidates).isEmpty();
        assertThat(payload).containsEntry("reserved_count", 0);
    }

    @Test
    @DisplayName("W4 fix: service throws RuntimeException → SkillResult.error with 'ListAttributionCandidates:' prefix")
    void execute_serviceThrows_returnsSkillResultError() {
        when(dispatcherService.listAndReserveCandidates(anyInt(), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new RuntimeException("db down"));

        SkillResult result = tool.execute(new HashMap<>(), null);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getOutput()).isNull();
        assertThat(result.getError()).startsWith("ListAttributionCandidates:");
        assertThat(result.getError()).contains("db down");
    }

    // ─── FLYWHEEL-PER-AGENT-RUN-NOW (2026-05-21): scope-filter input ──────

    @Test
    @DisplayName("agent_id_filter omitted → service called with null agentId (cron behavior)")
    void execute_noAgentIdFilter_passesNullToService() {
        when(dispatcherService.listAndReserveCandidates(anyInt(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new CandidateListResult(List.of(), 0, 0));

        tool.execute(Map.of("max", 10), null);

        ArgumentCaptor<Long> agentIdCap = ArgumentCaptor.forClass(Long.class);
        verify(dispatcherService).listAndReserveCandidates(anyInt(), agentIdCap.capture());
        assertThat(agentIdCap.getValue()).isNull();
    }

    @Test
    @DisplayName("agent_id_filter=42 → service called with 42L (on-demand scope active)")
    void execute_positiveAgentIdFilter_passesIdToService() throws Exception {
        when(dispatcherService.listAndReserveCandidates(eq(10), eq(42L)))
                .thenReturn(new CandidateListResult(List.of(), 0, 0));

        SkillResult result = tool.execute(Map.of("max", 10, "agent_id_filter", 42), null);

        assertThat(result.isSuccess()).isTrue();
        verify(dispatcherService).listAndReserveCandidates(eq(10), eq(42L));
        // JSON payload echoes back the agent_id_filter the tool actually used —
        // important for the dispatcher's STEP 4 summary so the operator sees
        // whether the scoped path or the cron path executed.
        Map<String, Object> payload = new ObjectMapper().registerModule(new JavaTimeModule())
                .readValue(result.getOutput(),
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        assertThat(payload).containsEntry("agent_id_filter", 42);
    }

    @Test
    @DisplayName("agent_id_filter=0 / negative / non-numeric → drops to no filter (defensive — null/0/neg all collapse)")
    void execute_invalidAgentIdFilter_collapsesToNull() {
        when(dispatcherService.listAndReserveCandidates(anyInt(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new CandidateListResult(List.of(), 0, 0));

        tool.execute(Map.of("max", 10, "agent_id_filter", 0), null);
        tool.execute(Map.of("max", 10, "agent_id_filter", -5), null);
        tool.execute(Map.of("max", 10, "agent_id_filter", "not-a-number"), null);

        ArgumentCaptor<Long> agentIdCap = ArgumentCaptor.forClass(Long.class);
        verify(dispatcherService, org.mockito.Mockito.times(3))
                .listAndReserveCandidates(anyInt(), agentIdCap.capture());
        // All three invocations collapsed to null — the tool refuses to honor
        // sentinel values that silently disable the filter (matches the
        // DetectSignalAnnotationsTool contract on agent_id parsing).
        assertThat(agentIdCap.getAllValues()).containsOnlyNulls();
    }
}
