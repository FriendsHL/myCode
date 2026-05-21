package com.skillforge.server.sessionannotation;

import com.skillforge.server.entity.PatternSessionMemberEntity;
import com.skillforge.server.entity.SessionAnnotationEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.entity.SessionPatternEntity;
import com.skillforge.server.repository.PatternSessionMemberRepository;
import com.skillforge.server.repository.SessionAnnotationRepository;
import com.skillforge.server.repository.SessionPatternRepository;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.sessionannotation.SessionPatternClusterService.RecomputeResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MULTI-DIM-ATTRIBUTION 2026-05-21: regression coverage for the
 * {@link SessionPatternClusterService} per-outcome admission threshold
 * ({@code MIN_MEMBERS_INFRA_OUTCOME=2} for infra failures, 3 for everything
 * else).
 *
 * <p>Also pins that:
 * <ul>
 *   <li>{@code buildTuple} accepts {@code infrastructure_failure} +
 *       {@code cost_high} as valid outcomes (the {@code OUTCOME_SUCCESS}
 *       exclusion rule must NOT inadvertently treat the new values like
 *       success)</li>
 *   <li>2-session infra bucket → pattern upserted</li>
 *   <li>2-session non-infra (regular failure) bucket → still skipped (no
 *       regression)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SessionPatternClusterService — multi-dim admission")
class SessionPatternClusterServiceMultiDimAdmissionTest {

    @Mock(strictness = Mock.Strictness.LENIENT)
    private SessionAnnotationRepository sessionAnnotationRepository;
    @Mock(strictness = Mock.Strictness.LENIENT)
    private SessionPatternRepository sessionPatternRepository;
    @Mock(strictness = Mock.Strictness.LENIENT)
    private PatternSessionMemberRepository patternSessionMemberRepository;
    @Mock(strictness = Mock.Strictness.LENIENT)
    private SessionRepository sessionRepository;

    private SessionPatternClusterService service;

    @BeforeEach
    void setUp() {
        service = new SessionPatternClusterService(
                sessionAnnotationRepository,
                sessionPatternRepository,
                patternSessionMemberRepository,
                sessionRepository);
    }

    @Test
    @DisplayName("buildTuple accepts outcome=infrastructure_failure (not auto-excluded as success)")
    void buildTuple_acceptsInfrastructureFailureOutcome() {
        Instant t = Instant.parse("2026-05-21T10:00:00Z");
        // 2 sessions sharing the infra-failure tuple — below standard MIN=3 but
        // ≥ MIN_MEMBERS_INFRA_OUTCOME=2 → should produce one pattern.
        when(sessionAnnotationRepository.findDistinctSessionIdsCreatedSince(any(Instant.class)))
                .thenReturn(List.of("s1", "s2"));
        primeAnnotations("s1", t, "infrastructure_failure", "other", null, 0.9);
        primeAnnotations("s2", t, "infrastructure_failure", "other", null, 0.9);
        primeSessions(Map.of("s1", 7L, "s2", 7L));
        when(sessionPatternRepository.findBySignature(anyString())).thenReturn(java.util.Optional.empty());
        AtomicLong idGen = new AtomicLong(600L);
        when(sessionPatternRepository.save(any(SessionPatternEntity.class))).thenAnswer(inv -> {
            SessionPatternEntity p = inv.getArgument(0);
            if (p.getId() == null) p.setId(idGen.getAndIncrement());
            return p;
        });

        RecomputeResult res = service.recompute(Duration.ofDays(7));

        assertThat(res.patternsUpserted()).isEqualTo(1);
        assertThat(res.membersAdded()).isEqualTo(2);

        ArgumentCaptor<SessionPatternEntity> cap = ArgumentCaptor.forClass(SessionPatternEntity.class);
        verify(sessionPatternRepository, times(2)).save(cap.capture());
        SessionPatternEntity created = cap.getAllValues().get(0);
        assertThat(created.getOutcome()).isEqualTo("infrastructure_failure");
        assertThat(created.getSuspectSurface()).isEqualTo("other");
    }

    @Test
    @DisplayName("buildTuple accepts outcome=cost_high (not auto-excluded as success)")
    void buildTuple_acceptsCostHighOutcome() {
        Instant t = Instant.parse("2026-05-21T10:00:00Z");
        // 3 cost_high sessions — meets the standard threshold (cost_high uses
        // MIN_MEMBERS_PER_PATTERN=3, NOT the relaxed infra threshold).
        when(sessionAnnotationRepository.findDistinctSessionIdsCreatedSince(any(Instant.class)))
                .thenReturn(List.of("s1", "s2", "s3"));
        primeAnnotations("s1", t, "cost_high", "skill", "GetTrace", 0.7);
        primeAnnotations("s2", t, "cost_high", "skill", "GetTrace", 0.7);
        primeAnnotations("s3", t, "cost_high", "skill", "GetTrace", 0.7);
        primeSessions(Map.of("s1", 7L, "s2", 7L, "s3", 7L));
        when(sessionPatternRepository.findBySignature(anyString())).thenReturn(java.util.Optional.empty());
        AtomicLong idGen = new AtomicLong(700L);
        when(sessionPatternRepository.save(any(SessionPatternEntity.class))).thenAnswer(inv -> {
            SessionPatternEntity p = inv.getArgument(0);
            if (p.getId() == null) p.setId(idGen.getAndIncrement());
            return p;
        });

        RecomputeResult res = service.recompute(Duration.ofDays(7));

        assertThat(res.patternsUpserted()).isEqualTo(1);
        assertThat(res.membersAdded()).isEqualTo(3);

        ArgumentCaptor<SessionPatternEntity> cap = ArgumentCaptor.forClass(SessionPatternEntity.class);
        verify(sessionPatternRepository, times(2)).save(cap.capture());
        assertThat(cap.getAllValues().get(0).getOutcome()).isEqualTo("cost_high");
    }

