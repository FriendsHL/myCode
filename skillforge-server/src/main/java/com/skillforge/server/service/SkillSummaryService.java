package com.skillforge.server.service;

import com.skillforge.server.dto.DashboardSkillSummaryDto;
import com.skillforge.server.entity.SkillEntity;
import com.skillforge.server.entity.SkillEvalHistoryEntity;
import com.skillforge.server.repository.SkillAbRunRepository;
import com.skillforge.server.repository.SkillDraftRepository;
import com.skillforge.server.repository.SkillEvalHistoryRepository;
import com.skillforge.server.repository.SkillEvolutionRunRepository;
import com.skillforge.server.repository.SkillRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * SKILL-DASHBOARD-POLISH-V2 §G — aggregates per-owner skill metrics for the
 * dashboard SkillSummaryCard.
 *
 * <p>Implementation note: the {@code lowScoreSkillsCount} pass walks each
 * enabled skill row and reads its latest eval-history row. Per-skill counts
 * are typically &lt;100 per owner so the N+1 cost is bounded; if it ever grows
 * we can switch to a single JPQL aggregating MAX(created_at) per skill.
 */
@Service
public class SkillSummaryService {

    private static final Logger log = LoggerFactory.getLogger(SkillSummaryService.class);

    /** SKILL-DASHBOARD-POLISH-V2 §G — composite score below this counts as "low". */
    private static final double LOW_SCORE_THRESHOLD = 60.0;

    /** SKILL-DASHBOARD-POLISH-V2 §G — sliding window for "this week" stats. */
    private static final int WEEK_DAYS = 7;

    private final SkillRepository skillRepository;
    private final SkillDraftRepository skillDraftRepository;
    private final SkillEvolutionRunRepository skillEvolutionRunRepository;
    private final SkillAbRunRepository skillAbRunRepository;
    private final SkillEvalHistoryRepository skillEvalHistoryRepository;

    public SkillSummaryService(SkillRepository skillRepository,
                               SkillDraftRepository skillDraftRepository,
                               SkillEvolutionRunRepository skillEvolutionRunRepository,
                               SkillAbRunRepository skillAbRunRepository,
                               SkillEvalHistoryRepository skillEvalHistoryRepository) {
        this.skillRepository = skillRepository;
        this.skillDraftRepository = skillDraftRepository;
        this.skillEvolutionRunRepository = skillEvolutionRunRepository;
        this.skillAbRunRepository = skillAbRunRepository;
        this.skillEvalHistoryRepository = skillEvalHistoryRepository;
    }

    @Transactional(readOnly = true)
    public DashboardSkillSummaryDto getSummaryStats(Long ownerId) {
        if (ownerId == null) {
            throw new IllegalArgumentException("ownerId must not be null");
        }
        Instant weekAgo = Instant.now().minus(WEEK_DAYS, ChronoUnit.DAYS);

        long autoUpgraded = 0L;
        long failedEvolve = 0L;
        try {
            autoUpgraded = skillAbRunRepository.countAutoPromotedByOwnerSince(ownerId, weekAgo);
        } catch (Exception e) {
            log.warn("countAutoPromotedByOwnerSince failed for ownerId={}: {}", ownerId, e.getMessage());
        }
        try {
            failedEvolve = skillEvolutionRunRepository.countByOwnerAndStatusAndCreatedAtAfter(
                    ownerId, "FAILED", weekAgo);
        } catch (Exception e) {
            log.warn("countByOwnerAndStatusAndCreatedAtAfter failed for ownerId={}: {}",
                    ownerId, e.getMessage());
        }

        long pendingDrafts = skillDraftRepository.countByOwnerIdAndStatus(ownerId, "draft");

        // Walk owner's skill rows; count enabled and low-score ones in one pass.
        List<SkillEntity> ownerSkills;
        try {
            ownerSkills = skillRepository.findByOwnerId(ownerId);
        } catch (Exception e) {
            log.warn("findByOwnerId failed for ownerId={}: {}", ownerId, e.getMessage());
            ownerSkills = List.of();
        }
        long totalEnabled = 0L;
        long lowScore = 0L;
        for (SkillEntity skill : ownerSkills) {
            if (!skill.isEnabled() || skill.isSystem()) {
                continue;
            }
            totalEnabled++;
            try {
                SkillEvalHistoryEntity latest = skillEvalHistoryRepository
                        .findFirstBySkillIdOrderByCreatedAtDesc(skill.getId())
                        .orElse(null);
                if (latest != null && latest.getCompositeScore() != null
                        && latest.getCompositeScore() < LOW_SCORE_THRESHOLD) {
                    lowScore++;
                }
            } catch (Exception e) {
                log.warn("findFirstBySkillIdOrderByCreatedAtDesc failed for skillId={}: {}",
                        skill.getId(), e.getMessage());
            }
        }

        return new DashboardSkillSummaryDto(
                autoUpgraded,
                pendingDrafts,
                failedEvolve,
                totalEnabled,
                lowScore
        );
    }
}
