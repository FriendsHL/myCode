package com.skillforge.server.canary;

import com.skillforge.server.entity.CanaryRolloutEntity;
import com.skillforge.server.repository.CanaryRolloutRepository;
import com.skillforge.server.repository.SessionAnnotationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * SKILL-CANARY-ROLLOUT V2 Phase 1.2 — {@link CanaryAllocator} unit tests.
 *
 * <p>Phase 1.0 shipped two {@code @Disabled} red-test stubs in
 * {@code CanaryAllocatorRedTest}; Phase 1.2 turns them green here (the file
 * was renamed for the standard {@code Test} suffix). Coverage:
 *
 * <ol>
 *   <li>No active canary → baseline returned, no annotation written.</li>
 *   <li>pct=100 → candidate returned (no annotation needed — fully rolled out).</li>
 *   <li>pct=0 → baseline returned (rolled back / reset).</li>
 *   <li>Session previously pinned → prior assignment honoured, no re-hash.</li>
 *   <li>Fresh session, bucket in candidate slice → candidate + annotation persisted.</li>
 *   <li>Fresh session, bucket outside candidate slice → baseline + annotation persisted.</li>
 *   <li>Defensive: malformed prior annotation value falls through to fresh allocation.</li>
 *   <li>Defensive: persistence failure does NOT block the user turn.</li>
 *   <li>Defensive: null sessionId / agentId short-circuit to baseline.</li>
 * </ol>
 *
 * <p>Spec source: {@code docs/requirements/active/SKILL-CANARY-ROLLOUT/tech-design.md} §5.
 */
@ExtendWith(MockitoExtension.class)
class CanaryAllocatorTest {

    @Mock private CanaryRolloutRepository canaryRepository;
    @Mock private SessionAnnotationRepository sessionAnnotationRepository;

    private CanaryAllocator allocator;

    @BeforeEach
    void setUp() {
        allocator = new CanaryAllocator(canaryRepository, sessionAnnotationRepository);
    }

    private CanaryRolloutEntity activeCanary(int pct, String baseline, String candidate) {
        CanaryRolloutEntity c = new CanaryRolloutEntity();
        c.setId(7L);
        c.setSurfaceType(CanaryRolloutEntity.SURFACE_SKILL);
        c.setAgentId(42L);
        c.setBaselineSkillName(baseline);
        c.setCandidateSkillName(candidate);
        c.setRolloutStage(CanaryRolloutEntity.STAGE_CANARY);
        c.setRolloutPercentage(pct);
        Instant now = Instant.now();
        c.setStartedAt(now);
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        return c;
    }

    /**
     * Find a sessionId whose bucket sits in the lower {@code pct} slice so we
     * can pin the candidate-path test without depending on a magic string. We
     * search small integers as session ids — deterministic and fast.
     */
    private String sessionIdInBucket(int pct, boolean inSlice) {
        for (int i = 0; i < 10_000; i++) {
            String candidate = "sess-" + i;
            int bucket = (candidate.hashCode() & 0x7FFFFFFF) % 100;
            if (inSlice && bucket < pct) return candidate;
            if (!inSlice && bucket >= pct) return candidate;
        }
        throw new IllegalStateException(
                "Could not find sessionId for pct=" + pct + " inSlice=" + inSlice);
    }

    @Test
    @DisplayName("returns baseline when no active canary exists for agent")
    void allocate_returnsBaseline_whenNoActiveCanary() {
        when(canaryRepository.findActiveCanaryForSkill(42L, "my-skill"))
                .thenReturn(Optional.empty());

        String result = allocator.allocate("sess-001", 42L, "my-skill");

        assertThat(result).isEqualTo("my-skill");
        verify(canaryRepository, times(1)).findActiveCanaryForSkill(42L, "my-skill");
        verifyNoInteractions(sessionAnnotationRepository);
    }

