package com.skillforge.server.flywheel.run;

import com.skillforge.server.AbstractPostgresIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OPT-LOOP-FRAMEWORK Sprint 4 — Repository-layer IT for the new
 * {@link FlywheelRunRepository#findAllWithFilters} query. Confirms each filter
 * dimension (loopKind / agentId / status) is honoured + that an all-null
 * filter returns every row + that the ORDER BY contract holds.
 *
 * <p>Sits on the same Testcontainers Postgres harness as
 * {@link FlywheelRunMigrationIT}; cleans the table per-test to keep
 * assertions independent.
 */
@DisplayName("FlywheelRunRepository — findAllWithFilters")
class FlywheelRunRepositoryFilterIT extends AbstractPostgresIT {

    @Autowired
    private FlywheelRunRepository runRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM t_flywheel_run_step");
        jdbcTemplate.update("DELETE FROM t_flywheel_run");
    }

    @Test
    @DisplayName("Case 1: all-null filters returns every row, newest first")
    void case1_allNullFilters_returnsAll() {
        // seed in non-chronological order so the ORDER BY contract is testable
        String oldId = insertRun(7L, "opt_report", "completed", Instant.parse("2026-05-20T00:00:00Z"));
        String midId = insertRun(8L, "memory_curation", "running", Instant.parse("2026-05-22T00:00:00Z"));
        String newId = insertRun(9L, "attribution", "error", Instant.parse("2026-05-26T00:00:00Z"));

        Page<FlywheelRunEntity> page = runRepository.findAllWithFilters(
                null, null, null, PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(3L);
        assertThat(page.getContent()).extracting(FlywheelRunEntity::getId)
                .containsExactly(newId, midId, oldId);
    }

    @Test
    @DisplayName("Case 2: filter by loopKind narrows to matching rows only")
    void case2_filterByLoopKind() {
        insertRun(7L, "opt_report", "completed", Instant.parse("2026-05-20T00:00:00Z"));
        insertRun(8L, "memory_curation", "running", Instant.parse("2026-05-22T00:00:00Z"));
        String anotherReport = insertRun(9L, "opt_report", "running", Instant.parse("2026-05-24T00:00:00Z"));

        Page<FlywheelRunEntity> page = runRepository.findAllWithFilters(
                "opt_report", null, null, PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(2L);
        assertThat(page.getContent()).extracting(FlywheelRunEntity::getLoopKind)
                .allMatch(k -> k.equals("opt_report"));
        // newest first
        assertThat(page.getContent().get(0).getId()).isEqualTo(anotherReport);
    }

    @Test
    @DisplayName("Case 3: filter by agentId narrows to matching rows only")
    void case3_filterByAgentId() {
        insertRun(7L, "opt_report", "completed", Instant.parse("2026-05-20T00:00:00Z"));
        insertRun(7L, "memory_curation", "running", Instant.parse("2026-05-22T00:00:00Z"));
        insertRun(8L, "opt_report", "completed", Instant.parse("2026-05-26T00:00:00Z"));

        Page<FlywheelRunEntity> page = runRepository.findAllWithFilters(
                null, 7L, null, PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(2L);
        assertThat(page.getContent()).extracting(FlywheelRunEntity::getAgentId)
                .allMatch(a -> a.equals(7L));
    }

    @Test
    @DisplayName("Case 4: filter by status + combined filters honours every dimension")
    void case4_combinedFilters() {
        insertRun(7L, "opt_report", "completed", Instant.parse("2026-05-20T00:00:00Z"));
        insertRun(7L, "opt_report", "error", Instant.parse("2026-05-22T00:00:00Z"));
        insertRun(7L, "memory_curation", "completed", Instant.parse("2026-05-26T00:00:00Z"));
        insertRun(8L, "opt_report", "completed", Instant.parse("2026-05-28T00:00:00Z"));

        // status=completed only → 3 rows
        Page<FlywheelRunEntity> byStatus = runRepository.findAllWithFilters(
                null, null, "completed", PageRequest.of(0, 10));
        assertThat(byStatus.getTotalElements()).isEqualTo(3L);

        // status=completed + agentId=7 → 2 rows
        Page<FlywheelRunEntity> byStatusAgent = runRepository.findAllWithFilters(
                null, 7L, "completed", PageRequest.of(0, 10));
        assertThat(byStatusAgent.getTotalElements()).isEqualTo(2L);

        // status=completed + agentId=7 + loopKind=opt_report → 1 row
        Page<FlywheelRunEntity> all3 = runRepository.findAllWithFilters(
                "opt_report", 7L, "completed", PageRequest.of(0, 10));
        assertThat(all3.getTotalElements()).isEqualTo(1L);
        assertThat(all3.getContent().get(0).getStatus()).isEqualTo("completed");
        assertThat(all3.getContent().get(0).getAgentId()).isEqualTo(7L);
        assertThat(all3.getContent().get(0).getLoopKind()).isEqualTo("opt_report");
    }

    /**
     * Insert via raw JDBC so we can pin {@code created_at} (the JPA save path
     * uses {@code @CreatedDate} = now). Lets us assert the ORDER BY contract
     * deterministically.
     */
    private String insertRun(long agentId, String loopKind, String status, Instant createdAt) {
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update(
                "INSERT INTO t_flywheel_run (id, agent_id, window_start, window_end, status, loop_kind, trigger_source, input_json, created_at, updated_at) " +
                        "VALUES (?, ?, NOW() - INTERVAL '7 days', NOW(), ?, ?, 'user_manual', '{}'::jsonb, ?, ?)",
                id, agentId, status, loopKind, createdAt, createdAt);
        return id;
    }
}
