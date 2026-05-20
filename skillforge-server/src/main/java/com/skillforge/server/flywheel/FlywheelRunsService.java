package com.skillforge.server.flywheel;

import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.OptimizationEventEntity;
import com.skillforge.server.entity.SessionPatternEntity;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.repository.OptimizationEventRepository;
import com.skillforge.server.repository.SessionPatternRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * FLYWHEEL-PER-RUN — aggregation service for the per-run sidebar on the
 * Flywheel observability panel.
 *
 * <p>Returns recent {@link OptimizationEventEntity} rows (one per attribution
 * run) joined with their pattern signature snippet + agent name. Joins are
 * batched (2 secondary queries regardless of run count) to avoid N+1.
 *
 * <p>Read-only — never mutates the underlying tables. Spec:
 * {@code docs/requirements/active/FLYWHEEL-PER-RUN/index.md §Acceptance}.
 */
@Service
public class FlywheelRunsService {

    private static final Logger log = LoggerFactory.getLogger(FlywheelRunsService.class);

    /** Maximum length of {@code patternSignature} snippet in {@link FlywheelRunDto}. */
    static final int SIGNATURE_SNIPPET_MAX = 80;

    /** Ellipsis appended when truncating the pattern signature. */
    private static final String ELLIPSIS = "…";

    /**
     * Stages considered "happy terminal" — the run completed successfully and
     * needs no further operator attention. Excluded from the sidebar by
     * default ({@code hideTerminal=true}). Failed terminals
     * ({@code proposal_rejected}, {@code candidate_failed}, {@code ab_failed})
     * are intentionally NOT in this set — operators want to see error states.
     */
    static final List<String> TERMINAL_HAPPY_STAGES = List.of(
            OptimizationEventEntity.STAGE_PROMOTED,
            OptimizationEventEntity.STAGE_VERIFIED,
            OptimizationEventEntity.STAGE_ROLLED_BACK);

    private final OptimizationEventRepository optimizationEventRepository;
    private final SessionPatternRepository sessionPatternRepository;
    private final AgentRepository agentRepository;

    public FlywheelRunsService(OptimizationEventRepository optimizationEventRepository,
                               SessionPatternRepository sessionPatternRepository,
                               AgentRepository agentRepository) {
        this.optimizationEventRepository = optimizationEventRepository;
        this.sessionPatternRepository = sessionPatternRepository;
        this.agentRepository = agentRepository;
    }

