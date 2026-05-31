package com.skillforge.server.tool.evolve;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.server.entity.SkillDraftEntity;
import com.skillforge.server.improve.BehaviorRuleImproverService;
import com.skillforge.server.improve.ImprovementStartResult;
import com.skillforge.server.improve.PromptImproverService;
import com.skillforge.server.improve.SkillDraftService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * AUTOEVOLVE-AGENT-FLYWHEEL Module C (FR-C2) — {@link GenerateCandidateTool}:
 * surface routing to the right improver (prompt / skill / behavior_rule),
 * eventId threading, skill-only patternId/ownerId requirements, and validation.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GenerateCandidateTool")
class GenerateCandidateToolTest {

    @Mock private PromptImproverService promptImproverService;
    @Mock private SkillDraftService skillDraftService;
    @Mock private BehaviorRuleImproverService behaviorRuleImproverService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private GenerateCandidateTool tool;

    @BeforeEach
    void setUp() {
        tool = new GenerateCandidateTool(promptImproverService, skillDraftService,
                behaviorRuleImproverService, objectMapper);
    }

    private SkillResult run(Map<String, Object> input) {
        return tool.execute(input, new SkillContext("/tmp", "sess", 7L));
    }

    @Test
    @DisplayName("prompt surface: routes to startImprovementFromAttribution, returns version id")
    void promptSurface_routesToPromptImprover() {
        when(promptImproverService.startImprovementFromAttribution(eq(55L), eq("42"), eq("the issue"), isNull()))
                .thenReturn(new ImprovementStartResult("42", null, "prompt-v2", "PENDING"));

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("surface", "prompt");
        input.put("issue", "the issue");
        input.put("targetAgentId", "42");
        input.put("eventId", "55");

        SkillResult result = run(input);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("\"candidateId\":\"prompt-v2\"");
        assertThat(result.getOutput()).contains("\"surface\":\"prompt\"");
        verify(promptImproverService).startImprovementFromAttribution(55L, "42", "the issue", null);
        verifyNoInteractions(skillDraftService, behaviorRuleImproverService);
    }

    @Test
    @DisplayName("behavior_rule surface: routes to behavior improver, returns version id")
    void behaviorRuleSurface_routesToBehaviorImprover() {
        when(behaviorRuleImproverService.startImprovementFromAttribution(eq(55L), eq("42"), eq("issue"), isNull()))
                .thenReturn(new ImprovementStartResult("42", null, "brule-v3", "PENDING"));

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("surface", "behavior_rule");
        input.put("issue", "issue");
        input.put("targetAgentId", "42");
        input.put("eventId", "55");

        SkillResult result = run(input);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("\"candidateId\":\"brule-v3\"");
        verify(behaviorRuleImproverService).startImprovementFromAttribution(55L, "42", "issue", null);
        verifyNoInteractions(promptImproverService, skillDraftService);
    }

    @Test
    @DisplayName("skill surface: routes to createDraftFromAttribution with patternId + ownerId")
    void skillSurface_routesToSkillDraftService() {
        SkillDraftEntity draft = new SkillDraftEntity();
        draft.setId("draft-77");
        when(skillDraftService.createDraftFromAttribution(
                eq(55L), eq(9L), eq("issue"), isNull(), isNull(), eq(3L), any()))
                .thenReturn(draft);

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("surface", "skill");
        input.put("issue", "issue");
        input.put("targetAgentId", "42");
        input.put("eventId", "55");
        input.put("patternId", "9");
        input.put("ownerId", "3");

        SkillResult result = run(input);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("\"candidateId\":\"draft-77\"");
        verify(skillDraftService).createDraftFromAttribution(
                eq(55L), eq(9L), eq("issue"), isNull(), isNull(), eq(3L), any());
        verifyNoInteractions(promptImproverService, behaviorRuleImproverService);
    }

    @Test
    @DisplayName("skill surface: missing patternId → validation error, service NOT called")
    void skillSurface_missingPatternId_validationError() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("surface", "skill");
        input.put("issue", "issue");
        input.put("targetAgentId", "42");
        input.put("eventId", "55");
        input.put("ownerId", "3");

        SkillResult result = run(input);

        assertThat(result.getErrorType()).isEqualTo(SkillResult.ErrorType.VALIDATION);
        assertThat(result.getError()).contains("patternId is required");
        verify(skillDraftService, never()).createDraftFromAttribution(
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("missing eventId → validation error")
    void missingEventId_validationError() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("surface", "prompt");
        input.put("issue", "issue");
        input.put("targetAgentId", "42");

        SkillResult result = run(input);

        assertThat(result.getErrorType()).isEqualTo(SkillResult.ErrorType.VALIDATION);
        assertThat(result.getError()).contains("eventId is required");
        verifyNoInteractions(promptImproverService, skillDraftService, behaviorRuleImproverService);
    }

    @Test
    @DisplayName("unknown surface → validation error")
    void unknownSurface_validationError() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("surface", "bogus");
        input.put("issue", "issue");
        input.put("targetAgentId", "42");
        input.put("eventId", "55");

        SkillResult result = run(input);

        assertThat(result.getErrorType()).isEqualTo(SkillResult.ErrorType.VALIDATION);
        assertThat(result.getError()).contains("surface");
        verifyNoInteractions(promptImproverService, skillDraftService, behaviorRuleImproverService);
    }

    @Test
    @DisplayName("non-numeric eventId → validation error")
    void nonNumericEventId_validationError() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("surface", "prompt");
        input.put("issue", "issue");
        input.put("targetAgentId", "42");
        input.put("eventId", "abc");

        SkillResult result = run(input);

        assertThat(result.getErrorType()).isEqualTo(SkillResult.ErrorType.VALIDATION);
    }

    @Test
    @DisplayName("tool metadata: name, not read-only")
    void metadata() {
        assertThat(tool.getName()).isEqualTo("GenerateCandidate");
        assertThat(tool.isReadOnly()).isFalse();
    }
}