    @Test
    @DisplayName("returns candidate when rolloutPercentage=100 (fully rolled out)")
    void allocate_returnsCandidate_whenPct100() {
        when(canaryRepository.findActiveCanaryForSkill(42L, "my-skill"))
                .thenReturn(Optional.of(activeCanary(100, "my-skill", "my-skill-v2")));

        String result = allocator.allocate("sess-001", 42L, "my-skill");

        assertThat(result).isEqualTo("my-skill-v2");
        // At 100% no per-session pinning is needed: the next call to the same
        // session would still hit the canary lookup, see pct=100, and return
        // candidate again — short-circuit before findCanaryGroup / upsert.
        verify(sessionAnnotationRepository, never())
                .upsertSkipDuplicate(anyString(), anyString(), anyString(), anyString(), any(), any());
        verify(sessionAnnotationRepository, never()).findCanaryGroup(anyString(), anyString());
    }

    @Test
    @DisplayName("returns baseline when rolloutPercentage=0 (rolled back / not started)")
    void allocate_returnsBaseline_whenPct0() {
        when(canaryRepository.findActiveCanaryForSkill(42L, "my-skill"))
                .thenReturn(Optional.of(activeCanary(0, "my-skill", "my-skill-v2")));

        String result = allocator.allocate("sess-001", 42L, "my-skill");

        assertThat(result).isEqualTo("my-skill");
        verifyNoInteractions(sessionAnnotationRepository);
    }

