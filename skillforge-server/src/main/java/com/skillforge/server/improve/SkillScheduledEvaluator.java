package com.skillforge.server.improve;

import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.SkillEntity;
import com.skillforge.server.repository.SkillEvalHistoryRepository;
import com.skillforge.server.repository.SkillRepository;
import com.skillforge.server.service.AgentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * SKILL-EVOLVE-LOOP Phase 3 (cron 0 4 * * MON) — weekly skill EVAL run.
 *
 * <p>Iterates enabled non-system skills and runs {@link SkillAbEvalService#runBaselineOnly}
 * for each. Skips skills already evaluated in the past 7 days (INV-3) so a
 * reschedule (e.g. operator manually triggered evaluate yesterday) doesn't
 * double-charge LLM calls.
 *
 * <p>Why we need an agentId per skill: {@code runBaselineOnly} runs scenarios
 * via {@link com.skillforge.core.engine.AgentLoopEngine} which requires an
 * AgentDefinition. We resolve a "host" agent for each skill by picking the
 * first agent owned by the skill's owner. A skill with no usable host agent
 * is skipped at WARN — operator visibility for orphaned skills.
 *
 * <p>yaml gate: {@code skillforge.skill-evaluation.scheduled-enabled} (INV-1,
 * default true).
 */
@Component
public class SkillScheduledEvaluator {

    private static final Logger log = LoggerFactory.getLogger(SkillScheduledEvaluator.class);

    /** INV-3: skip skills already evaluated within this window. */
    static final Duration RECENTLY_EVALUATED_WINDOW = Duration.ofDays(7);

    private final SkillRepository skillRepository;
    private final SkillEvalHistoryRepository skillEvalHistoryRepository;
    private final AgentService agentService;
    private final SkillAbEvalService skillAbEvalService;

    private final boolean enabled;

    public SkillScheduledEvaluator(SkillRepository skillRepository,
                                   SkillEvalHistoryRepository skillEvalHistoryRepository,
                                   AgentService agentService,
                                   SkillAbEvalService skillAbEvalService,
                                   @Value("${skillforge.skill-evaluation.scheduled-enabled:true}")
                                   boolean enabled) {
        this.skillRepository = skillRepository;
        this.skillEvalHistoryRepository = skillEvalHistoryRepository;
        this.agentService = agentService;
        this.skillAbEvalService = skillAbEvalService;
        this.enabled = enabled;
    }

    /** Cron: 0 0 4 * * MON — weekly Monday 04:00 (errpeak after Phase 1's 03:00 extract). */
    @Scheduled(cron = "0 0 4 * * MON")
    public void scheduledRun() {
        runOnce();
    }

    public void runOnce() {
        if (!enabled) {
            log.info("SkillScheduledEvaluator disabled via skillforge.skill-evaluation.scheduled-enabled=false");
            return;
        }
        List<SkillEntity> skills;
        try {
            skills = skillRepository.findByIsSystemFalseAndEnabledTrue();
        } catch (Exception e) {
            log.error("SkillScheduledEvaluator: failed to query enabled skills: {}", e.getMessage(), e);
            return;
        }
        if (skills == null || skills.isEmpty()) {
            log.info("SkillScheduledEvaluator: 0 enabled skills");
            return;
        }

        Instant cutoff = Instant.now().minus(RECENTLY_EVALUATED_WINDOW);
        int evaluated = 0;
        int skipped = 0;
        int failed = 0;

        for (SkillEntity skill : skills) {
            Long skillId = skill.getId();
            try {
                long recent = skillEvalHistoryRepository.countBySkillIdAndCreatedAtAfter(skillId, cutoff);
                if (recent > 0) {
                    log.info("SkillScheduledEvaluator: skillId={} evaluated within last 7d ({} rows), skipping",
                            skillId, recent);
                    skipped++;
                    continue;
                }

                String agentId = resolveHostAgentId(skill);
                if (agentId == null) {
                    log.warn("SkillScheduledEvaluator: skillId={} has no usable host agent, skipping",
                            skillId);
                    skipped++;
                    continue;
                }

                skillAbEvalService.runBaselineOnly(
                        skillId,
                        agentId,
                        skill.getOwnerId(),
                        null,
                        "scheduled");
                evaluated++;
            } catch (Exception e) {
                // INV-2: per-skill isolation.
                failed++;
                log.warn("SkillScheduledEvaluator: skillId={} eval failed: {}",
                        skillId, e.getMessage(), e);
            }
        }
        log.info("SkillScheduledEvaluator: done totalSkills={} evaluated={} skipped={} failed={}",
                skills.size(), evaluated, skipped, failed);
    }

    /**
     * Pick a host agent for the skill. V1: first agent owned by the skill's owner.
     * Returns null if no candidate is found — caller should skip + warn.
     *
     * <p>BE-2 may refine this to "first agent whose skillIds JSON references this
     * skill" (so scoring runs in the agent context that actually exercises the
     * skill); for V1 any owner agent is sufficient because runBaselineOnly only
     * needs an AgentDefinition shell to drive the loop engine.
     */
    private String resolveHostAgentId(SkillEntity skill) {
        Long ownerId = skill.getOwnerId();
        if (ownerId == null) {
            return null;
        }
        List<AgentEntity> candidates;
        try {
            candidates = new ArrayList<>(agentService.listAgents(ownerId));
        } catch (Exception e) {
            log.warn("SkillScheduledEvaluator: failed to list agents for ownerId={}: {}",
                    ownerId, e.getMessage());
            return null;
        }
        if (candidates.isEmpty()) {
            return null;
        }
        return String.valueOf(candidates.get(0).getId());
    }
}
