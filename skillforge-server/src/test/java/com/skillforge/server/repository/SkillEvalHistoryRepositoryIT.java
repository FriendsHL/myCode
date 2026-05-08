package com.skillforge.server.repository;

import com.skillforge.server.AbstractPostgresIT;
import com.skillforge.server.entity.SkillEvalHistoryEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test for {@link SkillEvalHistoryRepository}. Verifies the
 * three contract methods BE-2 / cron / dashboard depend on +
 * the {@code chk_seh_triggered_by} CHECK constraint.
 */
@DisplayName("SkillEvalHistoryRepository integration tests")
class SkillEvalHistoryRepositoryIT extends AbstractPostgresIT {

    @Autowired
    private SkillEvalHistoryRepository repository;

    @BeforeEach
    void cleanUp() {
        repository.deleteAll();
    }

    private SkillEvalHistoryEntity row(Long skillId, double composite, String triggeredBy) {
        SkillEvalHistoryEntity h = new SkillEvalHistoryEntity();
        h.setSkillId(skillId);
        h.setCompositeScore(composite);
        h.setQualityScore(composite + 1);
        h.setEfficiencyScore(composite + 2);
        h.setLatencyScore(composite + 3);
        h.setCostScore(composite + 4);
        h.setTriggeredBy(triggeredBy);
        // createdAt set via @PrePersist on save (W4 r1) — left null here so the
        // entity listener fires; tests that need an explicit aged createdAt call
        // setCreatedAt() after this helper.
        return h;
    }

    @Test
    @DisplayName("findFirstBySkillIdOrderByCreatedAtDesc returns the newest row for that skill")
    void findFirst_returnsNewest() {
        SkillEvalHistoryEntity older = row(1L, 50.0, "manual");
        older.setCreatedAt(Instant.now().minus(2, ChronoUnit.HOURS));
        repository.save(older);

        SkillEvalHistoryEntity newer = row(1L, 80.0, "scheduled");
        newer.setCreatedAt(Instant.now());
        repository.save(newer);

        // Different skill — must NOT pollute the result.
        repository.save(row(2L, 99.0, "manual"));

        Optional<SkillEvalHistoryEntity> latest = repository.findFirstBySkillIdOrderByCreatedAtDesc(1L);
        assertThat(latest).isPresent();
        assertThat(latest.get().getCompositeScore()).isEqualTo(80.0);
        assertThat(latest.get().getTriggeredBy()).isEqualTo("scheduled");
    }

    @Test
    @DisplayName("findFirstBySkillIdOrderByCreatedAtDesc returns empty when skill has no history")
    void findFirst_empty() {
        repository.save(row(2L, 50.0, "manual"));
        Optional<SkillEvalHistoryEntity> latest = repository.findFirstBySkillIdOrderByCreatedAtDesc(999L);
        assertThat(latest).isEmpty();
    }

    @Test
    @DisplayName("findBySkillIdOrderByCreatedAtDesc with limit returns recent N newest first")
    void findRecent_limited() {
        for (int i = 0; i < 5; i++) {
            SkillEvalHistoryEntity h = row(1L, 60.0 + i, "manual");
            h.setCreatedAt(Instant.now().minus(5 - i, ChronoUnit.HOURS));
            repository.save(h);
        }
        List<SkillEvalHistoryEntity> recent = repository
                .findBySkillIdOrderByCreatedAtDesc(1L, PageRequest.of(0, 3));
        assertThat(recent).hasSize(3);
        // Newest first → composite 64.0 then 63.0 then 62.0.
        assertThat(recent.get(0).getCompositeScore()).isEqualTo(64.0);
        assertThat(recent.get(2).getCompositeScore()).isEqualTo(62.0);
    }

    @Test
    @DisplayName("countBySkillIdAndCreatedAtAfter excludes rows older than the cutoff")
    void count_filtersByCutoff() {
        Instant now = Instant.now();
        SkillEvalHistoryEntity old = row(1L, 50.0, "manual");
        old.setCreatedAt(now.minus(10, ChronoUnit.DAYS));
        repository.save(old);

        SkillEvalHistoryEntity recent = row(1L, 70.0, "scheduled");
        recent.setCreatedAt(now.minus(2, ChronoUnit.DAYS));
        repository.save(recent);

        long countLast7d = repository.countBySkillIdAndCreatedAtAfter(
                1L, now.minus(7, ChronoUnit.DAYS));
        assertThat(countLast7d).isEqualTo(1L);

        long countLast30d = repository.countBySkillIdAndCreatedAtAfter(
                1L, now.minus(30, ChronoUnit.DAYS));
        assertThat(countLast30d).isEqualTo(2L);
    }

    @Test
    @DisplayName("CHECK constraint rejects invalid triggered_by")
    void checkConstraint_rejectsBadTriggeredBy() {
        SkillEvalHistoryEntity bad = row(1L, 50.0, "BOGUS");
        // The native CHECK fires on flush; flush triggers the SQL constraint error.
        assertThatThrownBy(() -> repository.saveAndFlush(bad))
                .isInstanceOf(Exception.class);
    }
}
