package com.skillforge.server.attribution;

import com.skillforge.server.AbstractPostgresIT;
import com.skillforge.server.entity.OptimizationEventEntity;
import com.skillforge.server.entity.SessionPatternEntity;
import com.skillforge.server.repository.OptimizationEventRepository;
import com.skillforge.server.repository.SessionPatternRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V3 ATTRIBUTION-AGENT Phase 1.1: persistence integration tests for
 * {@code t_optimization_event} (V80) and the {@code attribution-curator}
 * agent seed (V81).
 *
 * <p>Mirrors {@code CanaryPersistenceIT} / {@code SessionAnnotationPersistenceIT}:
 * shared PostgreSQL container with Flyway migrations applied (no schema
 * mocking). Test isolation comes from {@code @DataJpaTest} rolling back
 * each test.
 *
 * <p>Covers (per Phase 1.1 task list):
 * <ol>
 *   <li>{@code t_optimization_event} round-trip ({@code save} →
 *       {@link OptimizationEventRepository#findByStageOrderByCreatedAtDesc}).</li>
 *   <li>{@link OptimizationEventRepository#findByPatternIdAndCooldownExpiresAtAfter}
 *       returns only rows whose cooldown window is still active (Phase 1.2
 *       24h cooldown gate).</li>
 *   <li>{@link OptimizationEventRepository#findByPatternIdAndStage} filters
 *       to the requested stage; multiple stages for the same pattern coexist.</li>
 *   <li>FK {@code ON DELETE CASCADE} from {@code t_session_pattern} purges
 *       child {@code t_optimization_event} rows when the parent pattern is
 *       deleted.</li>
 * </ol>
 *
 * <p>Skipped automatically on machines without Docker
 * (@{@code Testcontainers(disabledWithoutDocker = true)} in
 * {@link AbstractPostgresIT}).
 */
@DisplayName("V3 ATTRIBUTION-AGENT persistence IT")
class OptimizationEventPersistenceIT extends AbstractPostgresIT {

    @Autowired
    private OptimizationEventRepository eventRepository;

    @Autowired
    private SessionPatternRepository patternRepository;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void cleanUp() {
        // Child first so FK constraints don't fight the cleanup (V77/V74
        // pattern — even though the FK is ON DELETE CASCADE, explicit child
        // delete keeps test order independent of cascade timing).
        eventRepository.deleteAll();
        patternRepository.deleteAll();
    }

    private SessionPatternEntity pattern(String signature, String outcome, String surface) {
        SessionPatternEntity p = new SessionPatternEntity();
        p.setSignature(signature);
        p.setOutcome(outcome);
        p.setSuspectSurface(surface);
        p.setSuggestedSurface(surface);
        p.setMemberCount(3);
        Instant now = Instant.now();
        p.setFirstSeenAt(now);
        p.setLastSeenAt(now);
        p.setCreatedAt(now);
        p.setUpdatedAt(now);
        return p;
    }

    private OptimizationEventEntity event(Long patternId,
                                          Long agentId,
                                          String surface,
                                          String stage,
                                          Instant cooldownExpiresAt) {
        OptimizationEventEntity e = new OptimizationEventEntity();
        e.setPatternId(patternId);
        e.setAgentId(agentId);
        e.setSurfaceType(surface);
        e.setChangeType("rewrite_skill_md");
        e.setDescription("Member sessions show repeated tool retries; "
                + "skill body never instructs pre-validation.");
        e.setExpectedImpact("Expect outcome failure rate to drop on this signature.");
        e.setConfidence(new BigDecimal("0.72"));
        e.setRisk(OptimizationEventEntity.RISK_MEDIUM);
        e.setStage(stage);
        e.setAttributionSessionId("sess-curator-" + patternId);
        e.setCooldownExpiresAt(cooldownExpiresAt);
        Instant now = Instant.now();
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        return e;
    }

    @Test
    @DisplayName("save round-trips and findByStageOrderByCreatedAtDesc returns newest first")
    void save_findByStage_roundtrips() {
        SessionPatternEntity p1 = patternRepository.save(pattern("sig-1", "failure", "skill"));
        SessionPatternEntity p2 = patternRepository.save(pattern("sig-2", "failure", "prompt"));

        Instant cooldown = Instant.now().plus(24, ChronoUnit.HOURS);

        OptimizationEventEntity oldEvent = event(p1.getId(), 42L,
                OptimizationEventEntity.SURFACE_SKILL,
                OptimizationEventEntity.STAGE_PROPOSAL_PENDING,
                cooldown);
        oldEvent.setCreatedAt(Instant.now().minus(2, ChronoUnit.HOURS));
        eventRepository.save(oldEvent);

        OptimizationEventEntity newEvent = event(p2.getId(), 99L,
                OptimizationEventEntity.SURFACE_PROMPT,
                OptimizationEventEntity.STAGE_PROPOSAL_PENDING,
                cooldown);
        eventRepository.save(newEvent);

        // Non-matching stage row — must NOT be returned by the filter.
        eventRepository.save(event(p2.getId(), 99L,
                OptimizationEventEntity.SURFACE_PROMPT,
                OptimizationEventEntity.STAGE_PROPOSAL_REJECTED,
                null));

        List<OptimizationEventEntity> pending = eventRepository
                .findByStageOrderByCreatedAtDesc(
                        OptimizationEventEntity.STAGE_PROPOSAL_PENDING,
                        PageRequest.of(0, 10));

        assertThat(pending).hasSize(2);
        assertThat(pending).extracting(OptimizationEventEntity::getStage)
                .allMatch(OptimizationEventEntity.STAGE_PROPOSAL_PENDING::equals);
        // Newest first: newEvent (now) comes before oldEvent (-2h).
        assertThat(pending.get(0).getAgentId()).isEqualTo(99L);
        assertThat(pending.get(1).getAgentId()).isEqualTo(42L);
        // Confidence + risk preserve precision across the round-trip.
        assertThat(pending.get(0).getConfidence())
                .isEqualByComparingTo(new BigDecimal("0.72"));
        assertThat(pending.get(0).getRisk())
                .isEqualTo(OptimizationEventEntity.RISK_MEDIUM);
    }

    @Test
    @DisplayName("findByPatternIdAndCooldownExpiresAtAfter returns only active-cooldown rows for that pattern")
    void findByPatternIdAndCooldownExpiresAtAfter_returnsActiveWithinCooldown() {
        SessionPatternEntity p = patternRepository.save(pattern("sig-cooldown", "failure", "skill"));
        SessionPatternEntity other = patternRepository.save(pattern("sig-other", "failure", "prompt"));

        Instant now = Instant.now();

        // Active cooldown: expires 1h in the future → must match.
        OptimizationEventEntity active = eventRepository.save(event(p.getId(), 42L,
                OptimizationEventEntity.SURFACE_SKILL,
                OptimizationEventEntity.STAGE_PROPOSAL_PENDING,
                now.plus(1, ChronoUnit.HOURS)));

        // Expired cooldown: 1h in the past → must NOT match.
        eventRepository.save(event(p.getId(), 42L,
                OptimizationEventEntity.SURFACE_SKILL,
                OptimizationEventEntity.STAGE_PROPOSAL_REJECTED,
                now.minus(1, ChronoUnit.HOURS)));

        // Null cooldown (event past cooldown column semantics) → must NOT match.
        eventRepository.save(event(p.getId(), 42L,
                OptimizationEventEntity.SURFACE_SKILL,
                OptimizationEventEntity.STAGE_PROMOTED,
                null));

        // Different pattern — must NOT leak across patternId filter.
        eventRepository.save(event(other.getId(), 42L,
                OptimizationEventEntity.SURFACE_PROMPT,
                OptimizationEventEntity.STAGE_PROPOSAL_PENDING,
                now.plus(1, ChronoUnit.HOURS)));

        List<OptimizationEventEntity> hits = eventRepository
                .findByPatternIdAndCooldownExpiresAtAfter(p.getId(), now);

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).getId()).isEqualTo(active.getId());
        assertThat(hits.get(0).getCooldownExpiresAt()).isAfter(now);
    }

    @Test
    @DisplayName("findByPatternIdAndStage returns only events at the requested stage for that pattern")
    void findByPatternIdAndStage_returnsMatchingEvents() {
        SessionPatternEntity p = patternRepository.save(pattern("sig-stage", "failure", "skill"));
        SessionPatternEntity other = patternRepository.save(pattern("sig-other-stage", "failure", "prompt"));

        Instant cooldown = Instant.now().plus(24, ChronoUnit.HOURS);

        // Two events at the requested stage on the target pattern.
        OptimizationEventEntity pending1 = eventRepository.save(event(p.getId(), 42L,
                OptimizationEventEntity.SURFACE_SKILL,
                OptimizationEventEntity.STAGE_PROPOSAL_PENDING,
                cooldown));
        OptimizationEventEntity pending2 = eventRepository.save(event(p.getId(), 42L,
                OptimizationEventEntity.SURFACE_SKILL,
                OptimizationEventEntity.STAGE_PROPOSAL_PENDING,
                cooldown));

        // Different stage on the same pattern — filter must exclude.
        eventRepository.save(event(p.getId(), 42L,
                OptimizationEventEntity.SURFACE_SKILL,
                OptimizationEventEntity.STAGE_AB_RUNNING,
                cooldown));

        // Same stage but different pattern — filter must exclude.
        eventRepository.save(event(other.getId(), 42L,
                OptimizationEventEntity.SURFACE_PROMPT,
                OptimizationEventEntity.STAGE_PROPOSAL_PENDING,
                cooldown));

        List<OptimizationEventEntity> hits = eventRepository
                .findByPatternIdAndStage(p.getId(),
                        OptimizationEventEntity.STAGE_PROPOSAL_PENDING);

        assertThat(hits).hasSize(2);
        assertThat(hits).extracting(OptimizationEventEntity::getId)
                .containsExactlyInAnyOrder(pending1.getId(), pending2.getId());
        assertThat(hits).extracting(OptimizationEventEntity::getStage)
                .allMatch(OptimizationEventEntity.STAGE_PROPOSAL_PENDING::equals);
    }

    @Test
    @DisplayName("deleting a t_session_pattern cascades to t_optimization_event via FK ON DELETE CASCADE")
    void cascade_delete_pattern_removes_events() {
        SessionPatternEntity p = patternRepository.save(pattern("sig-cascade", "failure", "skill"));
        Long patternId = p.getId();

        Instant cooldown = Instant.now().plus(24, ChronoUnit.HOURS);
        eventRepository.save(event(patternId, 42L,
                OptimizationEventEntity.SURFACE_SKILL,
                OptimizationEventEntity.STAGE_PROPOSAL_PENDING,
                cooldown));
        eventRepository.save(event(patternId, 42L,
                OptimizationEventEntity.SURFACE_SKILL,
                OptimizationEventEntity.STAGE_AB_RUNNING,
                cooldown));
        eventRepository.flush();

        assertThat(eventRepository.findByPatternIdOrderByCreatedAtAsc(patternId)).hasSize(2);

        // Use a native DELETE so we exercise the FK contract directly (the
        // JPA cascade mapping is intentionally NOT configured — the FK is
        // the contract we're verifying, V74 / V77 pattern).
        entityManager.createNativeQuery("DELETE FROM t_session_pattern WHERE id = :id")
                .setParameter("id", patternId)
                .executeUpdate();
        entityManager.clear();

        assertThat(patternRepository.findById(patternId)).isEmpty();
        assertThat(eventRepository.findByPatternIdOrderByCreatedAtAsc(patternId)).isEmpty();
        assertThat(eventRepository.countByPatternId(patternId)).isZero();
    }
}
