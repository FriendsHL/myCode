package com.skillforge.server.canary;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * V2 SKILL-CANARY-ROLLOUT — Phase 1.0 red tests.
 *
 * <p>Spec source:
 * <ul>
 *   <li>{@code docs/requirements/active/SKILL-CANARY-ROLLOUT/tech-design.md} §5
 *       (CanaryAllocator algorithm)</li>
 *   <li>{@code docs/requirements/active/SKILL-CANARY-ROLLOUT/tech-design.md} §8
 *       (Phase 1.0 — write red tests; implementation in Phase 1.2)</li>
 * </ul>
 *
 * <p>These tests are {@link Disabled} because the production class
 * {@code com.skillforge.server.canary.CanaryAllocator} does not yet exist —
 * shipping live (un-disabled) bodies would cause a compile failure and block
 * the rest of the suite. They will be implemented in Phase 1.2 alongside
 * the allocator itself, and the {@code @Disabled} annotations removed once
 * the allocator + its repositories ({@code CanaryRolloutRepository},
 * {@code SessionAnnotationRepository.findCanaryGroup}) are in place.
 *
 * <p>Until then the bodies remain as TODO outlines so a reviewer can verify
 * the spec ↔ test mapping without running the suite. The test file is
 * intentionally light on infrastructure (no Mockito wiring) — Phase 1.2 will
 * add {@code @ExtendWith(MockitoExtension.class)} with mocked repositories
 * once the dependencies exist.
 */
@Disabled("V2 Phase 1.0 red test — CanaryAllocator implementation not yet written; will turn green at Phase 1.2")
class CanaryAllocatorRedTest {

    /**
     * Spec: tech-design.md §5 step 1 — when no active canary rollout exists
     * for the (agentId, surface_type='skill') key, the allocator must return
     * the caller-provided {@code baselineSkillId} unchanged. This is the
     * "default 一刀切" backward-compatible path that protects the current
     * promote pipeline (zero behaviour change for skills without active
     * canaries).
     */
    @Test
    @DisplayName("allocateSkillVersion returns baseline when no active canary exists for agent")
    void allocateSkillVersion_returnsBaseline_whenNoActiveCanary() {
        // GIVEN
        //   - sessionId = "sess-no-canary-001"
        //   - agentId   = 42L
        //   - baselineSkillId = 100L
        //   - canaryRepository.findActiveCanary(42L, "skill") -> Optional.empty()
        //
        // WHEN
        //   Long result = canaryAllocator.allocateSkillVersion(
        //       "sess-no-canary-001", 42L, 100L);
        //
        // THEN
        //   - result == 100L (baseline returned verbatim)
        //   - sessionAnnotationRepository.upsertSkipDuplicate NEVER called
        //     (no canary_group annotation written when no canary is active)
        //   - canaryRepository.findActiveCanary(42L, "skill") called exactly once
        //
        // TODO Phase 1.2: implement once CanaryAllocator + CanaryRolloutRepository exist.
    }

    /**
     * Spec: tech-design.md §5 steps 2-4 — when an active canary exists with
     * 0 &lt; percentage &lt; 100 AND the session has not been previously
     * grouped, the allocator must hash the sessionId, compare against the
     * rollout percentage, and persist the resulting group assignment via
     * {@code t_session_annotation (annotation_type='canary_group', source='system')}
     * so subsequent calls for the same session return the same version
     * ("session 锁版本" ratify decision).
     *
     * <p>This test pins the bucket calculation: it uses a sessionId whose
     * {@code hashCode() & 0x7FFFFFFF) % 100 < pct} so the bucket falls inside
     * the canary slice and the candidate version is returned.
     */
    @Test
    @DisplayName("allocateSkillVersion returns candidate and writes canary_group annotation when session bucket < pct")
    void allocateSkillVersion_returnsCandidate_whenInCanaryBucket() {
        // GIVEN
        //   - sessionId chosen so (sessionId.hashCode() & 0x7FFFFFFF) % 100 < 10
        //     (e.g. iterate small ints until found; pin the value in Phase 1.2)
        //   - agentId   = 42L
        //   - baselineSkillId  = 100L
        //   - candidateVersionId = 101L
        //   - canaryRepository.findActiveCanary(42L, "skill") ->
        //       Optional.of(rollout(stage='canary', pct=10,
        //                           baselineVersionId=100L, candidateVersionId=101L))
        //   - sessionAnnotationRepository.findCanaryGroup(sessionId, "skill") ->
        //       Optional.empty()  (new session, no prior group)
        //
        // WHEN
        //   Long result = canaryAllocator.allocateSkillVersion(
        //       sessionId, 42L, 100L);
        //
        // THEN
        //   - result == 101L (candidate version picked for in-bucket session)
        //   - sessionAnnotationRepository.upsertSkipDuplicate called exactly once
        //     with (sessionId, "canary_group", "skill:101", "system",
        //           BigDecimal.ONE, null)
        //   - subsequent allocateSkillVersion(sessionId, 42L, 100L) call would
        //     read the persisted group and return 101L without re-hashing
        //     (covered by a separate Phase 1.2 idempotence test).
        //
        // TODO Phase 1.2: implement once CanaryAllocator +
        // SessionAnnotationRepository.findCanaryGroup query method exist.
    }
}
