package com.skillforge.server.canary;

import com.skillforge.server.entity.CanaryRolloutEntity;
import com.skillforge.server.repository.CanaryRolloutRepository;
import com.skillforge.server.repository.SessionAnnotationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * SKILL-CANARY-ROLLOUT V2 Phase 1.2: per-session skill-version allocator.
 *
 * <p>Called by {@code DefaultSessionSkillResolver} for each user-bound skill
 * name in {@code agentDef.skillIds}. Default (no active canary) returns the
 * baseline name unchanged, preserving today's "一刀切" behaviour — this is the
 * safe path that every existing skill takes until an operator explicitly opens
 * a canary.
 *
 * <p>When a canary is active, the algorithm (tech-design.md §5) is:
 * <ol>
 *   <li>Find the single active rollout for (agentId, surface_type='skill',
 *       baseline_skill_name=baselineSkillName).</li>
 *   <li>Short-circuit on degenerate percentages: 100 → candidate, 0 → baseline.</li>
 *   <li>Otherwise check {@code t_session_annotation} for a prior
 *       {@code canary_group} row pinning this session to a version (per
 *       "session 锁版本" ratify decision — a session never switches version
 *       mid-flight).</li>
 *   <li>For brand-new sessions, hash the sessionId into a 0..99 bucket and
 *       pick candidate if bucket &lt; pct, then persist the assignment via
 *       {@link SessionAnnotationRepository#upsertSkipDuplicate} so subsequent
 *       turns of the same session read the pinned value.</li>
 * </ol>
 *
 * <p><b>Idempotency</b>: persistence relies on {@code ON CONFLICT DO NOTHING}
 * (per V1 W2 PG aborted-tx footgun fix). A second concurrent allocate for
 * the same sessionId may race, but both writes converge to one row (UNIQUE
 * constraint) and the follow-up reads use {@code findCanaryGroup} which
 * resolves deterministically.
 *
 * <p><b>Hash stability</b>: {@code (sessionId.hashCode() &amp; 0x7FFFFFFF) % 100}
 * is stable per JVM. {@code String.hashCode()} is JLS-defined so behaviour is
 * identical across JVM versions — safe to use without a custom hash.
 */
@Component
public class CanaryAllocator {

    private static final Logger log = LoggerFactory.getLogger(CanaryAllocator.class);

    /** Surface namespace prefix for the canary_group annotation_value. */
    static final String SURFACE_SKILL = CanaryRolloutEntity.SURFACE_SKILL;
    static final String ANNOTATION_TYPE = "canary_group";
    static final String ANNOTATION_SOURCE = "system";

    private final CanaryRolloutRepository canaryRepository;
    private final SessionAnnotationRepository sessionAnnotationRepository;

    public CanaryAllocator(CanaryRolloutRepository canaryRepository,
                           SessionAnnotationRepository sessionAnnotationRepository) {
        this.canaryRepository = canaryRepository;
        this.sessionAnnotationRepository = sessionAnnotationRepository;
    }

    /**
     * Decide which skill name to actually mount for this session turn.
     *
     * @param sessionId         current session id (must be non-null and stable
     *                          across the session's lifetime)
     * @param agentId           id of the agent owning this skill binding;
     *                          {@code null} short-circuits to baseline
     *                          (legacy / test paths)
     * @param baselineSkillName the skill name the resolver was originally
     *                          going to mount (never {@code null})
     * @return the skill name to mount: either {@code baselineSkillName}
     *         unchanged or the rollout's candidate name
     */
    public String allocate(String sessionId, Long agentId, String baselineSkillName) {
        if (baselineSkillName == null) {
            return null;
        }
        if (sessionId == null || agentId == null) {
            // No session / no agent context → behave like today (baseline).
            return baselineSkillName;
        }

        Optional<CanaryRolloutEntity> activeOpt;
        try {
            activeOpt = canaryRepository.findActiveCanaryForSkill(agentId, baselineSkillName);
        } catch (DataAccessException e) {
            log.warn("CanaryAllocator: repository lookup failed for agent={}, skill={}; falling back to baseline: {}",
                    agentId, baselineSkillName, e.getMessage());
            return baselineSkillName;
        }
        if (activeOpt.isEmpty()) {
            return baselineSkillName;
        }

        CanaryRolloutEntity canary = activeOpt.get();
        int pct = canary.getRolloutPercentage() == null ? 0 : canary.getRolloutPercentage();
        String candidate = canary.getCandidateSkillName();

        if (pct >= 100) {
            // Fully rolled out — every session sees candidate. (Note: the
            // promote-to-100 path normally transitions stage='canary' →
            // 'production' so this branch is defensive against operator
            // races where pct was nudged to 100 but stage hasn't flipped yet.)
            return candidate;
        }
        if (pct <= 0) {
            return baselineSkillName;
        }

        // Session already pinned? — honour the prior assignment so the same
        // session never sees two versions during its lifetime.
        Optional<String> existingGroup;
        try {
            existingGroup = sessionAnnotationRepository.findCanaryGroup(sessionId, SURFACE_SKILL);
        } catch (DataAccessException e) {
            log.warn("CanaryAllocator: findCanaryGroup failed for session={}; falling back to baseline: {}",
                    sessionId, e.getMessage());
            return baselineSkillName;
        }
        if (existingGroup.isPresent()) {
            String prior = parseSkillNameFromGroupValue(existingGroup.get());
            if (prior != null) {
                return prior;
            }
            // Malformed value — fall through to fresh allocation; we still try
            // to persist below so the next call has a clean row.
            log.warn("CanaryAllocator: malformed canary_group value '{}' for session={}, falling through",
                    existingGroup.get(), sessionId);
        }

        // Fresh allocation — deterministic hash bucket.
        int bucket = (sessionId.hashCode() & 0x7FFFFFFF) % 100;
        String picked = bucket < pct ? candidate : baselineSkillName;

        // Persist the assignment so subsequent turns of the same session
        // converge. V1 W2 fix: ON CONFLICT DO NOTHING (never saveAndFlush+catch).
        try {
            sessionAnnotationRepository.upsertSkipDuplicate(
                    sessionId,
                    ANNOTATION_TYPE,
                    SURFACE_SKILL + ":" + picked,
                    ANNOTATION_SOURCE,
                    BigDecimal.ONE,
                    null);
        } catch (DataAccessException e) {
            // Persistence failure must NOT block the user turn — log and
            // proceed with the allocated name. Worst case the next turn
            // re-hashes (same sessionId → same bucket → same result).
            log.warn("CanaryAllocator: upsertSkipDuplicate failed for session={}, picked={}: {}",
                    sessionId, picked, e.getMessage());
        }
        return picked;
    }

    /**
     * Parse {@code "skill:my-name"} back into {@code "my-name"}. Defensive
     * against unexpected formats — returns {@code null} so the caller can fall
     * back to a fresh allocation rather than mounting a bogus name.
     */
    static String parseSkillNameFromGroupValue(String value) {
        if (value == null) return null;
        String prefix = SURFACE_SKILL + ":";
        if (!value.startsWith(prefix)) {
            return null;
        }
        String name = value.substring(prefix.length());
        return name.isEmpty() ? null : name;
    }
}
