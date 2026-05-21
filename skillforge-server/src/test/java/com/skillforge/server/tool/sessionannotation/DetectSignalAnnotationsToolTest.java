package com.skillforge.server.tool.sessionannotation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.server.sessionannotation.SessionAnnotationSignalService;
import com.skillforge.server.sessionannotation.SessionAnnotationSignalService.SessionNeedingLlmDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Wiring-level tests for {@link DetectSignalAnnotationsTool}. The signal-service
 * tests own the main behavior; here we lock JSON output shape + window_hours
 * threading + sensible default + clamping.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DetectSignalAnnotationsTool")
class DetectSignalAnnotationsToolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock private SessionAnnotationSignalService signalService;

    @Test
    @DisplayName("execute calls service with default 1h window and returns the §4.3 JSON shape")
    void execute_callsServiceAndReturnsJsonShape() throws Exception {
        when(signalService.detectAndPersist(Duration.ofHours(1), (Long) null)).thenReturn(5);
        when(signalService.findSessionsNeedingLlmAnnotation(anyInt(), org.mockito.ArgumentMatchers.<Long>any())).thenReturn(List.of(
                new SessionNeedingLlmDto("sess-A", "agent#1", List.of("tool_failure", "has_tool_calls")),
                new SessionNeedingLlmDto("sess-B", "agent#2", List.of("agent_error"))));

        DetectSignalAnnotationsTool tool = new DetectSignalAnnotationsTool(signalService, objectMapper);
        SkillResult result = tool.execute(Map.of(), new SkillContext(null, null, 0L));

        assertThat(result.isSuccess()).isTrue();
        JsonNode root = objectMapper.readTree(result.getOutput());
        assertThat(root.path("signal_count").asInt()).isEqualTo(5);
        assertThat(root.path("window_hours").asInt()).isEqualTo(1);
        // FLYWHEEL-PER-AGENT-RUN-NOW: cron path → agent_id key present + null.
        assertThat(root.path("agent_id").isNull()).isTrue();
        JsonNode q = root.path("sessions_needing_llm");
        assertThat(q.isArray()).isTrue();
        assertThat(q).hasSize(2);
        assertThat(q.get(0).path("sessionId").asText()).isEqualTo("sess-A");
        assertThat(q.get(0).path("agentName").asText()).isEqualTo("agent#1");
        assertThat(q.get(0).path("signalReasons").isArray()).isTrue();
        assertThat(q.get(0).path("signalReasons").get(0).asText()).isEqualTo("tool_failure");
    }

    @Test
    @DisplayName("execute threads window_hours through to the service")
    void execute_respectsWindowHoursParameter() {
        when(signalService.detectAndPersist(Duration.ofHours(6), (Long) null)).thenReturn(0);
        when(signalService.findSessionsNeedingLlmAnnotation(anyInt(), org.mockito.ArgumentMatchers.<Long>any())).thenReturn(List.of());

        DetectSignalAnnotationsTool tool = new DetectSignalAnnotationsTool(signalService, objectMapper);
        SkillResult result = tool.execute(Map.of("window_hours", 6), new SkillContext(null, null, 0L));

        assertThat(result.isSuccess()).isTrue();
        ArgumentCaptor<Duration> cap = ArgumentCaptor.forClass(Duration.class);
        verify(signalService).detectAndPersist(cap.capture(), org.mockito.ArgumentMatchers.isNull());
        assertThat(cap.getValue()).isEqualTo(Duration.ofHours(6));
    }

    @Test
    @DisplayName("execute clamps out-of-range window_hours into [1, 168]")
    void execute_clampsWindowHoursIntoRange() {
        when(signalService.detectAndPersist(Duration.ofHours(168), (Long) null)).thenReturn(0);
        when(signalService.findSessionsNeedingLlmAnnotation(anyInt(), org.mockito.ArgumentMatchers.<Long>any())).thenReturn(List.of());

        DetectSignalAnnotationsTool tool = new DetectSignalAnnotationsTool(signalService, objectMapper);

        // High end: 9999h → clamped to 168h (the defensive 7d ceiling).
        SkillResult hi = tool.execute(Map.of("window_hours", 9999), new SkillContext(null, null, 0L));
        assertThat(hi.isSuccess()).isTrue();
        verify(signalService).detectAndPersist(Duration.ofHours(168), (Long) null);

        // Low end: 0 → clamped to 1h (min).
        when(signalService.detectAndPersist(Duration.ofHours(1), (Long) null)).thenReturn(0);
        SkillResult lo = tool.execute(Map.of("window_hours", 0), new SkillContext(null, null, 0L));
        assertThat(lo.isSuccess()).isTrue();
        verify(signalService).detectAndPersist(Duration.ofHours(1), (Long) null);
    }

    // ─── FLYWHEEL-PER-AGENT-RUN-NOW (2026-05-21): scope-filter input ──────

    @Test
    @DisplayName("execute with agent_id=42 → service called with agentIdFilter=42L and JSON payload echoes back")
    void execute_positiveAgentIdFilter_threadedToService() throws Exception {
        when(signalService.detectAndPersist(Duration.ofHours(1), 42L)).thenReturn(3);
        when(signalService.findSessionsNeedingLlmAnnotation(anyInt(), org.mockito.ArgumentMatchers.<Long>any())).thenReturn(List.of());

        DetectSignalAnnotationsTool tool = new DetectSignalAnnotationsTool(signalService, objectMapper);
        SkillResult result = tool.execute(Map.of("agent_id", 42), new SkillContext(null, null, 0L));

        assertThat(result.isSuccess()).isTrue();
        verify(signalService).detectAndPersist(Duration.ofHours(1), 42L);
        JsonNode root = objectMapper.readTree(result.getOutput());
        assertThat(root.path("agent_id").asLong()).isEqualTo(42L);
    }

    @Test
    @DisplayName("execute with agent_id<=0 / non-numeric → drops filter (collapses to null, cron behavior)")
    void execute_invalidAgentId_collapsesToNullFilter() {
        when(signalService.detectAndPersist(any(Duration.class), org.mockito.ArgumentMatchers.<Long>any()))
                .thenReturn(0);
        when(signalService.findSessionsNeedingLlmAnnotation(anyInt(), org.mockito.ArgumentMatchers.<Long>any())).thenReturn(List.of());

        DetectSignalAnnotationsTool tool = new DetectSignalAnnotationsTool(signalService, objectMapper);

        tool.execute(Map.of("agent_id", 0), new SkillContext(null, null, 0L));
        tool.execute(Map.of("agent_id", -7), new SkillContext(null, null, 0L));
        tool.execute(Map.of("agent_id", "not-an-id"), new SkillContext(null, null, 0L));

        ArgumentCaptor<Long> agentIdCap = ArgumentCaptor.forClass(Long.class);
        verify(signalService, org.mockito.Mockito.times(3))
                .detectAndPersist(any(Duration.class), agentIdCap.capture());
        // 0 / negative / unparseable → all null, defensive contract.
        assertThat(agentIdCap.getAllValues()).containsOnlyNulls();
    }
}
