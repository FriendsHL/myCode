package com.skillforge.server.memory.llmsynth;

import com.skillforge.server.config.MemoryProperties;
import com.skillforge.server.repository.SessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * MEMORY-LLM-SYNTHESIS (V68): daily cron driving {@link LlmMemorySynthesizer#synthesize(Long)}
 * for every recently-active user.
 *
 * <p>Per D1 ratify 2026-05-11, schedule is {@code 0 30 4 * * *} (04:30 daily) — staggered
 * 1h after {@link com.skillforge.server.memory.MemoryConsolidationScheduler} so the
 * synthesizer sees the post-consolidation ACTIVE set.
 *
 * <p>yaml gate {@code skillforge.memory.llm-synthesis.scheduled-enabled} defaults to
 * {@code false} (D12 first-week observation period). Per-user try/catch so a single
 * user's failure doesn't abort the cron.
 */
@Component
public class LlmMemorySynthesisScheduler {

    private static final Logger log = LoggerFactory.getLogger(LlmMemorySynthesisScheduler.class);

    private final SessionRepository sessionRepository;
    private final LlmMemorySynthesizer synthesizer;
    private final MemoryProperties memoryProperties;

    public LlmMemorySynthesisScheduler(SessionRepository sessionRepository,
                                       LlmMemorySynthesizer synthesizer,
                                       MemoryProperties memoryProperties) {
        this.sessionRepository = sessionRepository;
        this.synthesizer = synthesizer;
        this.memoryProperties = memoryProperties;
    }

    /** Daily cron at 04:30 — D1 ratify. Gate is enforced (bypassGate=false). */
    @Scheduled(cron = "0 30 4 * * *")
    public void scheduledRun() {
        runOnce(null, false);
    }

    /**
     * Backward-compat overload: gate enforced. Old call-sites that didn't distinguish
     * cron-vs-admin keep the same gated semantics.
     *
     * <p>r2 fix R2-1: admin endpoints with {@code userIdFilter=null} (full-scan trigger)
     * must call {@link #runOnce(Long, boolean)} with {@code bypassGate=true} so the
     * default-disabled scheduler still services manual admin runs (per PRD flow B).
     * Otherwise the call returns silently with empty counts and HTTP 200 — admin can't
     * tell the difference between "no eligible users" and "gate killed it".
     */
    public SchedulerSummary runOnce(Long userIdFilter) {
        return runOnce(userIdFilter, false);
    }

    /**
     * Public entry — cron and the admin endpoint both call here.
     *
     * @param userIdFilter optional restriction; null = scan every active user.
     * @param bypassGate   when {@code true}, ignore {@code scheduled-enabled} and always run.
     *                     Admin manual-trigger paths pass {@code true}; cron always passes
     *                     {@code false}.
     */
    public SchedulerSummary runOnce(Long userIdFilter, boolean bypassGate) {
        MemoryProperties.LlmSynthesis cfg = memoryProperties.getLlmSynthesis();
        if (!bypassGate && !cfg.isScheduledEnabled()) {
            log.info("LlmMemorySynthesisScheduler disabled (skillforge.memory.llm-synthesis.scheduled-enabled=false)");
            return SchedulerSummary.disabled();
        }

        List<Long> userIds;
        if (userIdFilter != null) {
            userIds = List.of(userIdFilter);
        } else {
            int lookback = Math.max(cfg.getActiveUserLookbackDays(), 1);
            Instant since = Instant.now().minus(Duration.ofDays(lookback));
            try {
                userIds = sessionRepository.findDistinctUserIdsWithRecentUserMessage(since);
            } catch (Exception e) {
                log.error("LlmMemorySynthesisScheduler: failed to query active userIds since={}: {}",
                        since, e.getMessage(), e);
                return SchedulerSummary.empty();
            }
        }
        if (userIds == null || userIds.isEmpty()) {
            log.info("LlmMemorySynthesisScheduler: 0 eligible users{}",
                    userIdFilter != null ? " (filter=" + userIdFilter + ")" : "");
            return SchedulerSummary.empty();
        }

        int succeeded = 0, failed = 0;
        int totalDedup = 0, totalReflection = 0, totalOptimize = 0, totalContradiction = 0;
        long totalInputTokens = 0L, totalOutputTokens = 0L;
        double totalUsd = 0.0;
        for (Long userId : userIds) {
            try {
                SynthesisRunResult r = synthesizer.synthesize(userId);
                if (r != null && !r.skipped()) {
                    totalDedup += r.dedupProposals();
                    totalReflection += r.reflectionProposals();
                    totalOptimize += r.optimizeProposals();
                    totalContradiction += r.contradictionProposals();
                    totalInputTokens += r.inputTokens();
                    totalOutputTokens += r.outputTokens();
                    totalUsd += r.estimatedUsd();
                }
                succeeded++;
            } catch (Exception e) {
                // INV-2: per-user failure logs WARN, continues.
                failed++;
                log.warn("LlmMemorySynthesisScheduler: userId={} synthesize failed: {}",
                        userId, e.getMessage(), e);
            }
        }
        log.info("LlmMemorySynthesisScheduler done eligible={} succeeded={} failed={} "
                        + "dedup={} reflection={} optimize={} contradiction={} "
                        + "inputTokens={} outputTokens={} estimatedUsd={}",
                userIds.size(), succeeded, failed,
                totalDedup, totalReflection, totalOptimize, totalContradiction,
                totalInputTokens, totalOutputTokens, String.format("%.6f", totalUsd));
        return new SchedulerSummary(userIds.size(), succeeded, failed,
                totalDedup, totalReflection, totalOptimize, totalContradiction,
                totalInputTokens, totalOutputTokens, totalUsd);
    }

    /** Convenience overload — same as runOnce(null) but reserved for tests / programmatic callers. */
    public SchedulerSummary runOnce() {
        return runOnce(null);
    }

    public record SchedulerSummary(
            int eligible,
            int succeeded,
            int failed,
            int dedupProposals,
            int reflectionProposals,
            int optimizeProposals,
            int contradictionProposals,
            long inputTokens,
            long outputTokens,
            double estimatedUsd) {
        public static SchedulerSummary disabled() {
            return new SchedulerSummary(0, 0, 0, 0, 0, 0, 0, 0L, 0L, 0.0);
        }
        public static SchedulerSummary empty() {
            return new SchedulerSummary(0, 0, 0, 0, 0, 0, 0, 0L, 0L, 0.0);
        }
    }
}
