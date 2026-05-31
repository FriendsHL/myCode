package com.skillforge.server.evolve;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.skillforge.server.bootstrap.SystemAgentNames;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.flywheel.run.FlywheelRunEntity;
import com.skillforge.server.flywheel.run.FlywheelRunService;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.service.ChatService;
import com.skillforge.server.service.SessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AUTOEVOLVE-AGENT-FLYWHEEL Module C (FR-C0) — {@link EvolveController} REST
 * shape + the kickoff sequence: POST creates a FlywheelRun(loop_kind=evolve),
 * spawns the evolve-orchestrator session, and kicks chatAsync with the
 * targetAgentId + evolveRunId scope keywords.
 *
 * <p>Security guards:
 * <ul>
 *   <li>HIGH-1: 409 when an active evolve run already exists for the agent.</li>
 *   <li>Returns 202 ACCEPTED (async, mirrors FlywheelController.runLoop).</li>
 * </ul>
 *
 * <p>MockMvc standaloneSetup mirrors {@code FlywheelControllerTest}.
 */
@EnableWebMvc
@DisplayName("EvolveController")
class EvolveControllerTest {

    private AgentRepository agentRepository;
    private FlywheelRunService flywheelRunService;
    private SessionService sessionService;
    private ChatService chatService;
    private EvolveController controller;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        agentRepository = mock(AgentRepository.class);
        flywheelRunService = mock(FlywheelRunService.class);
        sessionService = mock(SessionService.class);
        chatService = mock(ChatService.class);

        ObjectMapper objectMapper = new ObjectMapper()
                .findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        controller = new EvolveController(agentRepository, flywheelRunService,
                sessionService, chatService);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    @DisplayName("POST /agents/{id}/run → 202 ACCEPTED, creates evolve run + session + kicks chatAsync")
    void run_happyPath_creates202AndKicks() throws Exception {
        AgentEntity target = agentEntity(7L, "my-agent");
        AgentEntity orchestrator = agentEntity(200L, SystemAgentNames.EVOLVE_ORCHESTRATOR);
        when(agentRepository.findById(7L)).thenReturn(Optional.of(target));
        when(agentRepository.findFirstByName(SystemAgentNames.EVOLVE_ORCHESTRATOR))
                .thenReturn(Optional.of(orchestrator));
        // HIGH-1: no active run → allow through
        when(flywheelRunService.hasActiveEvolveRun(7L)).thenReturn(false);

        FlywheelRunEntity run = new FlywheelRunEntity();
        run.setId("evolve-run-1");
        run.setLoopKind(FlywheelRunEntity.LOOP_KIND_EVOLVE);
        when(flywheelRunService.startRun(
                eq(FlywheelRunEntity.LOOP_KIND_EVOLVE),
                eq(FlywheelRunEntity.TRIGGER_SOURCE_API),
                any(), eq(7L), anyInt()))
                .thenReturn(run);
        when(sessionService.createSession(eq(0L), eq(200L)))
                .thenReturn(sessionEntity("sess-orch"));

        mvc.perform(post("/api/evolve/agents/7/run"))
                .andExpect(status().isAccepted())   // 202, not 200
                .andExpect(jsonPath("$.evolveRunId").value("evolve-run-1"))
                .andExpect(jsonPath("$.sessionId").value("sess-orch"))
                .andExpect(jsonPath("$.agentId").value(7))
                .andExpect(jsonPath("$.agentName").value("my-agent"))
                .andExpect(jsonPath("$.maxIter").value(10))
                .andExpect(jsonPath("$.status").value("running"));

        // Run transitioned pending→running with the orchestrator session attached.
        verify(flywheelRunService).attachGeneratorSession("evolve-run-1", "sess-orch");

        // chatAsync fires on the orchestrator session with the scope keywords.
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(chatService).chatAsync(eq("sess-orch"), prompt.capture(), eq(0L));
        assertThat(prompt.getValue())
                .contains("targetAgentId=7")
                .contains("evolveRunId=evolve-run-1");
    }

