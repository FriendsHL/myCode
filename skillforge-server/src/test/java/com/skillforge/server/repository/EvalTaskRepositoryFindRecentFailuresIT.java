package com.skillforge.server.repository;

import com.skillforge.server.AbstractPostgresIT;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.EvalTaskEntity;
import com.skillforge.server.entity.EvalTaskItemEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SKILL-EVOLVE-LOOP Phase 4 (INV-5/6/12): integration test for
 * {@link EvalTaskRepository#findRecentFailuresForSkill}. Exercises the native
 * cross-table join (t_eval_task_item ↔ t_eval_task ↔ t_agent) and the
 * JSON-array LIKE pattern that disambiguates skill names.
 */
@DisplayName("EvalTaskRepository.findRecentFailuresForSkill")
class EvalTaskRepositoryFindRecentFailuresIT extends AbstractPostgresIT {

    @Autowired
    private EvalTaskRepository evalTaskRepository;

    @Autowired
    private EvalTaskItemRepository evalTaskItemRepository;

    @Autowired
    private AgentRepository agentRepository;

    @BeforeEach
    void cleanUp() {
        evalTaskItemRepository.deleteAll();
        evalTaskRepository.deleteAll();
        agentRepository.deleteAll();
    }

    private AgentEntity agent(String name, String skillIdsJson) {
        AgentEntity a = new AgentEntity();
        a.setName(name);
        a.setSkillIds(skillIdsJson);
        a.setMcpServerIds("");
        a.setDisabledSystemSkills("[]");
        return agentRepository.save(a);
    }

    private EvalTaskEntity task(AgentEntity agent) {
        EvalTaskEntity t = new EvalTaskEntity();
        t.setId(UUID.randomUUID().toString());
        t.setAgentDefinitionId(String.valueOf(agent.getId()));
        t.setStatus("COMPLETED");
        return evalTaskRepository.save(t);
    }

    private EvalTaskItemEntity item(String taskId, String scenarioId,
                                    BigDecimal compositeScore,
                                    Instant createdAt) {
        EvalTaskItemEntity i = new EvalTaskItemEntity();
        i.setTaskId(taskId);
        i.setScenarioId(scenarioId);
        i.setStatus("FAIL");
        i.setCompositeScore(compositeScore);
        i.setAttribution("PROMPT_QUALITY");
        i.setAgentFinalOutput("output for " + scenarioId);
        i.setCreatedAt(createdAt);
        return evalTaskItemRepository.save(i);
    }

    @Test
    @DisplayName("returns failures for matching skill name only, newest first, capped at limit")
    void findFailures_happyPath() {
        AgentEntity bound = agent("agent-foo", "[\"OnboardingHelper\",\"PostgresQueryWriter\"]");
        AgentEntity unrelated = agent("agent-bar", "[\"DataExtractor\"]");

        EvalTaskEntity t1 = task(bound);
        EvalTaskEntity t2 = task(unrelated);

        Instant now = Instant.now();
        // 6 failures bound (only top 5 should return); 1 above threshold (excluded)
        item(t1.getId(), "scn-old", new BigDecimal("30.00"), now.minus(6, ChronoUnit.HOURS));
        item(t1.getId(), "scn-1", new BigDecimal("35.00"), now.minus(5, ChronoUnit.HOURS));
        item(t1.getId(), "scn-2", new BigDecimal("18.00"), now.minus(4, ChronoUnit.HOURS));
        item(t1.getId(), "scn-3", new BigDecimal("42.00"), now.minus(3, ChronoUnit.HOURS));
        item(t1.getId(), "scn-4", new BigDecimal("50.00"), now.minus(2, ChronoUnit.HOURS));
        item(t1.getId(), "scn-newest", new BigDecimal("28.00"), now.minus(1, ChronoUnit.HOURS));
        item(t1.getId(), "scn-passing", new BigDecimal("82.00"), now); // excluded by threshold
        // unrelated agent's failures must NOT match
        item(t2.getId(), "scn-other-fail", new BigDecimal("10.00"), now);

        List<EvalTaskItemEntity> results = evalTaskRepository
                .findRecentFailuresForSkill("OnboardingHelper", 60.0, 5);

        assertThat(results).hasSize(5);
        // Newest first
        assertThat(results.get(0).getScenarioId()).isEqualTo("scn-newest");
        // Excludes pass items + unrelated agent failures
        assertThat(results).extracting(EvalTaskItemEntity::getScenarioId)
                .doesNotContain("scn-passing", "scn-other-fail");
    }

    @Test
    @DisplayName("INV-6: skill name not bound to any agent returns empty")
    void findFailures_unknownSkill_empty() {
        AgentEntity a = agent("a1", "[\"OnboardingHelper\"]");
        EvalTaskEntity t = task(a);
        item(t.getId(), "scn-1", new BigDecimal("30.00"), Instant.now());

        List<EvalTaskItemEntity> results = evalTaskRepository
                .findRecentFailuresForSkill("NotABoundSkillName", 60.0, 5);

        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("LIKE pattern uses surrounding quotes — substring of a different skill name doesn't match")
    void findFailures_substringDoesNotMatch() {
        // Agent bound to "DataExtractor" — searching for "Data" must not match.
        AgentEntity a = agent("a1", "[\"DataExtractor\"]");
        EvalTaskEntity t = task(a);
        item(t.getId(), "scn-1", new BigDecimal("20.00"), Instant.now());

        List<EvalTaskItemEntity> results = evalTaskRepository
                .findRecentFailuresForSkill("Data", 60.0, 5);

        assertThat(results).isEmpty();
    }

    // ─── r1 W3: LIKE wildcard escaping ───────────────────────────────────

    @Test
    @DisplayName("r1 W3: skill name containing '_' is treated literally — does not match arbitrary single char")
    void findFailures_underscoreNotWildcard() {
        // Agent A bound to "Foo_Bar" (literal underscore).
        // Agent B bound to "FooXBar" — without escape, _ would match X here.
        AgentEntity boundLiteral = agent("agent-A", "[\"Foo_Bar\"]");
        AgentEntity boundOther = agent("agent-B", "[\"FooXBar\"]");

        EvalTaskEntity ta = task(boundLiteral);
        EvalTaskEntity tb = task(boundOther);
        Instant now = Instant.now();
        item(ta.getId(), "scn-A", new BigDecimal("20.00"), now);
        item(tb.getId(), "scn-B", new BigDecimal("20.00"), now);

        // Search for the literal "Foo_Bar" — must hit only agent A's items,
        // NOT agent B's. Pre-fix this returned both because _ was a wildcard.
        List<EvalTaskItemEntity> results = evalTaskRepository
                .findRecentFailuresForSkill("Foo_Bar", 60.0, 5);

        assertThat(results).extracting(EvalTaskItemEntity::getScenarioId)
                .containsExactly("scn-A")
                .doesNotContain("scn-B");
    }

    @Test
    @DisplayName("r1 W3: skill name containing '%' is treated literally — does not match arbitrary substring")
    void findFailures_percentNotWildcard() {
        AgentEntity boundLiteral = agent("agent-A", "[\"Foo%Bar\"]");
        AgentEntity boundOther = agent("agent-B", "[\"FooLongMiddleBar\"]");

        EvalTaskEntity ta = task(boundLiteral);
        EvalTaskEntity tb = task(boundOther);
        Instant now = Instant.now();
        item(ta.getId(), "scn-A", new BigDecimal("20.00"), now);
        item(tb.getId(), "scn-B", new BigDecimal("20.00"), now);

        // Search for literal "Foo%Bar" — % must NOT act as "any chars".
        List<EvalTaskItemEntity> results = evalTaskRepository
                .findRecentFailuresForSkill("Foo%Bar", 60.0, 5);

        assertThat(results).extracting(EvalTaskItemEntity::getScenarioId)
                .containsExactly("scn-A")
                .doesNotContain("scn-B");
    }

    @Test
    @DisplayName("r1 W3: null / empty skill name returns empty without hitting DB")
    void findFailures_nullOrEmpty_returnsEmpty() {
        AgentEntity a = agent("a1", "[\"Foo\"]");
        EvalTaskEntity t = task(a);
        item(t.getId(), "scn-1", new BigDecimal("20.00"), Instant.now());

        assertThat(evalTaskRepository.findRecentFailuresForSkill(null, 60.0, 5)).isEmpty();
        assertThat(evalTaskRepository.findRecentFailuresForSkill("", 60.0, 5)).isEmpty();
    }
}
