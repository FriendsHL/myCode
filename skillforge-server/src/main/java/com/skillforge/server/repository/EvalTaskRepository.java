package com.skillforge.server.repository;

import com.skillforge.server.entity.EvalTaskEntity;
import com.skillforge.server.entity.EvalTaskItemEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * EVAL-V2 M3a (b2): renamed from {@code EvalRunRepository}; backs
 * {@link EvalTaskEntity} on table {@code t_eval_task} (V52).
 *
 * <p>Method names are preserved verbatim from the legacy
 * {@code EvalRunRepository} so existing callers
 * ({@code PromptImproverService} / {@code SkillAbEvalService} / {@code AbEvalPipeline} /
 * {@code EvalController} / {@code EvalOrchestrator}) only need an import-path
 * + entity-type swap, not a query-shape change.
 */
@Repository
public interface EvalTaskRepository extends JpaRepository<EvalTaskEntity, String> {

    List<EvalTaskEntity> findByAgentDefinitionIdOrderByStartedAtDesc(String agentDefinitionId);

    /**
     * Paged variant used by {@code EvalController#getScenarioRecentRuns} to bound
     * the {@code scenario_results_json} blobs we hydrate per request. Spring Data
     * derives the pagination/sort from the {@link Pageable}.
     */
    List<EvalTaskEntity> findByAgentDefinitionIdOrderByStartedAtDesc(String agentDefinitionId,
                                                                    Pageable pageable);

    Optional<EvalTaskEntity> findTopByAgentDefinitionIdAndStatusOrderByStartedAtDesc(
            String agentDefinitionId, String status);

    /** Rate limit check: only count active/successful tasks, not FAILED ghost rows. */
    List<EvalTaskEntity> findByAgentDefinitionIdAndStatusInAndStartedAtAfter(
            String agentDefinitionId, List<String> statuses, Instant since);

    /** EVAL-V2 M3a (b2): Tasks tab list filter. */
    List<EvalTaskEntity> findByAgentDefinitionIdAndStatusOrderByStartedAtDesc(
            String agentDefinitionId, String status);

    /**
     * SKILL-EVOLVE-LOOP Phase 4 (INV-5 + INV-12): recent failed eval items
     * attributable to a skill, used by
     * {@link com.skillforge.server.improve.SkillEvolutionService} to inject
     * concrete failure cases into the LLM improvement prompt.
     *
     * <p>{@code t_eval_task_item} has no skill_id column — failures are
     * attributed to a skill by joining through the agent that ran the task
     * (every agent stores its skill bindings in {@code t_agent.skill_ids} as
     * a JSON array of skill names). The {@code LIKE '%"<name>"%'} pattern
     * (with surrounding quotes) avoids substring collisions: searching for
     * {@code "Foo"} won't accidentally match an agent bound to {@code "FooBar"}.
     *
     * <p>r1 W3 fix — skill names are user input and may legitimately contain
     * SQL LIKE wildcards ({@code %} / {@code _}) or escape characters
     * ({@code \}). Without escaping, a skill named {@code "Foo_Bar"} would
     * spuriously match an agent bound to {@code "FooXBar"} (because {@code _}
     * is "match any single char"). This default method escapes those
     * characters and the underlying {@code @Query} appends an
     * {@code ESCAPE '\'} clause so the bound parameter is treated as a
     * literal pattern. Callers pass the raw skill name — escaping is a
     * repository-internal contract.
     *
     * <p>{@code agent_definition_id} is the stringified {@code t_agent.id};
     * cast to varchar on the agent side keeps the comparison portable.
     *
     * <p>{@code threshold} defaults to 60.0 (matches the self-improve trigger
     * threshold). Caller passes {@code limit} = 5 (INV-12) to cap prompt size.
     */
    default List<EvalTaskItemEntity> findRecentFailuresForSkill(
            String skillName, double threshold, int limit) {
        if (skillName == null || skillName.isEmpty()) {
            return List.of();
        }
        // Order matters: escape backslash FIRST so we don't double-escape the
        // backslashes we introduce for % and _.
        String escaped = skillName
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return findRecentFailuresForSkillEscaped(escaped, threshold, limit);
    }

    /**
     * Internal: native query backing
     * {@link #findRecentFailuresForSkill(String, double, int)}. Expects the
     * caller to have escaped any LIKE metacharacters in {@code escapedSkillName}
     * already; the {@code ESCAPE '\'} clause makes {@code \%} / {@code \_} /
     * {@code \\} match literally.
     */
    @Query(value = """
            SELECT eti.* FROM t_eval_task_item eti
            JOIN t_eval_task et ON eti.task_id = et.id
            JOIN t_agent a ON et.agent_definition_id = CAST(a.id AS varchar)
            WHERE a.skill_ids LIKE CONCAT('%"', :escapedSkillName, '"%') ESCAPE '\\'
              AND eti.composite_score IS NOT NULL
              AND eti.composite_score < :threshold
            ORDER BY eti.created_at DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<EvalTaskItemEntity> findRecentFailuresForSkillEscaped(
            @Param("escapedSkillName") String escapedSkillName,
            @Param("threshold") double threshold,
            @Param("limit") int limit);
}
