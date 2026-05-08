package com.skillforge.server.repository;

import com.skillforge.server.entity.SkillEvalHistoryEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * SKILL-EVOLVE-LOOP (V63): query surface for {@code t_skill_eval_history}.
 *
 * <p>Methods exposed here are the contract BE-2 (SkillSelfImproveLoop +
 * SkillScheduledEvaluator 7-day skip + dashboard /eval-history endpoint)
 * depends on — keep signatures stable.
 */
public interface SkillEvalHistoryRepository extends JpaRepository<SkillEvalHistoryEntity, Long> {

    /** Latest history row for a skill (used by SkillSelfImproveLoop INV-4). */
    Optional<SkillEvalHistoryEntity> findFirstBySkillIdOrderByCreatedAtDesc(Long skillId);

    /** Recent N history rows for FE eval-history sparkline / curve. */
    List<SkillEvalHistoryEntity> findBySkillIdOrderByCreatedAtDesc(Long skillId, Pageable pageable);

    /**
     * Used by {@link com.skillforge.server.improve.SkillScheduledEvaluator} INV-3:
     * skip skills already evaluated within the last 7 days.
     */
    long countBySkillIdAndCreatedAtAfter(Long skillId, Instant after);
}
