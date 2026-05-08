package com.skillforge.server.improve;

import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.service.AgentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * SKILL-EVOLVE-LOOP Phase 1 (cron 0 3 * * *) — nightly skill draft extraction.
 *
 * <p>Strategy:
 * <ol>
 *   <li>Find distinct agentIds whose top-level sessions saw a real user message
 *       in the last 24h (INV: lastUserMessageAt is the only correct "user
 *       activity" marker — updatedAt is polluted by runtime status / smart-title
 *       writes; see SessionEntity javadoc).</li>
 *   <li>For each agent, skip if its owner already has pending drafts (mirrors
 *       the manual {@code POST /agents/{agentId}/skill-drafts} short-circuit
 *       in {@link com.skillforge.server.controller.SkillDraftController}).</li>
 *   <li>Call {@link SkillDraftService#extractFromRecentSessions(Long, Long)}
 *       inline (LLM call wrapped in per-agent try/catch — INV-2: single agent
 *       failure logs warn and continues with the next agent, never aborts the
 *       whole cron).</li>
 * </ol>
 *
 * <p>yaml gate: {@code skillforge.skill-extraction.scheduled-enabled} (INV-1,
 * default true). Set to false to disable the cron without restart.
 */
@Component
public class SkillDraftScheduledExtractor {

    private static final Logger log = LoggerFactory.getLogger(SkillDraftScheduledExtractor.class);

    private static final Duration WINDOW = Duration.ofHours(24);

    private final SessionRepository sessionRepository;
    private final AgentService agentService;
    private final SkillDraftService skillDraftService;

    /** INV-1: default enabled but operator-toggleable. */
    private final boolean enabled;

    public SkillDraftScheduledExtractor(SessionRepository sessionRepository,
                                        AgentService agentService,
                                        SkillDraftService skillDraftService,
                                        @Value("${skillforge.skill-extraction.scheduled-enabled:true}")
                                        boolean enabled) {
        this.sessionRepository = sessionRepository;
        this.agentService = agentService;
        this.skillDraftService = skillDraftService;
        this.enabled = enabled;
    }

    /**
     * Cron: 0 0 3 * * * — daily 03:00. Errors per-agent are isolated; total
     * count metrics are logged at INFO so operators can diff success/failure
     * across nights.
     */
    @Scheduled(cron = "0 0 3 * * *")
    public void scheduledRun() {
        runOnce();
    }

    /** Public test-only entry point — also callable from ops debug endpoints later. */
    public void runOnce() {
        if (!enabled) {
            log.info("SkillDraftScheduledExtractor disabled via skillforge.skill-extraction.scheduled-enabled=false");
            return;
        }
        Instant since = Instant.now().minus(WINDOW);
        List<Long> agentIds;
        try {
            agentIds = sessionRepository.findDistinctAgentIdsWithRecentUserMessage(since);
        } catch (Exception e) {
            log.error("SkillDraftScheduledExtractor: failed to query eligible agentIds since={}: {}",
                    since, e.getMessage(), e);
            return;
        }

        if (agentIds == null || agentIds.isEmpty()) {
            log.info("SkillDraftScheduledExtractor: 0 eligible agents in last {}h", WINDOW.toHours());
            return;
        }

        int succeeded = 0;
        int skipped = 0;
        int failed = 0;
        for (Long agentId : agentIds) {
            try {
                AgentEntity agent;
                try {
                    agent = agentService.getAgent(agentId);
                } catch (Exception e) {
                    // Agent deleted between session write and cron run — skip silently.
                    log.warn("SkillDraftScheduledExtractor: agentId={} not found, skipping", agentId);
                    skipped++;
                    continue;
                }

                Long ownerId = agent.getOwnerId();
                // Mirror manual endpoint: skip when there are pending drafts (INV: dedupe gate
                // is per-owner; running extract again would just queue more dupes for review).
                if (ownerId != null && skillDraftService.hasPendingDrafts(ownerId)) {
                    log.info("SkillDraftScheduledExtractor: agentId={} ownerId={} already has pending drafts, skipping",
                            agentId, ownerId);
                    skipped++;
                    continue;
                }

                int extracted = skillDraftService.extractFromRecentSessions(agentId, ownerId);
                log.info("SkillDraftScheduledExtractor: agentId={} ownerId={} extracted={}",
                        agentId, ownerId, extracted);
                succeeded++;
            } catch (Exception e) {
                // INV-2: per-agent failure logs WARN and continues — never abort the whole cron.
                failed++;
                log.warn("SkillDraftScheduledExtractor: agentId={} extraction failed: {}",
                        agentId, e.getMessage(), e);
            }
        }
        log.info("SkillDraftScheduledExtractor: done eligible={} succeeded={} skipped={} failed={}",
                agentIds.size(), succeeded, skipped, failed);
    }
}
