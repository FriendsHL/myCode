package com.skillforge.server.improve;

import com.skillforge.core.llm.LlmProviderFactory;
import com.skillforge.server.config.LlmProperties;
import com.skillforge.server.entity.EvalTaskItemEntity;
import com.skillforge.server.entity.SkillEntity;
import com.skillforge.server.repository.EvalTaskRepository;
import com.skillforge.server.repository.SkillEvolutionRunRepository;
import com.skillforge.server.repository.SkillRepository;
import com.skillforge.server.service.SkillService;
import com.skillforge.server.skill.SkillStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Phase 4 (INV-5/6/12): unit-tests {@link SkillEvolutionService#buildFailureSection}
 * directly — the prompt assembly downstream is a deterministic format string,
 * so testing this builder + the (separate) repository IT for the SQL covers
 * the failures-injection contract without needing an end-to-end async pipeline.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SkillEvolutionService — failures injection")
class SkillEvolutionServiceFailuresInjectionTest {

    @Mock private SkillRepository skillRepository;
    @Mock private SkillEvolutionRunRepository evolutionRunRepository;
    @Mock private SkillAbEvalService skillAbEvalService;
    @Mock private SkillService skillService;
    @Mock private ExecutorService evolutionExecutor;
    @Mock private SkillStorageService skillStorageService;
    @Mock private EvalTaskRepository evalTaskRepository;

    private SkillEvolutionService service;

    @BeforeEach
    void setUp() {
        LlmProperties props = new LlmProperties();
        props.setDefaultProvider("test");
        service = new SkillEvolutionService(
                skillRepository, evolutionRunRepository, skillAbEvalService, skillService,
                new LlmProviderFactory(), props, evolutionExecutor, skillStorageService,
                evalTaskRepository);
    }

    private SkillEntity skill(String name) {
        SkillEntity s = new SkillEntity();
        s.setId(11L);
        s.setName(name);
        return s;
    }

    private EvalTaskItemEntity item(String scenarioId, double composite,
                                    String attribution, String agentOutput) {
        EvalTaskItemEntity e = new EvalTaskItemEntity();
        e.setScenarioId(scenarioId);
        e.setCompositeScore(BigDecimal.valueOf(composite));
        e.setAttribution(attribution);
        e.setAgentFinalOutput(agentOutput);
        e.setStatus("FAIL");
        return e;
    }

    @Test
    @DisplayName("INV-12: 5 failures returned → all surface in prompt block, scenarioId/attribution included")
    void buildFailureSection_fiveFailures_allRendered() {
        when(evalTaskRepository.findRecentFailuresForSkill(
                eq("OnboardingHelper"), anyDouble(), anyInt()))
                .thenReturn(List.of(
                        item("scn-1", 35.0, "PROMPT_QUALITY", "missed step"),
                        item("scn-2", 42.0, "SKILL_EXEC_FAILURE", "exec err"),
                        item("scn-3", 18.0, "CONTEXT_OVERFLOW", "ctx blew up"),
                        item("scn-4", 51.0, "PROMPT_QUALITY", "too vague"),
                        item("scn-5", 28.0, "PROMPT_QUALITY", "wrong tool")));

        String section = service.buildFailureSection(skill("OnboardingHelper"));

        assertThat(section)
                .contains("Recent EVAL failures attributed to this skill (5")
                .contains("scn-1").contains("scn-5")
                .contains("PROMPT_QUALITY")
                .contains("SKILL_EXEC_FAILURE")
                .contains("missed step");
    }

    @Test
    @DisplayName("INV-6: empty failures → returns blank section so caller falls back to statistics-only prompt")
    void buildFailureSection_zeroFailures_returnsBlank() {
        when(evalTaskRepository.findRecentFailuresForSkill(
                eq("Lonely"), anyDouble(), anyInt()))
                .thenReturn(List.of());

        String section = service.buildFailureSection(skill("Lonely"));

        assertThat(section).isEmpty();
    }

    @Test
    @DisplayName("INV-2: query throws → log warn + blank section, evolve continues")
    void buildFailureSection_queryThrows_returnsBlank() {
        when(evalTaskRepository.findRecentFailuresForSkill(
                eq("Broken"), anyDouble(), anyInt()))
                .thenThrow(new RuntimeException("DB down"));

        String section = service.buildFailureSection(skill("Broken"));

        assertThat(section).isEmpty();
    }

    @Test
    @DisplayName("Long agent_final_output is truncated to bound prompt size")
    void buildFailureSection_longOutput_truncated() {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 1000; i++) big.append("x");
        when(evalTaskRepository.findRecentFailuresForSkill(
                eq("Big"), anyDouble(), anyInt()))
                .thenReturn(List.of(item("scn-big", 10.0, "PROMPT_QUALITY", big.toString())));

        String section = service.buildFailureSection(skill("Big"));

        // 400 char cap + ellipsis
        assertThat(section).contains("...");
        assertThat(section.length()).isLessThan(800);
    }
}
