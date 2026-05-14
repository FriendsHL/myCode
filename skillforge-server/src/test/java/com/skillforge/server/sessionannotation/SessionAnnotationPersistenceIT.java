package com.skillforge.server.sessionannotation;

import com.skillforge.server.AbstractPostgresIT;
import com.skillforge.server.entity.PatternSessionMemberEntity;
import com.skillforge.server.entity.PatternSessionMemberId;
import com.skillforge.server.entity.SessionAnnotationEntity;
import com.skillforge.server.entity.SessionPatternEntity;
import com.skillforge.server.repository.PatternSessionMemberRepository;
import com.skillforge.server.repository.SessionAnnotationRepository;
import com.skillforge.server.repository.SessionPatternRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PROD-LABEL-CLUSTER V1 Phase 1.1: persistence integration tests for the three
 * new tables introduced by V74 + the V75 agent/scheduled-task seed.
 *
 * <p>Each test runs against the shared PostgreSQL container with Flyway migrations
 * applied (no schema mocking). Test isolation comes from {@code @DataJpaTest}
 * rolling back each test.
 *
 * <p>Covers (per Phase 1.1 task list):
 * <ol>
 *   <li>{@code t_session_annotation} round-trip ({@code save} → {@code findBySessionId}).</li>
 *   <li>{@code uq_session_annotation} UNIQUE constraint rejects duplicates.</li>
 *   <li>{@code t_session_pattern.findBySignature} works for both hits + misses.</li>
 *   <li>{@code t_pattern_session_member} composite-key round-trip + FK CASCADE
 *       delete from parent pattern.</li>
 * </ol>
 */
@DisplayName("PROD-LABEL-CLUSTER persistence IT")
class SessionAnnotationPersistenceIT extends AbstractPostgresIT {

    @Autowired
    private SessionAnnotationRepository annotationRepository;

    @Autowired
    private SessionPatternRepository patternRepository;

    @Autowired
    private PatternSessionMemberRepository memberRepository;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void cleanUp() {
        memberRepository.deleteAll();
        patternRepository.deleteAll();
        annotationRepository.deleteAll();
    }

    private SessionAnnotationEntity annotation(String sessionId,
                                               String type,
                                               String value,
                                               String source) {
        SessionAnnotationEntity a = new SessionAnnotationEntity();
        a.setSessionId(sessionId);
        a.setAnnotationType(type);
        a.setAnnotationValue(value);
        a.setSource(source);
        a.setConfidence(new BigDecimal("1.00"));
        a.setCreatedAt(Instant.now());
        return a;
    }

    private SessionPatternEntity pattern(String signature, String outcome, String surface) {
        SessionPatternEntity p = new SessionPatternEntity();
        p.setSignature(signature);
        p.setOutcome(outcome);
        p.setSuspectSurface(surface);
        p.setSuggestedSurface(surface);
        p.setMemberCount(0);
        Instant now = Instant.now();
        p.setFirstSeenAt(now);
        p.setLastSeenAt(now);
        p.setCreatedAt(now);
        p.setUpdatedAt(now);
        return p;
    }

    @Test
    @DisplayName("t_session_annotation save round-trips and findBySessionId returns saved rows")
    void sessionAnnotation_saveAndFindBySessionId_roundtrips() {
        SessionAnnotationEntity saved = annotationRepository.save(
                annotation("sess-1", "tool_failure", "true", SessionAnnotationEntity.SOURCE_SIGNAL));
        annotationRepository.save(
                annotation("sess-1", "outcome", "failure", SessionAnnotationEntity.SOURCE_LLM));
        annotationRepository.save(
                annotation("sess-OTHER", "tool_failure", "true", SessionAnnotationEntity.SOURCE_SIGNAL));

        assertThat(saved.getId()).isNotNull();

        List<SessionAnnotationEntity> rows = annotationRepository.findBySessionId("sess-1");
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(SessionAnnotationEntity::getSource)
                .containsExactlyInAnyOrder(
                        SessionAnnotationEntity.SOURCE_SIGNAL,
                        SessionAnnotationEntity.SOURCE_LLM);
        assertThat(rows).extracting(SessionAnnotationEntity::getCreatedAt)
                .allMatch(Instant.class::isInstance);
    }

    @Test
    @DisplayName("uq_session_annotation rejects duplicate (session,type,value,source) on flush")
    void sessionAnnotation_uniqueConstraint_rejectsDuplicate() {
        annotationRepository.save(
                annotation("sess-dup", "tool_failure", "true", SessionAnnotationEntity.SOURCE_SIGNAL));
        // First row is fine. Re-insert with identical (session, type, value, source)
        // must violate uq_session_annotation. Flushing forces the SQL constraint
        // check before the @DataJpaTest rollback.
        SessionAnnotationEntity duplicate = annotation(
                "sess-dup", "tool_failure", "true", SessionAnnotationEntity.SOURCE_SIGNAL);
        annotationRepository.save(duplicate);

        assertThatThrownBy(() -> entityManager.flush())
                .isInstanceOfAny(DataIntegrityViolationException.class,
                                 jakarta.persistence.PersistenceException.class);
    }

    @Test
    @DisplayName("t_session_pattern findBySignature returns the row when present, empty otherwise")
    void sessionPattern_findBySignature_works() {
        patternRepository.save(pattern("failure|skill||42", "failure", "skill"));

        Optional<SessionPatternEntity> hit = patternRepository.findBySignature("failure|skill||42");
        assertThat(hit).isPresent();
        assertThat(hit.get().getOutcome()).isEqualTo("failure");
        assertThat(hit.get().getSuspectSurface()).isEqualTo("skill");

        Optional<SessionPatternEntity> miss = patternRepository.findBySignature("does-not-exist|x|y|0");
        assertThat(miss).isEmpty();
    }

    @Test
    @DisplayName("t_pattern_session_member composite PK round-trips + FK CASCADE removes members on pattern delete")
    void patternSessionMember_compositeKeyAndCascadeDelete_work() {
        SessionPatternEntity p = patternRepository.save(pattern("partial_success|prompt||7", "partial_success", "prompt"));

        PatternSessionMemberEntity m1 = new PatternSessionMemberEntity();
        m1.setPatternId(p.getId());
        m1.setSessionId("sess-A");
        m1.setAddedAt(Instant.now());

        PatternSessionMemberEntity m2 = new PatternSessionMemberEntity();
        m2.setPatternId(p.getId());
        m2.setSessionId("sess-B");
        m2.setAddedAt(Instant.now());

        memberRepository.saveAll(List.of(m1, m2));

        // Composite-key lookup via findById.
        Optional<PatternSessionMemberEntity> lookup = memberRepository.findById(
                new PatternSessionMemberId(p.getId(), "sess-A"));
        assertThat(lookup).isPresent();
        assertThat(lookup.get().getSessionId()).isEqualTo("sess-A");

        assertThat(memberRepository.findByPatternId(p.getId())).hasSize(2);

        // FK ON DELETE CASCADE: deleting the pattern must purge member rows.
        // Use a native query so we don't rely on JPA cascade (the FK is the
        // contract being tested, not the JPA mapping).
        Long pid = p.getId();
        memberRepository.flush();
        patternRepository.flush();
        entityManager.createNativeQuery("DELETE FROM t_session_pattern WHERE id = :id")
                .setParameter("id", pid)
                .executeUpdate();
        entityManager.clear();

        assertThat(memberRepository.findByPatternId(pid)).isEmpty();
        assertThat(patternRepository.findById(pid)).isEmpty();
    }
}
