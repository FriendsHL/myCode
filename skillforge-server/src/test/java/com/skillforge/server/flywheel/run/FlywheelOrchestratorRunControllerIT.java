package com.skillforge.server.flywheel.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OPT-LOOP-FRAMEWORK Sprint 4 — wiring-level + JSON contract tests for
 * {@link FlywheelOrchestratorRunController}. Mirrors the
 * {@code OptReportControllerTest} pattern: standalone MockMvc + mocked
 * {@link FlywheelRunService} so we don't bootstrap the full Spring context.
 *
 * <p>Cases:
 * <ol>
 *   <li>list happy path — 3 runs → 200 + items.size=3 + total=3 + ORDER BY DESC</li>
 *   <li>list filter loopKind=memory_curation → only matching rows</li>
 *   <li>list filter status=error → only matching rows</li>
 *   <li>list filter agentId=1 → only matching rows</li>
 *   <li>list pagination limit=10&offset=10 → safeLimit=10, safeOffset=10, total=25</li>
 *   <li>detail happy path — run + 3 steps; steps ASC</li>
 *   <li>detail 404 when not found</li>
 *   <li>contract: list envelope JSON has items→total→limit→offset, inner DTO
 *       has runId / generatorSessionId / agentId (no snake_case / no "id")</li>
 *   <li>contract: detail envelope JSON has run→steps</li>
 * </ol>
 */
@EnableWebMvc
@DisplayName("FlywheelOrchestratorRunController")
class FlywheelOrchestratorRunControllerIT {

    private FlywheelRunService flywheelRunService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        flywheelRunService = mock(FlywheelRunService.class);

        ObjectMapper objectMapper = new ObjectMapper()
                .findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        FlywheelOrchestratorRunController controller =
                new FlywheelOrchestratorRunController(flywheelRunService);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────
    // list endpoint
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Case 1: GET / → 200 + items.size=3 + total=3 + DESC order")
    void list_happyPath_returns200() throws Exception {
        FlywheelRunEntity newest = run("uuid-3", 7L, "opt_report", "completed",
                Instant.parse("2026-05-26T00:00:00Z"));
        FlywheelRunEntity mid = run("uuid-2", 8L, "memory_curation", "running",
                Instant.parse("2026-05-22T00:00:00Z"));
        FlywheelRunEntity oldest = run("uuid-1", 9L, "opt_report", "error",
                Instant.parse("2026-05-20T00:00:00Z"));
        Page<FlywheelRunEntity> page = new PageImpl<>(
                List.of(newest, mid, oldest), PageRequest.of(0, 20), 3L);
        when(flywheelRunService.listRuns(isNull(), isNull(), isNull(), eq(20), eq(0)))
                .thenReturn(page);

        mvc.perform(get("/api/flywheel/orchestrator-runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].runId").value("uuid-3"))
                .andExpect(jsonPath("$.items[1].runId").value("uuid-2"))
                .andExpect(jsonPath("$.items[2].runId").value("uuid-1"))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.limit").value(20))
                .andExpect(jsonPath("$.offset").value(0));
    }

    @Test
    @DisplayName("Case 2: GET ?loopKind=memory_curation → service called with loopKind filter")
    void list_filterByLoopKind() throws Exception {
        FlywheelRunEntity mc = run("uuid-mc", 7L, "memory_curation", "completed",
                Instant.parse("2026-05-22T00:00:00Z"));
        when(flywheelRunService.listRuns(eq("memory_curation"), isNull(), isNull(), eq(20), eq(0)))
                .thenReturn(new PageImpl<>(List.of(mc), PageRequest.of(0, 20), 1L));

        mvc.perform(get("/api/flywheel/orchestrator-runs").param("loopKind", "memory_curation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].loopKind").value("memory_curation"))
                .andExpect(jsonPath("$.total").value(1));

        verify(flywheelRunService).listRuns(eq("memory_curation"), isNull(), isNull(), eq(20), eq(0));
    }

