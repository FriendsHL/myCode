package com.skillforge.server.repository;

import com.skillforge.server.entity.SkillEvolutionRunEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface SkillEvolutionRunRepository extends JpaRepository<SkillEvolutionRunEntity, String> {

    List<SkillEvolutionRunEntity> findBySkillIdOrderByCreatedAtDesc(Long skillId);

    List<SkillEvolutionRunEntity> findByStatus(String status);

    List<SkillEvolutionRunEntity> findBySkillIdAndStatusIn(Long skillId, List<String> statuses);

    /**
     * SKILL-DASHBOARD-POLISH-V2 §G — count failed evolve runs for a given owner
     * since a cutoff. Joins via {@code skill_id → t_skill.owner_id} so we don't
     * surface another tenant's failures. {@link SkillEvolutionRunEntity} has no
     * ownerId column; we use SkillEntity as the ownership boundary.
     */
    @Query("SELECT COUNT(r) FROM SkillEvolutionRunEntity r, SkillEntity s "
            + "WHERE r.skillId = s.id "
            + "AND r.status = :status "
            + "AND r.createdAt > :after "
            + "AND s.ownerId = :ownerId")
    long countByOwnerAndStatusAndCreatedAtAfter(@Param("ownerId") Long ownerId,
                                                @Param("status") String status,
                                                @Param("after") Instant after);
}