    @Test
    @DisplayName("2-session infra bucket passes admission (MIN_MEMBERS_INFRA_OUTCOME=2)")
    void twoSessionInfraBucket_passesAdmission() {
        Instant t = Instant.parse("2026-05-21T10:00:00Z");
        when(sessionAnnotationRepository.findDistinctSessionIdsCreatedSince(any(Instant.class)))
                .thenReturn(List.of("s1", "s2"));
        primeAnnotations("s1", t, "infrastructure_failure", "other", null, 0.9);
        primeAnnotations("s2", t, "infrastructure_failure", "other", null, 0.9);
        primeSessions(Map.of("s1", 9L, "s2", 9L));
        when(sessionPatternRepository.findBySignature(anyString())).thenReturn(java.util.Optional.empty());
        AtomicLong idGen = new AtomicLong(800L);
        when(sessionPatternRepository.save(any(SessionPatternEntity.class))).thenAnswer(inv -> {
            SessionPatternEntity p = inv.getArgument(0);
            if (p.getId() == null) p.setId(idGen.getAndIncrement());
            return p;
        });

        RecomputeResult res = service.recompute(Duration.ofDays(7));

        assertThat(res.patternsUpserted()).isEqualTo(1);
        verify(patternSessionMemberRepository, times(2))
                .saveAndFlush(any(PatternSessionMemberEntity.class));
    }

    @Test
    @DisplayName("regression: 2-session regular failure bucket still SKIPPED (admission stays 3 for non-infra)")
    void twoSessionRegularFailureBucket_stillSkipped() {
        Instant t = Instant.parse("2026-05-21T10:00:00Z");
        when(sessionAnnotationRepository.findDistinctSessionIdsCreatedSince(any(Instant.class)))
                .thenReturn(List.of("s1", "s2"));
        primeAnnotations("s1", t, "failure", "skill", "BashTool", 0.9);
        primeAnnotations("s2", t, "failure", "skill", "BashTool", 0.9);
        primeSessions(Map.of("s1", 7L, "s2", 7L));

        RecomputeResult res = service.recompute(Duration.ofDays(7));

        // Critical: relaxing the threshold for infra MUST NOT relax it for
        // regular failures — the standard 3-member admission still applies.
        assertThat(res.patternsUpserted()).isZero();
        assertThat(res.membersAdded()).isZero();
        verify(sessionPatternRepository, never()).save(any(SessionPatternEntity.class));
    }

    // ---------- helpers (mirror SessionPatternClusterServiceTest) ----------

    private void primeAnnotations(String sessionId,
                                  Instant at,
                                  String outcome,
                                  String surface,
                                  String topFailingTool,
                                  double confidence) {
        List<SessionAnnotationEntity> rows = new ArrayList<>();
        rows.add(makeRow(sessionId, "outcome", outcome, confidence, at));
        rows.add(makeRow(sessionId, "suspect_surface", surface, confidence, at));
        if (topFailingTool != null) {
            rows.add(makeRow(sessionId, "top_failing_tool", topFailingTool, confidence, at));
        }
        when(sessionAnnotationRepository.findBySessionId(sessionId)).thenReturn(rows);
    }

    private static SessionAnnotationEntity makeRow(String sessionId,
                                                   String type,
                                                   String value,
                                                   double confidence,
                                                   Instant at) {
        SessionAnnotationEntity r = new SessionAnnotationEntity();
        r.setSessionId(sessionId);
        r.setAnnotationType(type);
        r.setAnnotationValue(value);
        r.setSource("llm");
        r.setConfidence(new BigDecimal(Double.toString(confidence)));
        r.setReasoning("test");
        r.setCreatedAt(at);
        return r;
    }

    private void primeSessions(Map<String, Long> agentIdBySession) {
        List<SessionEntity> entities = new ArrayList<>();
        for (Map.Entry<String, Long> e : agentIdBySession.entrySet()) {
            SessionEntity s = new SessionEntity();
            s.setId(e.getKey());
            s.setAgentId(e.getValue());
            entities.add(s);
        }
        when(sessionRepository.findAllById(anyIterable())).thenReturn(entities);
    }
}