    /**
     * @param agentType    {@code "user"} / {@code "system"} or {@code null}. Resolves
     *                     to a set of agent ids before the OptEvent query so the
     *                     IN-list lookup is a single SQL statement.
     * @param surfaceType  {@code "skill"} / {@code "prompt"} / {@code "behavior_rule"}
     *                     or {@code null} (no surface filter). Unknown values pass
     *                     through — caller validates if strict matching is wanted.
     * @param limit        capped to {@code [1, 100]} by the controller.
     * @param hideTerminal when {@code true} exclude {@link #TERMINAL_HAPPY_STAGES}.
     */
    @Transactional(readOnly = true)
    public List<FlywheelRunDto> listRecentRuns(String agentType,
                                               String surfaceType,
                                               int limit,
                                               boolean hideTerminal) {
        Pageable page = PageRequest.of(0, limit);

        // r2 W1 fix: split into 4 finders (agent-filter × terminal-filter) so
        // each JPQL is concrete. Hibernate 6.4 (Spring Boot 3.2) does NOT
        // reliably short-circuit a `(:collection IS NULL OR e.x IN :collection)`
        // guard — it evaluates the IN parameter even when the IS NULL branch
        // would skip it, raising IllegalArgumentException when null is bound.
        // The 4 finders take only the parameters they actually use, so there's
        // no nullable-collection footgun.
        List<OptimizationEventEntity> events;
        if (agentType == null || agentType.isBlank()) {
            events = hideTerminal
                    ? optimizationEventRepository.findRecentRunsForFlywheel(
                            surfaceType, TERMINAL_HAPPY_STAGES, page)
                    : optimizationEventRepository.findRecentRunsForFlywheelAllStages(
                            surfaceType, page);
        } else {
            // Resolve agentType → agentIds first so the OptEvent query stays a single
            // IN-list SQL. Returns empty fast (no second query) when no agents match.
            List<Long> agentIds = agentRepository.findByAgentType(agentType).stream()
                    .map(AgentEntity::getId)
                    .toList();
            if (agentIds.isEmpty()) {
                return List.of();
            }
            events = hideTerminal
                    ? optimizationEventRepository.findRecentRunsByAgentIds(
                            surfaceType, agentIds, TERMINAL_HAPPY_STAGES, page)
                    : optimizationEventRepository.findRecentRunsByAgentIdsAllStages(
                            surfaceType, agentIds, page);
        }

        if (events.isEmpty()) {
            return List.of();
        }

        // Batch-fetch dependents to avoid N+1: 1 query per dependent table (pattern,
        // agent) regardless of how many events we returned. Set semantics dedupe
        // when many events share the same pattern_id / agent_id.
        Set<Long> patternIds = events.stream()
                .map(OptimizationEventEntity::getPatternId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));
        Set<Long> agentIds = events.stream()
                .map(OptimizationEventEntity::getAgentId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));

        Map<Long, String> signatureByPatternId = patternIds.isEmpty()
                ? Map.of()
                : sessionPatternRepository.findAllById(patternIds).stream()
                        .collect(Collectors.toMap(
                                SessionPatternEntity::getId,
                                SessionPatternEntity::getSignature,
                                (a, b) -> a));   // dedup safety; ids are unique by PK
        Map<Long, String> agentNameById = agentIds.isEmpty()
                ? Map.of()
                : agentRepository.findAllById(agentIds).stream()
                        .collect(Collectors.toMap(
                                AgentEntity::getId,
                                AgentEntity::getName,
                                (a, b) -> a));

        return events.stream()
                .map(e -> toDto(e, signatureByPatternId, agentNameById))
                .toList();
    }

    private static FlywheelRunDto toDto(OptimizationEventEntity e,
                                        Map<Long, String> signatureByPatternId,
                                        Map<Long, String> agentNameById) {
        String agentName = e.getAgentId() == null ? null : agentNameById.get(e.getAgentId());
        String fullSignature = e.getPatternId() == null ? null : signatureByPatternId.get(e.getPatternId());
        String signatureSnippet = snippet(fullSignature, SIGNATURE_SNIPPET_MAX);
        return new FlywheelRunDto(
                e.getId(),
                e.getAgentId(),
                agentName,
                e.getSurfaceType(),
                e.getPatternId(),
                signatureSnippet,
                e.getStage(),
                errorLabelFor(e.getStage()),
                e.getCreatedAt(),
                e.getUpdatedAt(),
                e.getCandidateSkillDraftUuid(),
                e.getAbRunId(),
                e.getDescription());
    }

    /**
     * Map failed stages to a stable, human-friendly error label. Returns
     * {@code null} for non-failed stages so the FE can render the badge
     * conditionally. Keeps the mapping server-side so the FE doesn't reimplement
     * stage→label.
     */
    static String errorLabelFor(String stage) {
        if (stage == null) return null;
        return switch (stage) {
            case OptimizationEventEntity.STAGE_PROPOSAL_REJECTED -> "Proposal rejected";
            case OptimizationEventEntity.STAGE_CANDIDATE_FAILED -> "Candidate generation failed";
            case OptimizationEventEntity.STAGE_AB_FAILED -> "A/B test failed";
            default -> null;
        };
    }

    /**
     * Truncate a string to {@code max} chars + ellipsis when longer. UTF-16
     * surrogate-safe: when the {@code max}-th char would split a surrogate
     * pair (a high surrogate at index {@code max-1}), back off by one so we
     * never cut mid-codepoint. The signature column is ASCII-ish in practice
     * but the guard is cheap insurance.
     */
    static String snippet(String s, int max) {
        if (s == null) return null;
        if (s.length() <= max) return s;
        int cut = max;
        if (Character.isHighSurrogate(s.charAt(cut - 1))) {
            cut -= 1;
        }
        return s.substring(0, cut) + ELLIPSIS;
    }
}
