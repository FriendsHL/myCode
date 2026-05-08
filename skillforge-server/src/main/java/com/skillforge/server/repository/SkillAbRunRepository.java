package com.skillforge.server.repository;

import com.skillforge.server.entity.SkillAbRunEntity;
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
}
