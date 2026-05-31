package com.skillforge.server.tool.evolve;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.server.entity.BehaviorRuleVersionEntity;
import com.skillforge.server.entity.PromptAbRunEntity;
import com.skillforge.server.entity.SkillAbRunEntity;
import com.skillforge.server.improve.BehaviorRulePromotionService;
import com.skillforge.server.improve.PromotionResult;
import com.skillforge.server.improve.PromptPromotionService;
import com.skillforge.server.improve.SkillAbEvalService;
import com.skillforge.server.repository.BehaviorRuleVersionRepository;
import com.skillforge.server.repository.PromptAbRunRepository;
import com.skillforge.server.repository.SkillAbRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * AUTOEVOLVE-AGENT-FLYWHEEL Module B — {@link PromoteCandidateTool}: surface
 * routing through the guarded promote services, guard-rejection passthrough, and
 * the SECURITY candidate-ownership / candidate-id mismatch rejections.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PromoteCandidateTool")
class PromoteCandidateToolTest {

    @Mock private PromptPromotionService promptPromotionService;
    @Mock private SkillAbEvalService skillAbEvalService;
    @Mock private BehaviorRulePromotionService behaviorRulePromotionService;
    @Mock private PromptAbRunRepository promptAbRunRepository;
    @Mock private SkillAbRunRepository skillAbRunRepository;
    @Mock private BehaviorRuleVersionRepository behaviorRuleVersionRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private PromoteCandidateTool tool;

    @BeforeEach
    void setUp() {
        tool = new PromoteCandidateTool(promptPromotionService, skillAbEvalService,
                behaviorRulePromotionService, promptAbRunRepository, skillAbRunRepository,
                behaviorRuleVersionRepository, objectMapper);
    }

    private SkillResult run(Map<String, Object> input) {
        return tool.execute(input, new SkillContext("/tmp", "sess", 7L));
    }

    private Map<String, Object> input(String surface, String candidateId,
                                      String targetAgentId, String abRunId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("surface", surface);
        m.put("candidateId", candidateId);
        m.put("targetAgentId", targetAgentId);
        if (abRunId != null) {
            m.put("abRunId", abRunId);
        }
        return m;
    }

    // ───────────────────────────── prompt ─────────────────────────────

