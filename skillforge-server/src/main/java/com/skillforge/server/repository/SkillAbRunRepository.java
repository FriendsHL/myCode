package com.skillforge.server.repository;

import com.skillforge.server.entity.SkillAbRunEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface SkillAbRunRepository extends JpaRepository<SkillAbRunEntity, String> {

    List<SkillAbRunEntity> findByParentSkillIdOrderByStartedAtDesc(Long parentSkillId);

    List<SkillAbRunEntity> findByCandidateSkillIdOrderByStartedAtDesc(Long candidateSkillId);

    List<SkillAbRunEntity> findByStatusIn(List<String> statuses);

    /**
     * SKILL-DASHBOARD-POLISH-V2 §G — count auto-promoted (system-triggered) AB
     * runs for a given owner since a cutoff. "System" = {@code triggered_by_user_id = 0}
     * per {@code SkillSelfImproveLoop.SYSTEM_USER_ID}. Owner is resolved by joining
     * {@code parent_skill_id → t_skill.owner_id}.
     */
    @Query("SELECT COUNT(r) FROM SkillAbRunEntity r, SkillEntity s "
            + "WHERE r.parentSkillId = s.id "
            + "AND r.promoted = true "
            + "AND r.triggeredByUserId = 0 "
            + "AND r.createdAt > :after "
            + "AND s.ownerId = :ownerId")
    long countAutoPromotedByOwnerSince(@Param("ownerId") Long ownerId,
                                       @Param("after") Instant after);

    /**
     * FLYWHEEL-VISUAL-STATUS Phase 2: paginated global A/B run listing for the
     * observability panel. Both {@code agentId} and {@code status} are nullable
     * — null means "no filter on that dimension". Ordering matches the
     * per-skill {@code findByParentSkillIdOrderByStartedAtDesc} convention
     * (newest first).
     *
     * <p>Note: {@code t_skill_ab_run} has no {@code surface_type} column —
     * all rows are skill-surface today. Surface filtering is handled at the
     * controller layer (only {@code skill} / null accepted; other surfaces
     * 400) so this query stays simple.
     *
     * <p>Ordering: {@code startedAt DESC, createdAt DESC}. {@code createdAt}
     * is {@code @CreatedDate}-managed (never null), so for rows with
     * {@code startedAt = NULL} (PENDING / SKIPPED before {@code startedAt} is
     * stamped) the {@code createdAt} tiebreaker effectively acts as
     * "NULLS LAST" without requiring the {@code NULLS LAST} JPQL keyword —
     * Postgres supports it but the H2 dialect used by {@code @DataJpaTest}
     * slice tests does not (code review r1 code-B2).
     */
    @Query(value = "SELECT r FROM SkillAbRunEntity r "
            + "WHERE (:agentId IS NULL OR r.agentId = :agentId) "
            + "AND (:status IS NULL OR r.status = :status) "
            + "ORDER BY r.startedAt DESC, r.createdAt DESC",
            countQuery = "SELECT COUNT(r) FROM SkillAbRunEntity r "
                    + "WHERE (:agentId IS NULL OR r.agentId = :agentId) "
                    + "AND (:status IS NULL OR r.status = :status)")
    Page<SkillAbRunEntity> findByFilters(@Param("agentId") String agentId,
                                         @Param("status") String status,
                                         Pageable pageable);
}