    @Test
    @DisplayName("honours prior canary_group annotation without re-hashing")
    void allocate_returnsPinnedAssignment_whenSessionAlreadyGrouped() {
        when(canaryRepository.findActiveCanaryForSkill(42L, "my-skill"))
                .thenReturn(Optional.of(activeCanary(50, "my-skill", "my-skill-v2")));
        when(sessionAnnotationRepository.findCanaryGroup("sess-pinned", "skill"))
                .thenReturn(Optional.of("skill:my-skill-v2"));

        String result = allocator.allocate("sess-pinned", 42L, "my-skill");

        assertThat(result).isEqualTo("my-skill-v2");
        // No new upsert — session was already pinned.
        verify(sessionAnnotationRepository, never())
                .upsertSkipDuplicate(anyString(), anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("returns candidate + persists annotation when fresh session bucket < pct")
    void allocate_returnsCandidateAndPersists_whenInCanaryBucket() {
        int pct = 50;
        String sessionId = sessionIdInBucket(pct, true);
        when(canaryRepository.findActiveCanaryForSkill(42L, "my-skill"))
                .thenReturn(Optional.of(activeCanary(pct, "my-skill", "my-skill-v2")));
        when(sessionAnnotationRepository.findCanaryGroup(sessionId, "skill"))
                .thenReturn(Optional.empty());

        String result = allocator.allocate(sessionId, 42L, "my-skill");

        assertThat(result).isEqualTo("my-skill-v2");

        ArgumentCaptor<String> typeCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> valueCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> sourceCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<BigDecimal> confCap = ArgumentCaptor.forClass(BigDecimal.class);
        verify(sessionAnnotationRepository, times(1))
                .upsertSkipDuplicate(eq(sessionId), typeCap.capture(), valueCap.capture(),
                        sourceCap.capture(), confCap.capture(), eq((String) null));
        assertThat(typeCap.getValue()).isEqualTo("canary_group");
        assertThat(valueCap.getValue()).isEqualTo("skill:my-skill-v2");
        assertThat(sourceCap.getValue()).isEqualTo("system");
        assertThat(confCap.getValue()).isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    @DisplayName("returns baseline + persists annotation when fresh session bucket >= pct")
    void allocate_returnsBaselineAndPersists_whenOutsideCanaryBucket() {
        int pct = 10;
        String sessionId = sessionIdInBucket(pct, false);
        when(canaryRepository.findActiveCanaryForSkill(42L, "my-skill"))
                .thenReturn(Optional.of(activeCanary(pct, "my-skill", "my-skill-v2")));
        when(sessionAnnotationRepository.findCanaryGroup(sessionId, "skill"))
                .thenReturn(Optional.empty());

        String result = allocator.allocate(sessionId, 42L, "my-skill");

        assertThat(result).isEqualTo("my-skill");
        verify(sessionAnnotationRepository, times(1))
                .upsertSkipDuplicate(eq(sessionId), eq("canary_group"), eq("skill:my-skill"),
                        eq("system"), eq(BigDecimal.ONE), eq((String) null));
    }

    @Test
    @DisplayName("falls through to fresh allocation when prior annotation value is malformed")
    void allocate_fallsThroughToHash_whenPriorAnnotationMalformed() {
        int pct = 50;
        String sessionId = sessionIdInBucket(pct, true);
        when(canaryRepository.findActiveCanaryForSkill(42L, "my-skill"))
                .thenReturn(Optional.of(activeCanary(pct, "my-skill", "my-skill-v2")));
        when(sessionAnnotationRepository.findCanaryGroup(sessionId, "skill"))
                .thenReturn(Optional.of("garbage-no-prefix"));

        String result = allocator.allocate(sessionId, 42L, "my-skill");

        // Hash bucket < pct → candidate; allocator should still try to persist
        // a clean annotation on top of the malformed one (ON CONFLICT DO NOTHING).
        assertThat(result).isEqualTo("my-skill-v2");
        verify(sessionAnnotationRepository, times(1))
                .upsertSkipDuplicate(eq(sessionId), eq("canary_group"), eq("skill:my-skill-v2"),
                        eq("system"), eq(BigDecimal.ONE), eq((String) null));
    }

    @Test
    @DisplayName("persistence failure does not block allocation — allocated name still returned")
    void allocate_returnsAllocatedName_evenWhenUpsertThrows() {
        int pct = 50;
        String sessionId = sessionIdInBucket(pct, true);
        when(canaryRepository.findActiveCanaryForSkill(42L, "my-skill"))
                .thenReturn(Optional.of(activeCanary(pct, "my-skill", "my-skill-v2")));
        when(sessionAnnotationRepository.findCanaryGroup(sessionId, "skill"))
                .thenReturn(Optional.empty());
        when(sessionAnnotationRepository.upsertSkipDuplicate(
                anyString(), anyString(), anyString(), anyString(), any(), any()))
                .thenThrow(new DataAccessResourceFailureException("PG hiccup"));

        String result = allocator.allocate(sessionId, 42L, "my-skill");

        assertThat(result).isEqualTo("my-skill-v2");
    }

    @Test
    @DisplayName("null sessionId short-circuits to baseline (legacy / test path)")
    void allocate_returnsBaseline_whenSessionIdNull() {
        String result = allocator.allocate(null, 42L, "my-skill");

        assertThat(result).isEqualTo("my-skill");
        verifyNoInteractions(canaryRepository);
        verifyNoInteractions(sessionAnnotationRepository);
    }

    @Test
    @DisplayName("null agentId short-circuits to baseline (legacy / test path)")
    void allocate_returnsBaseline_whenAgentIdNull() {
        String result = allocator.allocate("sess-001", null, "my-skill");

        assertThat(result).isEqualTo("my-skill");
        verifyNoInteractions(canaryRepository);
        verifyNoInteractions(sessionAnnotationRepository);
    }

    @Test
    @DisplayName("repository lookup failure falls back to baseline (fail-secure)")
    void allocate_returnsBaseline_whenRepositoryThrows() {
        when(canaryRepository.findActiveCanaryForSkill(42L, "my-skill"))
                .thenThrow(new DataAccessResourceFailureException("DB down"));

        String result = allocator.allocate("sess-001", 42L, "my-skill");

        assertThat(result).isEqualTo("my-skill");
        verifyNoInteractions(sessionAnnotationRepository);
    }

    @Test
    @DisplayName("findCanaryGroup failure falls back to baseline (fail-secure)")
    void allocate_returnsBaseline_whenFindCanaryGroupThrows() {
        int pct = 50;
        String sessionId = sessionIdInBucket(pct, true);
        when(canaryRepository.findActiveCanaryForSkill(42L, "my-skill"))
                .thenReturn(Optional.of(activeCanary(pct, "my-skill", "my-skill-v2")));
        when(sessionAnnotationRepository.findCanaryGroup(sessionId, "skill"))
                .thenThrow(new DataAccessResourceFailureException("DB down mid-flight"));

        String result = allocator.allocate(sessionId, 42L, "my-skill");

        assertThat(result).isEqualTo("my-skill");
        // upsert never attempted when we already bailed out.
        verify(sessionAnnotationRepository, never())
                .upsertSkipDuplicate(anyString(), anyString(), anyString(), anyString(), any(), any());
    }
}