    @Test
    @DisplayName("POST /agents/{id}/run?maxIter=99 → clamped to 50, 202 ACCEPTED")
    void run_maxIterClamped_returns202() throws Exception {
        AgentEntity target = agentEntity(7L, "my-agent");
        AgentEntity orchestrator = agentEntity(200L, SystemAgentNames.EVOLVE_ORCHESTRATOR);
        when(agentRepository.findById(7L)).thenReturn(Optional.of(target));
        when(agentRepository.findFirstByName(SystemAgentNames.EVOLVE_ORCHESTRATOR))
                .thenReturn(Optional.of(orchestrator));
        when(flywheelRunService.hasActiveEvolveRun(7L)).thenReturn(false);
        FlywheelRunEntity run = new FlywheelRunEntity();
        run.setId("evolve-run-2");
        when(flywheelRunService.startRun(any(), any(), any(), anyLong(), anyInt()))
                .thenReturn(run);
        when(sessionService.createSession(eq(0L), eq(200L)))
                .thenReturn(sessionEntity("sess-orch"));

        mvc.perform(post("/api/evolve/agents/7/run").param("maxIter", "99"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.maxIter").value(50));
    }

    @Test
    @DisplayName("HIGH-1: POST /agents/{id}/run → 409 when agent already has active evolve run")
    void run_activeRunExists_returns409() throws Exception {
        AgentEntity target = agentEntity(7L, "my-agent");
        AgentEntity orchestrator = agentEntity(200L, SystemAgentNames.EVOLVE_ORCHESTRATOR);
        when(agentRepository.findById(7L)).thenReturn(Optional.of(target));
        when(agentRepository.findFirstByName(SystemAgentNames.EVOLVE_ORCHESTRATOR))
                .thenReturn(Optional.of(orchestrator));
        // HIGH-1: active run already in flight → must reject
        when(flywheelRunService.hasActiveEvolveRun(7L)).thenReturn(true);

        mvc.perform(post("/api/evolve/agents/7/run"))
                .andExpect(status().isConflict());

        // No run / session created, no chatAsync fired.
        verify(flywheelRunService, never()).startRun(any(), any(), any(), anyLong(), anyInt());
        verify(sessionService, never()).createSession(anyLong(), anyLong());
        verify(chatService, never()).chatAsync(anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("POST /agents/{id}/run → 404 when target agent missing")
    void run_unknownAgent_returns404() throws Exception {
        when(agentRepository.findById(999L)).thenReturn(Optional.empty());

        mvc.perform(post("/api/evolve/agents/999/run"))
                .andExpect(status().isNotFound());

        verify(flywheelRunService, never()).startRun(any(), any(), any(), anyLong(), anyInt());
        verify(sessionService, never()).createSession(anyLong(), anyLong());
        verify(chatService, never()).chatAsync(anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("POST /agents/{id}/run → 503 when evolve-orchestrator agent not seeded")
    void run_orchestratorMissing_returns503() throws Exception {
        AgentEntity target = agentEntity(7L, "my-agent");
        when(agentRepository.findById(7L)).thenReturn(Optional.of(target));
        when(agentRepository.findFirstByName(SystemAgentNames.EVOLVE_ORCHESTRATOR))
                .thenReturn(Optional.empty());

        mvc.perform(post("/api/evolve/agents/7/run"))
                .andExpect(status().isServiceUnavailable());

        // Must fail-fast BEFORE the in-flight guard check / run / session creation.
        verify(flywheelRunService, never()).hasActiveEvolveRun(anyLong());
        verify(flywheelRunService, never()).startRun(any(), any(), any(), anyLong(), anyInt());
        verify(sessionService, never()).createSession(anyLong(), anyLong());
        verify(chatService, never()).chatAsync(anyString(), anyString(), anyLong());
    }

    // ─── helpers ──────────────────────────────────────────────────────────

    private static AgentEntity agentEntity(Long id, String name) {
        AgentEntity a = new AgentEntity();
        a.setId(id);
        a.setName(name);
        a.setAgentType("system");
        return a;
    }

    private static SessionEntity sessionEntity(String id) {
        SessionEntity s = new SessionEntity();
        s.setId(id);
        s.setUserId(0L);
        s.setAgentId(0L);
        return s;
    }
}