    @Test
    @DisplayName("Case 3: GET ?status=error → service called with status filter")
    void list_filterByStatus() throws Exception {
        FlywheelRunEntity err = run("uuid-err", 7L, "opt_report", "error",
                Instant.parse("2026-05-22T00:00:00Z"));
        err.setErrorReason("LLM call failed");
        when(flywheelRunService.listRuns(isNull(), isNull(), eq("error"), eq(20), eq(0)))
                .thenReturn(new PageImpl<>(List.of(err), PageRequest.of(0, 20), 1L));

        mvc.perform(get("/api/flywheel/orchestrator-runs").param("status", "error"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].status").value("error"))
                .andExpect(jsonPath("$.items[0].errorReason").value("LLM call failed"))
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    @DisplayName("Case 4: GET ?agentId=1 → service called with agentId filter")
    void list_filterByAgentId() throws Exception {
        FlywheelRunEntity agent1 = run("uuid-1", 1L, "opt_report", "completed",
                Instant.parse("2026-05-22T00:00:00Z"));
        when(flywheelRunService.listRuns(isNull(), eq(1L), isNull(), eq(20), eq(0)))
                .thenReturn(new PageImpl<>(List.of(agent1), PageRequest.of(0, 20), 1L));

        mvc.perform(get("/api/flywheel/orchestrator-runs").param("agentId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].agentId").value(1))
                .andExpect(jsonPath("$.total").value(1));

        verify(flywheelRunService).listRuns(isNull(), eq(1L), isNull(), eq(20), eq(0));
    }

    @Test
    @DisplayName("Case 5: GET ?limit=10&offset=10 → service called with clamped paging args, envelope echoes them")
    void list_pagination() throws Exception {
        List<FlywheelRunEntity> pageRows = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            pageRows.add(run("uuid-" + i, 7L, "opt_report", "completed",
                    Instant.parse("2026-05-20T00:00:00Z").plusSeconds(i)));
        }
        when(flywheelRunService.listRuns(isNull(), isNull(), isNull(), eq(10), eq(10)))
                .thenReturn(new PageImpl<>(pageRows, PageRequest.of(1, 10), 25L));

        mvc.perform(get("/api/flywheel/orchestrator-runs")
                        .param("limit", "10").param("offset", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(10))
                .andExpect(jsonPath("$.total").value(25))
                .andExpect(jsonPath("$.limit").value(10))
                .andExpect(jsonPath("$.offset").value(10));
    }

    @Test
    @DisplayName("Case 5b: limit and offset clamp to safe range (limit > 100 → 100; offset < 0 → 0)")
    void list_clampsLimitAndOffset() throws Exception {
        when(flywheelRunService.listRuns(any(), any(), any(), eq(100), eq(0)))
                .thenReturn(new PageImpl<>(Collections.emptyList(), PageRequest.of(0, 100), 0L));

        mvc.perform(get("/api/flywheel/orchestrator-runs")
                        .param("limit", "9999").param("offset", "-5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").value(100))
                .andExpect(jsonPath("$.offset").value(0));

        verify(flywheelRunService).listRuns(isNull(), isNull(), isNull(), eq(100), eq(0));
    }

    // ─────────────────────────────────────────────────────────────────────
    // detail endpoint
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Case 6: GET /{id} → 200 + run + steps[3] (ASC)")
    void detail_happyPath_returns200() throws Exception {
        FlywheelRunEntity r = run("run-uuid", 7L, "opt_report", "completed",
                Instant.parse("2026-05-22T00:00:00Z"));
        r.setGeneratorSessionId("session-uuid-1");
        r.setSummaryJson("{\"topIssues\":[]}");
        when(flywheelRunService.findById("run-uuid")).thenReturn(Optional.of(r));

        FlywheelRunStepEntity s1 = step("step-1", "run-uuid", "completed",
                Instant.parse("2026-05-22T00:01:00Z"));
        FlywheelRunStepEntity s2 = step("step-2", "run-uuid", "completed",
                Instant.parse("2026-05-22T00:02:00Z"));
        FlywheelRunStepEntity s3 = step("step-3", "run-uuid", "error",
                Instant.parse("2026-05-22T00:03:00Z"));
        when(flywheelRunService.listStepsByRunId("run-uuid"))
                .thenReturn(List.of(s1, s2, s3));

        mvc.perform(get("/api/flywheel/orchestrator-runs/run-uuid"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.run.runId").value("run-uuid"))
                .andExpect(jsonPath("$.run.generatorSessionId").value("session-uuid-1"))
                .andExpect(jsonPath("$.run.agentId").value(7))
                .andExpect(jsonPath("$.run.loopKind").value("opt_report"))
                .andExpect(jsonPath("$.run.status").value("completed"))
                .andExpect(jsonPath("$.run.summaryJson").value("{\"topIssues\":[]}"))
                .andExpect(jsonPath("$.steps").isArray())
                .andExpect(jsonPath("$.steps.length()").value(3))
                .andExpect(jsonPath("$.steps[0].stepRunId").value("step-1"))
                .andExpect(jsonPath("$.steps[1].stepRunId").value("step-2"))
                .andExpect(jsonPath("$.steps[2].stepRunId").value("step-3"))
                .andExpect(jsonPath("$.steps[2].status").value("error"));
    }

    @Test
    @DisplayName("Case 7: GET /{id} on missing id → 404")
    void detail_missingId_returns404() throws Exception {
        when(flywheelRunService.findById("nope")).thenReturn(Optional.empty());
        mvc.perform(get("/api/flywheel/orchestrator-runs/nope"))
                .andExpect(status().isNotFound());
    }

    // ─────────────────────────────────────────────────────────────────────
    // contract IT — raw JSON shape checks (W1 + W2 + footgun #6b)
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Case 8: list envelope JSON has items→total→limit→offset in that order, inner DTO uses camelCase runId/generatorSessionId/agentId")
    void list_envelopeContract_fieldOrderAndNames() throws Exception {
        FlywheelRunEntity r = run("uuid-x", 1L, "opt_report", "completed",
                Instant.parse("2026-05-22T00:00:00Z"));
        r.setGeneratorSessionId("sess-uuid");
        when(flywheelRunService.listRuns(isNull(), isNull(), isNull(), eq(20), eq(0)))
                .thenReturn(new PageImpl<>(List.of(r), PageRequest.of(0, 20), 1L));

        MvcResult result = mvc.perform(get("/api/flywheel/orchestrator-runs"))
                .andExpect(status().isOk())
                .andReturn();
        String json = result.getResponse().getContentAsString();

        // W2 — LinkedHashMap field order: items → total → limit → offset
        int idxItems = json.indexOf("\"items\":");
        int idxTotal = json.indexOf("\"total\":");
        int idxLimit = json.indexOf("\"limit\":");
        int idxOffset = json.indexOf("\"offset\":");
        assertThat(idxItems).isPositive();
        assertThat(idxTotal).isGreaterThan(idxItems);
        assertThat(idxLimit).isGreaterThan(idxTotal);
        assertThat(idxOffset).isGreaterThan(idxLimit);

        // W1 — generatorSessionId must be in the inner DTO
        assertThat(json).contains("\"generatorSessionId\":\"sess-uuid\"");
        // camelCase inner field names
        assertThat(json).contains("\"runId\":\"uuid-x\"");
        assertThat(json).contains("\"loopKind\":\"opt_report\"");
        assertThat(json).contains("\"triggerSource\":");
        assertThat(json).contains("\"agentId\":1");

        // explicit footgun-#6 guard: no snake_case, no bare "id" key on the
        // wire (entity getId() must surface as "runId", not "id").
        assertThat(json).doesNotContain("\"agent_id\":");
        assertThat(json).doesNotContain("\"loop_kind\":");
        assertThat(json).doesNotContain("\"generator_session_id\":");
        // The outer envelope has no top-level "id"; nested DTO should not
        // leak the entity getId() as a bare "id" field either.
        assertThat(json).doesNotContain("\"id\":\"uuid-x\"");
    }

    @Test
    @DisplayName("Case 9: detail envelope JSON has run→steps in that order, step DTO uses camelCase stepRunId/subAgentSessionId")
    void detail_envelopeContract_fieldOrderAndNames() throws Exception {
        FlywheelRunEntity r = run("run-uuid", 7L, "opt_report", "completed",
                Instant.parse("2026-05-22T00:00:00Z"));
        r.setGeneratorSessionId("sess-uuid");
        when(flywheelRunService.findById("run-uuid")).thenReturn(Optional.of(r));

        FlywheelRunStepEntity s = step("step-uuid", "run-uuid", "completed",
                Instant.parse("2026-05-22T00:01:00Z"));
        s.setSubAgentSessionId("sub-sess-uuid");
        when(flywheelRunService.listStepsByRunId("run-uuid")).thenReturn(List.of(s));

        MvcResult result = mvc.perform(get("/api/flywheel/orchestrator-runs/run-uuid"))
                .andExpect(status().isOk())
                .andReturn();
        String json = result.getResponse().getContentAsString();

        // detail envelope: run → steps
        int idxRun = json.indexOf("\"run\":");
        int idxSteps = json.indexOf("\"steps\":");
        assertThat(idxRun).isPositive();
        assertThat(idxSteps).isGreaterThan(idxRun);

        // step camelCase
        assertThat(json).contains("\"stepRunId\":\"step-uuid\"");
        assertThat(json).contains("\"subAgentSessionId\":\"sub-sess-uuid\"");
        assertThat(json).contains("\"stepKind\":\"subagent_dispatch\"");

        // no snake_case leak on step DTO
        assertThat(json).doesNotContain("\"step_run_id\":");
        assertThat(json).doesNotContain("\"sub_agent_session_id\":");
        assertThat(json).doesNotContain("\"step_kind\":");
        // bare "id" of either entity must not surface
        assertThat(json).doesNotContain("\"id\":\"step-uuid\"");
        assertThat(json).doesNotContain("\"id\":\"run-uuid\"");
    }

    // ─────────────────────────────────────────────────────────────────────
    // helpers
    // ─────────────────────────────────────────────────────────────────────

    private static FlywheelRunEntity run(String id, long agentId, String loopKind,
                                         String status, Instant createdAt) {
        FlywheelRunEntity r = new FlywheelRunEntity();
        r.setId(id);
        r.setAgentId(agentId);
        r.setLoopKind(loopKind);
        r.setTriggerSource("user_manual");
        r.setStatus(status);
        r.setWindowStart(createdAt.minusSeconds(7 * 86400));
        r.setWindowEnd(createdAt);
        r.setInputJson("{}");
        r.setCreatedAt(createdAt);
        r.setUpdatedAt(createdAt);
        return r;
    }

    private static FlywheelRunStepEntity step(String id, String runId, String status,
                                              Instant createdAt) {
        FlywheelRunStepEntity s = new FlywheelRunStepEntity();
        s.setId(id);
        s.setRunId(runId);
        s.setStatus(status);
        s.setStepInputJson("{}");
        s.setStepKind(FlywheelRunStepEntity.STEP_KIND_SUBAGENT_DISPATCH);
        s.setCreatedAt(createdAt);
        s.setUpdatedAt(createdAt);
        return s;
    }

}
