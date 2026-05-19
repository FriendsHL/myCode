package com.skillforge.server.repository;

import com.skillforge.server.AbstractPostgresIT;
import com.skillforge.server.entity.SkillAbRunEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FLYWHEEL-VISUAL-STATUS Phase 2 (2026-05-20) — integration test for the new
 * {@link SkillAbRunRepository#findByFilters} paginated query.
 *
 * <p>Verifies the JPQL {@code (:param IS NULL OR …)} nullable-bind contract
 * with a real Postgres dialect — unit tests with mocked repository can't
 * catch JPQL semantic mistakes ({@code IS NULL OR =} type coercion edge
 * cases, ordering with nullable columns, etc.).
 */
@DisplayName("SkillAbRunRepository.findByFilters integration tests")
class SkillAbRunRepositoryFiltersIT extends AbstractPostgresIT {

    @Autowired
    private SkillAbRunRepository repository;

    @BeforeEach
    void cleanUp() {
        repository.deleteAll();
    }

    private SkillAbRunEntity row(String agentId, String status,
                                 Long parentSkillId, Long candidateSkillId) {
        SkillAbRunEntity r = new SkillAbRunEntity();
        r.setId(UUID.randomUUID().toString());
        r.setAgentId(agentId);
        r.setStatus(status);
        r.setParentSkillId(parentSkillId);
        r.setCandidateSkillId(candidateSkillId);
        return r;
    }

    @Test
    @DisplayName("findByFilters: null agentId + null status → returns all rows (cross-agent)")
    void findByFilters_allNull_returnsAll() {
        repository.save(row("ag-a", "RUNNING", 1L, 2L));
        repository.save(row("ag-b", "COMPLETED", 1L, 3L));
        repository.save(row("ag-c", "FAILED", 4L, 5L));

        Page<SkillAbRunEntity> page = repository.findByFilters(null, null, PageRequest.of(0, 20));

        assertThat(page.getTotalElements()).isEqualTo(3L);
        assertThat(page.getContent()).hasSize(3);
    }

    @Test
    @DisplayName("findByFilters: agentId filter exact-match; rows with different agent excluded")
    void findByFilters_agentIdFilter_excludesOthers() {
        repository.save(row("ag-a", "RUNNING", 1L, 2L));
        repository.save(row("ag-b", "RUNNING", 1L, 3L));
        repository.save(row("ag-a", "COMPLETED", 4L, 5L));

        Page<SkillAbRunEntity> page = repository.findByFilters("ag-a", null, PageRequest.of(0, 20));

        assertThat(page.getTotalElements()).isEqualTo(2L);
        assertThat(page.getContent())
                .allMatch(r -> "ag-a".equals(r.getAgentId()));
    }

    @Test
    @DisplayName("findByFilters: status filter + agentId combined; row must match both")
    void findByFilters_combinedFilters_intersection() {
        repository.save(row("ag-a", "RUNNING", 1L, 2L));
        repository.save(row("ag-a", "COMPLETED", 3L, 4L));
        repository.save(row("ag-b", "RUNNING", 5L, 6L));

        Page<SkillAbRunEntity> page = repository.findByFilters(
                "ag-a", "RUNNING", PageRequest.of(0, 20));

        assertThat(page.getTotalElements()).isEqualTo(1L);
        SkillAbRunEntity only = page.getContent().get(0);
        assertThat(only.getAgentId()).isEqualTo("ag-a");
        assertThat(only.getStatus()).isEqualTo("RUNNING");
    }

    @Test
    @DisplayName("findByFilters: pagination — page=0 size=2 returns first 2, totalElements reflects all")
    void findByFilters_pagination_respectsPageRequest() {
        for (int i = 0; i < 5; i++) {
            repository.save(row("ag-a", "RUNNING", (long) i, (long) (i + 100)));
        }

        Page<SkillAbRunEntity> first = repository.findByFilters(
                "ag-a", null, PageRequest.of(0, 2));
        Page<SkillAbRunEntity> second = repository.findByFilters(
                "ag-a", null, PageRequest.of(1, 2));

        assertThat(first.getTotalElements()).isEqualTo(5L);
        assertThat(first.getContent()).hasSize(2);
        assertThat(first.getTotalPages()).isEqualTo(3);
        assertThat(second.getContent()).hasSize(2);
        // Different page → different ids returned.
        assertThat(first.getContent().get(0).getId())
                .isNotEqualTo(second.getContent().get(0).getId());
    }

    @Test
    @DisplayName("findByFilters: empty result when nothing matches; returns empty page (not null)")
    void findByFilters_noMatch_emptyPage() {
        repository.save(row("ag-a", "RUNNING", 1L, 2L));

        Page<SkillAbRunEntity> page = repository.findByFilters(
                "ag-NOT-PRESENT", null, PageRequest.of(0, 20));

        assertThat(page).isNotNull();
        assertThat(page.getTotalElements()).isEqualTo(0L);
        assertThat(page.getContent()).isEmpty();
    }
}