    @Test
    @DisplayName("prompt: ownership + candidate match → routes to evaluateAndPromote, returns promoted")
    void prompt_promoted() {
        PromptAbRunEntity ab = new PromptAbRunEntity();
        ab.setAgentId("42");
        ab.setPromptVersionId("cand-v2");
        when(promptAbRunRepository.findById("ab-1")).thenReturn(Optional.of(ab));
        when(promptPromotionService.evaluateAndPromote("ab-1", "42"))
                .thenReturn(PromotionResult.promoted("cand-v2"));

        SkillResult result = run(input("prompt", "cand-v2", "42", "ab-1"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("\"status\":\"promoted\"");
        verify(promptPromotionService).evaluateAndPromote("ab-1", "42");
    }

    @Test
    @DisplayName("prompt: guard rejects (delta below threshold) → status rejected with reason")
    void prompt_guardRejected() {
        PromptAbRunEntity ab = new PromptAbRunEntity();
        ab.setAgentId("42");
        ab.setPromptVersionId("cand-v2");
        when(promptAbRunRepository.findById("ab-1")).thenReturn(Optional.of(ab));
        when(promptPromotionService.evaluateAndPromote("ab-1", "42"))
                .thenReturn(PromotionResult.rejected("Delta 5.0 below threshold 15.0"));

        SkillResult result = run(input("prompt", "cand-v2", "42", "ab-1"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("\"status\":\"rejected\"");
        assertThat(result.getOutput()).contains("below threshold");
    }

    @Test
    @DisplayName("SECURITY prompt: ab_run belongs to another agent → rejected, promote NOT called")
    void prompt_ownershipMismatch_rejected() {
        PromptAbRunEntity ab = new PromptAbRunEntity();
        ab.setAgentId("99");          // different agent!
        ab.setPromptVersionId("cand-v2");
        when(promptAbRunRepository.findById("ab-1")).thenReturn(Optional.of(ab));

        SkillResult result = run(input("prompt", "cand-v2", "42", "ab-1"));

        assertThat(result.getOutput()).contains("\"status\":\"rejected\"");
        assertThat(result.getOutput()).contains("ownership mismatch");
        verify(promptPromotionService, never()).evaluateAndPromote(any(), any());
    }

    @Test
    @DisplayName("SECURITY prompt: candidateId != ab_run's version → rejected, promote NOT called")
    void prompt_candidateMismatch_rejected() {
        PromptAbRunEntity ab = new PromptAbRunEntity();
        ab.setAgentId("42");
        ab.setPromptVersionId("cand-OTHER");   // run promotes a different candidate
        when(promptAbRunRepository.findById("ab-1")).thenReturn(Optional.of(ab));

        SkillResult result = run(input("prompt", "cand-v2", "42", "ab-1"));

        assertThat(result.getOutput()).contains("\"status\":\"rejected\"");
        assertThat(result.getOutput()).contains("candidateId mismatch");
        verify(promptPromotionService, never()).evaluateAndPromote(any(), any());
    }

    @Test
    @DisplayName("prompt: missing abRunId → validation error")
    void prompt_missingAbRunId_validationError() {
        SkillResult result = run(input("prompt", "cand-v2", "42", null));

        assertThat(result.getErrorType()).isEqualTo(SkillResult.ErrorType.VALIDATION);
        assertThat(result.getError()).contains("abRunId is required");
    }

    // ───────────────────────────── skill ─────────────────────────────

    @Test
    @DisplayName("skill: ownership + candidate match → routes to manualPromote, returns promoted")
    void skill_promoted() {
        SkillAbRunEntity ab = new SkillAbRunEntity();
        ab.setAgentId("42");
        ab.setCandidateSkillId(77L);
        when(skillAbRunRepository.findById("sk-1")).thenReturn(Optional.of(ab));

        SkillResult result = run(input("skill", "77", "42", "sk-1"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("\"status\":\"promoted\"");
        verify(skillAbEvalService).manualPromote("sk-1", 7L);
    }

    @Test
    @DisplayName("skill: service throws (not COMPLETED) → rejected")
    void skill_notCompleted_rejected() {
        SkillAbRunEntity ab = new SkillAbRunEntity();
        ab.setAgentId("42");
        ab.setCandidateSkillId(77L);
        when(skillAbRunRepository.findById("sk-1")).thenReturn(Optional.of(ab));
        when(skillAbEvalService.manualPromote("sk-1", 7L))
                .thenThrow(new IllegalStateException("A/B run is not in COMPLETED status"));

        SkillResult result = run(input("skill", "77", "42", "sk-1"));

        assertThat(result.getOutput()).contains("\"status\":\"rejected\"");
        assertThat(result.getOutput()).contains("COMPLETED");
    }

    @Test
    @DisplayName("SECURITY skill: ab_run belongs to another agent → rejected, manualPromote NOT called")
    void skill_ownershipMismatch_rejected() {
        SkillAbRunEntity ab = new SkillAbRunEntity();
        ab.setAgentId("99");
        ab.setCandidateSkillId(77L);
        when(skillAbRunRepository.findById("sk-1")).thenReturn(Optional.of(ab));

        SkillResult result = run(input("skill", "77", "42", "sk-1"));

        assertThat(result.getOutput()).contains("ownership mismatch");
        verify(skillAbEvalService, never()).manualPromote(any(), anyLong());
    }

    @Test
    @DisplayName("SECURITY skill: candidateId != ab_run's candidateSkillId → rejected")
    void skill_candidateMismatch_rejected() {
        SkillAbRunEntity ab = new SkillAbRunEntity();
        ab.setAgentId("42");
        ab.setCandidateSkillId(88L);   // run promotes a different skill
        when(skillAbRunRepository.findById("sk-1")).thenReturn(Optional.of(ab));

        SkillResult result = run(input("skill", "77", "42", "sk-1"));

        assertThat(result.getOutput()).contains("candidateId mismatch");
        verify(skillAbEvalService, never()).manualPromote(any(), anyLong());
    }

    @Test
    @DisplayName("skill: non-numeric candidateId → validation error")
    void skill_nonNumericCandidate_validationError() {
        SkillAbRunEntity ab = new SkillAbRunEntity();
        ab.setAgentId("42");
        ab.setCandidateSkillId(77L);
        when(skillAbRunRepository.findById("sk-1")).thenReturn(Optional.of(ab));

        SkillResult result = run(input("skill", "not-a-number", "42", "sk-1"));

        assertThat(result.getErrorType()).isEqualTo(SkillResult.ErrorType.VALIDATION);
        assertThat(result.getError()).contains("numeric skill id");
    }

    // ───────────────────────────── behavior_rule ─────────────────────────────

    @Test
    @DisplayName("behavior_rule: ownership match → routes to promoteManual, returns promoted")
    void behaviorRule_promoted() {
        BehaviorRuleVersionEntity v = new BehaviorRuleVersionEntity();
        v.setId("ver-7");
        v.setAgentId("42");
        when(behaviorRuleVersionRepository.findById("ver-7")).thenReturn(Optional.of(v));
        when(behaviorRulePromotionService.promoteManual("ver-7", 7L))
                .thenReturn(BehaviorRulePromotionService.PromoteResult.promoted("ver-7"));

        SkillResult result = run(input("behavior_rule", "ver-7", "42", null));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("\"status\":\"promoted\"");
        verify(behaviorRulePromotionService).promoteManual("ver-7", 7L);
    }

    @Test
    @DisplayName("behavior_rule: dual-criteria not satisfied → rejected")
    void behaviorRule_dualCriteriaFails_rejected() {
        BehaviorRuleVersionEntity v = new BehaviorRuleVersionEntity();
        v.setId("ver-7");
        v.setAgentId("42");
        when(behaviorRuleVersionRepository.findById("ver-7")).thenReturn(Optional.of(v));
        when(behaviorRulePromotionService.promoteManual("ver-7", 7L))
                .thenThrow(new IllegalStateException("Dual-criteria not satisfied"));

        SkillResult result = run(input("behavior_rule", "ver-7", "42", null));

        assertThat(result.getOutput()).contains("\"status\":\"rejected\"");
        assertThat(result.getOutput()).contains("Dual-criteria");
    }

    @Test
    @DisplayName("behavior_rule: noop (already active) normalised to promoted")
    void behaviorRule_noop_normalisedToPromoted() {
        BehaviorRuleVersionEntity v = new BehaviorRuleVersionEntity();
        v.setId("ver-7");
        v.setAgentId("42");
        when(behaviorRuleVersionRepository.findById("ver-7")).thenReturn(Optional.of(v));
        when(behaviorRulePromotionService.promoteManual("ver-7", 7L))
                .thenReturn(BehaviorRulePromotionService.PromoteResult.noop("already active"));

        SkillResult result = run(input("behavior_rule", "ver-7", "42", null));

        assertThat(result.getOutput()).contains("\"status\":\"promoted\"");
    }

    @Test
    @DisplayName("SECURITY behavior_rule: version belongs to another agent → rejected, promote NOT called")
    void behaviorRule_ownershipMismatch_rejected() {
        BehaviorRuleVersionEntity v = new BehaviorRuleVersionEntity();
        v.setId("ver-7");
        v.setAgentId("99");           // different agent
        when(behaviorRuleVersionRepository.findById("ver-7")).thenReturn(Optional.of(v));

        SkillResult result = run(input("behavior_rule", "ver-7", "42", null));

        assertThat(result.getOutput()).contains("ownership mismatch");
        verify(behaviorRulePromotionService, never()).promoteManual(any(), anyLong());
    }

    // ───────────────────────────── validation ─────────────────────────────

    @Test
    @DisplayName("unknown surface → validation error, no service called")
    void unknownSurface_validationError() {
        SkillResult result = run(input("bogus", "x", "42", "ab-1"));

        assertThat(result.getErrorType()).isEqualTo(SkillResult.ErrorType.VALIDATION);
        verifyNoInteractions(promptPromotionService, skillAbEvalService, behaviorRulePromotionService);
    }

    @Test
    @DisplayName("tool metadata: name, not read-only")
    void metadata() {
        assertThat(tool.getName()).isEqualTo("PromoteCandidate");
        assertThat(tool.isReadOnly()).isFalse();
    }
}
