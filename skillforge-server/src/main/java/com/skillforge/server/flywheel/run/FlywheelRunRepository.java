package com.skillforge.server.flywheel.run;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * OPT-LOOP-FRAMEWORK Sprint 1: JPA access for {@link FlywheelRunEntity}.
 *
 * <p>The convenience query {@link #findByAgentIdAndLoopKindOrderByCreatedAtDesc}
 * preserves the OPT-REPORT-V1 "reports for an agent newest-first" lookup —
 * callers still need it scoped by {@code loop_kind} because the table now
 * mixes runs from all orchestrators.
 *
 * <p>Sprint 4 extends with {@link #findAllWithFilters} — a generic
 * filter-by-loopKind/agentId/status lookup powering the dashboard
 * {@code /flywheel-runs} page. All three filter params are nullable; a null
 * means "don't filter on this dimension". The OPT-REPORT-V1 query above is
 * kept untouched (AC-7 regression baseline).
 */
public interface FlywheelRunRepository extends JpaRepository<FlywheelRunEntity, String> {

    /**
     * Per-agent newest-first lookup scoped by a {@code loop_kind}. The OPT-REPORT
     * controller passes {@code loop_kind='opt_report'} to mimic the V97 query
     * shape exactly; future "All Flywheel Runs" callers can pass any of the
     * {@code FlywheelRunEntity.LOOP_KIND_*} constants.
     */
    @Query("""
            SELECT r FROM FlywheelRunEntity r
            WHERE r.agentId = :agentId
              AND r.loopKind = :loopKind
            ORDER BY r.createdAt DESC, r.id DESC
            """)
    List<FlywheelRunEntity> findByAgentIdAndLoopKindOrderByCreatedAtDesc(
            @Param("agentId") Long agentId,
            @Param("loopKind") String loopKind,
            Pageable pageable);

    /**
     * Sprint 4 (FR-5): generic filter-by-(loopKind / agentId / status) lookup
     * for the dashboard {@code /flywheel-runs} page. Each filter param is
     * nullable — a null means "don't filter on this dimension". Returns a
     * {@link Page} so the caller gets {@code totalElements} (used to render
     * the "X of Y" page indicator) in the same DB round-trip.
     *
     * <p>Ordered by {@code created_at DESC, id DESC} so newest rows surface
     * first and ties break deterministically (id is a UUID — stable but
     * arbitrary). Indexed via {@code idx_flywheel_run_loop_kind} (Sprint 1) +
     * {@code idx_flywheel_run_status}; the all-null sweep falls back to the
     * primary key with PG's planner handling the order by.
     */
    @Query("""
            SELECT r FROM FlywheelRunEntity r
            WHERE (:loopKind IS NULL OR r.loopKind = :loopKind)
              AND (:agentId IS NULL OR r.agentId = :agentId)
              AND (:status IS NULL OR r.status = :status)
            ORDER BY r.createdAt DESC, r.id DESC
            """)
    Page<FlywheelRunEntity> findAllWithFilters(
            @Param("loopKind") String loopKind,
            @Param("agentId") Long agentId,
            @Param("status") String status,
            Pageable pageable);
}
