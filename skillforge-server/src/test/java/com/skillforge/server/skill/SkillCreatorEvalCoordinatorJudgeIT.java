package com.skillforge.server.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.skillforge.server.entity.EvalScenarioEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.entity.SkillDraftEntity;
import com.skillforge.server.eval.EvalJudgeMultiTurnOutput;
import com.skillforge.server.eval.EvalJudgeTool;
import com.skillforge.server.eval.MultiTurnTranscript;
import com.skillforge.server.eval.ScenarioRunResult;
import com.skillforge.server.eval.scenario.EvalScenario;
import com.skillforge.server.improve.EphemeralScenarioCleanupService;
import com.skillforge.server.repository.EvalScenarioDraftRepository;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.repository.SkillDraftRepository;
import com.skillforge.server.service.event.SessionLoopFinishedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SKILL-CREATOR-PHASE-1.6 Phase 1.1 (2026-05-19) — judge integration tests.
 *
 * <p>Inverted from the Phase 1.0 red-test anchor (which asserted
 * {@code never()}): now the coordinator's aggregate path MUST call
 * {@link EvalJudgeTool#judgeMultiTurnConversation} once per child session.
 * The judge return value drives the per-side
 * {@link EvaluationResult.SkillMetrics} via 0..100 → 0..1 normalize (per
 * Phase 1.0 verify (a) — judge returns scores in 0..100 range).
 *
 * <p>Test surface focuses on:
 * <ol>
 *   <li><b>Judge invocation count</b>: 2 child sessions per scenario × 1
 *       scenario = 2 judge calls.</li>
 *   <li><b>Score normalization</b>: stub judge returns
 *       {@code compositeScore=85.0} for with_skill side / {@code 35.0} for
 *       without_skill side; aggregated SkillMetrics should be 0.85 / 0.35.</li>
 *   <li><b>passRate threshold</b>: with_skill=85 ≥ 70 → pass; without_skill=35
 *       &lt; 70 → fail. passRate delta = 1.0 - 0.0 = +1.0 ≥ 5pp threshold →
 *       status='evaluated_passed'.</li>
 *   <li><b>Transcript + ScenarioRunResult adapter</b>: verify the call goes
 *       through with a non-null transcript + non-null scenarioRunResult so
 *       the EvalJudgeTool contract is honored.</li>
 * </ol>
 */
@DisplayName("SkillCreatorEvalCoordinator — Phase 1.1 real-judge integration")
class SkillCreatorEvalCoordinatorJudgeIT {

    private SessionRepository sessionRepository;
    private SkillDraftRepository draftRepository;
    private EphemeralScenarioCleanupService cleanupService;
    private ObjectMapper objectMapper;
    private EvalJudgeTool evalJudgeTool;
    private EvalScenarioDraftRepository scenarioRepository;
    private MultiTurnTranscriptBuilder transcriptBuilder;

    private SkillCreatorEvalCoordinator coordinator;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(SessionRepository.class);
        draftRepository = mock(SkillDraftRepository.class);
        cleanupService = mock(EphemeralScenarioCleanupService.class);
        evalJudgeTool = mock(EvalJudgeTool.class);
        scenarioRepository = mock(EvalScenarioDraftRepository.class);
        transcriptBuilder = mock(MultiTurnTranscriptBuilder.class);
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        // Phase 1.6 test ctor — wires the judge surface so aggregate() goes via
        // computeMetricsViaJudge() instead of the proxy fallback path.
        coordinator = new SkillCreatorEvalCoordinator(
                sessionRepository, draftRepository, cleanupService, objectMapper,
                evalJudgeTool, scenarioRepository, transcriptBuilder);

        // Default transcript stub — non-empty so judge sees something
        // (judge contract degrades to 0 on "(no transcript)" string).
        MultiTurnTranscript canned = new MultiTurnTranscript();
        canned.add("user", "How do I parse a CSV?");
        canned.add("assistant", "Read the file then split lines by comma.");
        when(transcriptBuilder.fromSession(any())).thenReturn(canned);
    }

    @Test
    @DisplayName("aggregate calls EvalJudgeTool once per child session and normalizes scores 0..100 → 0..1")
    void aggregate_callsRealJudge_normalizesScores() {
        // Arrange: 1 scenario × 2 baselines = 2 child sessions, 1 expected scenario.
        String draftId = "draft-j1";
        String scenarioId = "sc-j1";
        String parentId = "parent-j1";

        SessionEntity withSess = newSession("sess-with", evalCtx(draftId, scenarioId, "with_skill"));
        withSess.setRuntimeStatus("completed");
        withSess.setParentSessionId(parentId);
        withSess.setTotalInputTokens(1200);
        withSess.setTotalOutputTokens(800);

        SessionEntity withoutSess = newSession("sess-without",
                evalCtx(draftId, scenarioId, "without_skill"));
        withoutSess.setRuntimeStatus("completed");
        withoutSess.setParentSessionId(parentId);
        withoutSess.setTotalInputTokens(900);
        withoutSess.setTotalOutputTokens(450);

        when(sessionRepository.findById("sess-with")).thenReturn(Optional.of(withSess));
        when(sessionRepository.findByParentSessionId(parentId))
                .thenReturn(Arrays.asList(withSess, withoutSess));

        SkillDraftEntity draft = newDraft(draftId);
        draft.setEvaluationResultJson(pendingStub(2, List.of(scenarioId), parentId));
        when(draftRepository.findById(draftId)).thenReturn(Optional.of(draft));

        EvalScenarioEntity scenarioEntity = new EvalScenarioEntity();
        scenarioEntity.setId(scenarioId);
        scenarioEntity.setName("csv-parsing");
        scenarioEntity.setTask("Parse this CSV file: foo,bar\\n1,2\\n");
        scenarioEntity.setOracleExpected("Returns header + rows");
        scenarioEntity.setOracleType("llm_judge");
        when(scenarioRepository.findById(scenarioId)).thenReturn(Optional.of(scenarioEntity));

        // Stub judge: with_skill scores well (composite=85, overall=90),
        // without_skill scores poorly (composite=35, overall=40). Judge returns
        // scores in 0..100; coordinator normalizes by /100.
        when(evalJudgeTool.judgeMultiTurnConversation(any(EvalScenario.class),
                any(ScenarioRunResult.class), any(MultiTurnTranscript.class)))
                .thenAnswer(invocation -> {
                    ScenarioRunResult srr = invocation.getArgument(1);
                    EvalJudgeMultiTurnOutput out = new EvalJudgeMultiTurnOutput();
                    // Cheap discriminator: pass on with_skill (sessionId "sess-with"),
                    // fail on without_skill (sessionId "sess-without"). Real judge
                    // would use the transcript content; we use sessionId because
                    // the test transcript is the same canned blob.
                    if ("sess-with".equals(srr.getSessionId())) {
                        out.setCompositeScore(85.0);
                        out.setOverallScore(90.0);
                        out.setPass(true);
                    } else {
                        out.setCompositeScore(35.0);
                        out.setOverallScore(40.0);
                        out.setPass(false);
                    }
                    return out;
                });

        // Act: trigger aggregation by firing one of the sibling sessions' finish events.
        coordinator.onSessionLoopFinished(
                new SessionLoopFinishedEvent("sess-with", "done", "completed", 7L));

        // Assert: judge called twice (once per child session).
        verify(evalJudgeTool, times(2))
                .judgeMultiTurnConversation(any(EvalScenario.class),
                        any(ScenarioRunResult.class), any(MultiTurnTranscript.class));

        // Transcript builder called for both sessions.
        verify(transcriptBuilder, atLeastOnce()).fromSession("sess-with");
        verify(transcriptBuilder, atLeastOnce()).fromSession("sess-without");

        // Status flipped to passed (passRate delta = 1.0 - 0.0 = +1.0 ≥ 5pp).
        verify(draftRepository).save(any(SkillDraftEntity.class));
        verify(cleanupService).cleanupEphemerals(any());
        assertThat(draft.getStatus()).isEqualTo(SkillCreatorService.STATUS_EVALUATED_PASSED);

        // 5 SkillMetrics fields populated from judge output:
        //   withSkill.compositeScore = 85/100 = 0.85
        //   withSkill.overallScore   = 90/100 = 0.90
        //   withSkill.passRate       = 1.0 (composite 0.85 ≥ 0.7 threshold)
        //   withoutSkill.compositeScore = 35/100 = 0.35
        //   withoutSkill.overallScore   = 40/100 = 0.40
        //   withoutSkill.passRate       = 0.0 (composite 0.35 < 0.7)
        String json = draft.getEvaluationResultJson();
        assertThat(json)
                .as("evaluation_result_json contains normalized judge scores")
                .doesNotContain("_pending")
                .contains("\"compositeScore\":0.85")    // with_skill
                .contains("\"overallScore\":0.9")        // with_skill (Jackson trim trailing zero)
                .contains("\"compositeScore\":0.35")    // without_skill
                .contains("\"overallScore\":0.4")        // without_skill (Jackson trim trailing zero)
                .contains("LLM judge");                   // summary path label
    }

    @Test
    @DisplayName("aggregate degrades gracefully when scenario lookup fails (contributes 0 to mean)")
    void aggregate_missingScenario_contributesZero() {
        String draftId = "draft-j2";
        String scenarioId = "sc-missing";
        String parentId = "parent-j2";

        SessionEntity withSess = newSession("sess-w2", evalCtx(draftId, scenarioId, "with_skill"));
        withSess.setRuntimeStatus("completed");
        withSess.setParentSessionId(parentId);
        SessionEntity withoutSess = newSession("sess-wo2", evalCtx(draftId, scenarioId, "without_skill"));
        withoutSess.setRuntimeStatus("completed");
        withoutSess.setParentSessionId(parentId);

        when(sessionRepository.findById("sess-w2")).thenReturn(Optional.of(withSess));
        when(sessionRepository.findByParentSessionId(parentId))
                .thenReturn(Arrays.asList(withSess, withoutSess));

        SkillDraftEntity draft = newDraft(draftId);
        draft.setEvaluationResultJson(pendingStub(2, List.of(scenarioId), parentId));
        when(draftRepository.findById(draftId)).thenReturn(Optional.of(draft));

        // Scenario lookup returns empty → judge MUST NOT be called (no scenario to judge against).
        when(scenarioRepository.findById(scenarioId)).thenReturn(Optional.empty());

        coordinator.onSessionLoopFinished(
                new SessionLoopFinishedEvent("sess-w2", "done", "completed", 7L));

        verify(evalJudgeTool, times(0))
                .judgeMultiTurnConversation(any(), any(), any());
        verify(draftRepository).save(any(SkillDraftEntity.class));
        // Both sides contributed 0 → delta = 0 → reject
        assertThat(draft.getStatus()).isEqualTo(SkillCreatorService.STATUS_REJECTED);
    }

    // -------------------------- helpers (mirror SkillCreatorEvalCoordinatorTest) --------------------------

    private SessionEntity newSession(String id, String evalContextJson) {
        SessionEntity s = new SessionEntity();
        s.setId(id);
        s.setUserId(7L);
        s.setAgentId(100L);
        s.setMessageCount(0);
        s.setEvalContextJson(evalContextJson);
        s.setRuntimeStatus("idle");
        s.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1));
        s.setCompletedAt(Instant.now());
        return s;
    }

    private SkillDraftEntity newDraft(String id) {
        SkillDraftEntity d = new SkillDraftEntity();
        d.setId(id);
        d.setName("test-skill");
        d.setOwnerId(1L);
        d.setStatus(SkillCreatorService.STATUS_EVALUATING);
        return d;
    }

    private String evalCtx(String draftId, String scenarioId, String baselineLabel) {
        return String.format("{\"draftId\":\"%s\",\"scenarioId\":\"%s\",\"baselineLabel\":\"%s\"}",
                draftId, scenarioId, baselineLabel);
    }

    private String pendingStub(int expectedCount, List<String> scenarioIds, String parentSessionId) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"_pending\":true,");
        sb.append("\"expectedCount\":").append(expectedCount).append(",");
        sb.append("\"parentSessionId\":\"").append(parentSessionId).append("\",");
        sb.append("\"scenarioIds\":[");
        for (int i = 0; i < scenarioIds.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(scenarioIds.get(i)).append("\"");
        }
        sb.append("]}");
        return sb.toString();
    }
}
