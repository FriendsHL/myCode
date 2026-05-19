package com.skillforge.server.repository;

import com.skillforge.server.entity.SkillDraftEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SkillDraftRepository extends JpaRepository<SkillDraftEntity, String> {

    List<SkillDraftEntity> findByOwnerIdOrderByCreatedAtDesc(Long ownerId);

    /**
     * FLYWHEEL-VISUAL-STATUS Phase 2: filter by {@code source} column for the
     * global observability panel's draft-source drill-down. {@code source} is
     * the V91 free-form provenance string (see {@link SkillDraftEntity#source}).
     */
    List<SkillDraftEntity> findByOwnerIdAndSourceOrderByCreatedAtDesc(Long ownerId, String source);

    List<SkillDraftEntity> findByOwnerIdAndStatus(Long ownerId, String status);

    long countByOwnerIdAndStatus(Long ownerId, String status);

    /**
     * Count pending drafts for a specific (owner, agent) tuple. Drafts don't carry
     * an agent_id column directly, so we resolve through their source session.
     * Used by the manual extract gate so different agents can extract in parallel
     * even when one agent's drafts are still pending review.
     */
    @Query("""
            SELECT COUNT(d) FROM SkillDraftEntity d
            WHERE d.ownerId = :ownerId AND d.status = :status
              AND d.sourceSessionId IN (
                SELECT s.id FROM SessionEntity s WHERE s.agentId = :agentId
              )
            """)
    long countByOwnerIdAndStatusForAgent(
            @Param("ownerId") Long ownerId,
            @Param("status") String status,
            @Param("agentId") Long agentId);

    /**
     * Count pending drafts with no source-session anchor — legacy rows written
     * before the per-(owner, agent) gate landed. These can't be tied to an
     * agent, so the controller blocks all new extractions until they're cleared.
     */
    long countByOwnerIdAndStatusAndSourceSessionIdIsNull(Long ownerId, String status);

    // SKILL-DASHBOARD-POLISH-V2.5 — paged variants for the new GET /api/skills/drafts endpoint.
    Page<SkillDraftEntity> findByOwnerId(Long ownerId, Pageable pageable);

    Page<SkillDraftEntity> findByOwnerIdAndStatus(Long ownerId, String status, Pageable pageable);

    /** Pessimistic write lock — prevents concurrent approve/discard on the same draft. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM SkillDraftEntity d WHERE d.id = :id")
    Optional<SkillDraftEntity> findByIdForUpdate(@Param("id") String id);
}
