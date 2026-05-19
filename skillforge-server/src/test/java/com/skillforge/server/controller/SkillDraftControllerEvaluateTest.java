package com.skillforge.server.controller;

import com.skillforge.server.entity.EvalScenarioEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.entity.SkillDraftEntity;
import com.skillforge.server.improve.SkillDraftService;
import com.skillforge.server.repository.EvalScenarioDraftRepository;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.repository.SkillDraftRepository;
import com.skillforge.server.skill.SkillCreatorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SKILL-CREATOR-PHASE-1.6 F2 (2026-05-19) — Phase 1.2 IT for the new
 * {@code POST /api/skill-drafts/{id}/evaluate} controller endpoint.
 *
 * <p>Pins:
 * <ol>
 *   <li>Happy path: caller provides {@code targetAgentId} + draft has
 *       {@code sourceSessionId} → controller auto-builds ephemeral scenarios
 *       from the source session, persists them, and fires
 *       {@code dispatchEvaluation}. Returns 202 + run-id list.</li>
 *   <li>Bad request: missing {@code targetAgentId} → 400.</li>
 *   <li>Not found: unknown draft id → 404.</li>
 *   <li>No scenarios: draft has no sourceSessionId AND caller didn't supply
 *       scenarios → 400 with explicit error.</li>
 *   <li>Caller-supplied scenarios: when {@code scenarios[]} present in the
 *       body, the auto-build path is skipped (no
 *       buildEphemeralScenariosFromSessions call); the supplied ids are
 *       passed straight to dispatch.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SkillDraftController.triggerDraftEvaluation — Phase 1.2 F2 endpoint")
class SkillDraftControllerEvaluateTest {

    @Mock private SkillDraftService skillDraftService;
    @Mock private SkillDraftRepository skillDraftRepository;
    @Mock private SkillCreatorService skillCreatorService;
    @Mock private SessionRepository sessionRepository;
    @Mock private EvalScenarioDraftRepository evalScenarioRepository;
    private ExecutorService coordinatorExecutor;

    private SkillDraftController controller;

    @BeforeEach
    void setUp() {
        coordinatorExecutor = Executors.newSingleThreadExecutor();
        controller = new SkillDraftController(
                skillDraftService, skillDraftRepository, coordinatorExecutor,
                skillCreatorService, sessionRepository, evalScenarioRepository);
    }

    @Test
    @DisplayName("happy path: caller-provided scenarios → dispatch fires + 202")
    void evaluate_withCallerProvidedScenarios_dispatchesAndReturns202() {
        SkillDraftEntity draft = newDraft("draft-1");
        when(skillDraftRepository.findById("draft-1")).thenReturn(Optional.of(draft));
        when(skillDraftRepository.save(any(SkillDraftEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(skillCreatorService.dispatchEvaluation(eq(null), eq("draft-1"), anyList()))
                .thenReturn(List.of("run-1", "run-2"));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("targetAgentId", 100);
        body.put("scenarios", List.of("sc-1", "sc-2"));

        ResponseEntity<?> resp = controller.triggerDraftEvaluation("draft-1", body);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        @SuppressWarnings("unchecked")
        Map<String, Object> respBody = (Map<String, Object>) resp.getBody();
        assertThat(respBody).containsEntry("draftId", "draft-1")
                .containsEntry("status", "evaluating")
                .containsEntry("targetAgentId", 100L)
                .containsEntry("runIds", List.of("run-1", "run-2"))
                .containsEntry("scenarioIds", List.of("sc-1", "sc-2"));

        // targetAgentId was stamped into the draft before dispatch.
        assertThat(draft.getTargetAgentId()).isEqualTo(100L);
        verify(skillCreatorService).dispatchEvaluation(null, "draft-1", List.of("sc-1", "sc-2"));
        // Caller-provided scenarios → no auto-build call.
        verify(skillCreatorService, never()).buildEphemeralScenariosFromSessions(anyList(), any());
    }

    @Test
    @DisplayName("happy path: no scenarios provided → controller auto-builds from sourceSessionId")
    void evaluate_noScenariosProvided_autoBuildsFromSource() {
        SkillDraftEntity draft = newDraft("draft-auto");
        draft.setSourceSessionId("sess-source");
        when(skillDraftRepository.findById("draft-auto")).thenReturn(Optional.of(draft));
        when(skillDraftRepository.save(any(SkillDraftEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        SessionEntity source = new SessionEntity();
        source.setId("sess-source");
        when(sessionRepository.findById("sess-source")).thenReturn(Optional.of(source));

        EvalScenarioEntity built = new EvalScenarioEntity();
        built.setId("auto-sc-1");
        built.setTask("Do the thing");
        when(skillCreatorService.buildEphemeralScenariosFromSessions(
                List.of(source), 100L))
                .thenReturn(List.of(built));
        when(skillCreatorService.dispatchEvaluation(eq(null), eq("draft-auto"), anyList()))
                .thenReturn(List.of("run-auto"));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("targetAgentId", 100);
        // no scenarios in body

        ResponseEntity<?> resp = controller.triggerDraftEvaluation("draft-auto", body);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        verify(skillCreatorService).buildEphemeralScenariosFromSessions(List.of(source), 100L);
        verify(evalScenarioRepository).saveAll(List.of(built));
        verify(skillCreatorService).dispatchEvaluation(null, "draft-auto", List.of("auto-sc-1"));
    }

    @Test
    @DisplayName("missing targetAgentId → 400 BAD_REQUEST + dispatch not called")
    void evaluate_missingTargetAgent_returns400() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("scenarios", List.of("sc-1"));

        ResponseEntity<?> resp = controller.triggerDraftEvaluation("draft-x", body);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        @SuppressWarnings("unchecked")
        Map<String, Object> errBody = (Map<String, Object>) resp.getBody();
        assertThat(errBody)
                .containsEntry("error", "BAD_REQUEST");
        verify(skillCreatorService, never()).dispatchEvaluation(any(), anyString(), anyList());
    }

    @Test
    @DisplayName("unknown draft id → 404 NOT_FOUND")
    void evaluate_unknownDraft_returns404() {
        when(skillDraftRepository.findById("missing")).thenReturn(Optional.empty());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("targetAgentId", 100);
        body.put("scenarios", List.of("sc-1"));

        ResponseEntity<?> resp = controller.triggerDraftEvaluation("missing", body);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(skillCreatorService, never()).dispatchEvaluation(any(), anyString(), anyList());
    }

    @Test
    @DisplayName("no scenarios + no sourceSessionId → 400 NO_SCENARIOS")
    void evaluate_noScenariosAndNoSource_returns400() {
        SkillDraftEntity draft = newDraft("draft-empty");
        // no sourceSessionId
        when(skillDraftRepository.findById("draft-empty")).thenReturn(Optional.of(draft));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("targetAgentId", 100);

        ResponseEntity<?> resp = controller.triggerDraftEvaluation("draft-empty", body);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        @SuppressWarnings("unchecked")
        Map<String, Object> errBody = (Map<String, Object>) resp.getBody();
        assertThat(errBody)
                .containsEntry("error", "NO_SCENARIOS");
        verify(skillCreatorService, never()).dispatchEvaluation(any(), anyString(), anyList());
    }

    private SkillDraftEntity newDraft(String id) {
        SkillDraftEntity d = new SkillDraftEntity();
        d.setId(id);
        d.setOwnerId(7L);
        d.setName("test-skill");
        d.setStatus("draft");
        return d;
    }
}
