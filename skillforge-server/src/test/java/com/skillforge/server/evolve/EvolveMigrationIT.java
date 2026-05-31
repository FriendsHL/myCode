package com.skillforge.server.evolve;

import com.skillforge.server.AbstractPostgresIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AUTOEVOLVE-AGENT-FLYWHEEL Module C — Flyway integration tests for the V130
 * ({@code add_evolve_loop_kind}) + V131 ({@code seed_evolve_orchestrator_agent})
 * migrations, run end-to-end against the Testcontainers Postgres on top of every
 * prior baseline.
 *
 * <p>Confirms: (1) loop_kind='evolve' is now accepted by the V130 CHECK while a
 * bogus value is still rejected; (2) step_kind='evolve_iteration' inserts fine
 * (step_kind has no CHECK by design — V124); (3) the evolve-orchestrator agent
 * is seeded with the 6 Module A/B/C tool_ids and the mimo model.
 */
@DisplayName("Evolve migrations (V130 loop_kind + V131 orchestrator seed)")
class EvolveMigrationIT extends AbstractPostgresIT {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("V130: loop_kind='evolve' accepted by chk_flywheel_run_loop_kind")
    void v130_evolveLoopKind_accepted() {
        String id = UUID.randomUUID().toString();
        assertThatCode(() -> jdbcTemplate.update(
                "INSERT INTO t_flywheel_run (id, agent_id, window_start, window_end, status, loop_kind, trigger_source, input_json, created_at, updated_at) " +
                        "VALUES (?, ?, NOW() - INTERVAL '7 days', NOW(), 'pending', 'evolve', 'api', '{}'::jsonb, NOW(), NOW())",
                id, 7L))
                .doesNotThrowAnyException();

        String loopKind = jdbcTemplate.queryForObject(
                "SELECT loop_kind FROM t_flywheel_run WHERE id = ?", String.class, id);
        assertThat(loopKind).isEqualTo("evolve");

        // Cleanup so we don't pollute other ITs sharing the container.
        jdbcTemplate.update("DELETE FROM t_flywheel_run WHERE id = ?", id);
    }

    @Test
    @DisplayName("V130: a bogus loop_kind is still rejected (constraint carried forward)")
    void v130_bogusLoopKind_rejected() {
        String id = UUID.randomUUID().toString();
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO t_flywheel_run (id, agent_id, window_start, window_end, status, loop_kind, trigger_source, input_json, created_at, updated_at) " +
                        "VALUES (?, ?, NOW() - INTERVAL '7 days', NOW(), 'pending', 'singularity', 'api', '{}'::jsonb, NOW(), NOW())",
                id, 7L))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("step_kind='evolve_iteration' inserts on an evolve run (no CHECK on step_kind)")
    void evolveIterationStep_accepted() {
        String runId = UUID.randomUUID().toString();
        jdbcTemplate.update(
                "INSERT INTO t_flywheel_run (id, agent_id, window_start, window_end, status, loop_kind, trigger_source, input_json, created_at, updated_at) " +
                        "VALUES (?, ?, NOW() - INTERVAL '7 days', NOW(), 'running', 'evolve', 'api', '{}'::jsonb, NOW(), NOW())",
                runId, 7L);
        String stepId = UUID.randomUUID().toString();
        assertThatCode(() -> jdbcTemplate.update(
                "INSERT INTO t_flywheel_run_step (id, run_id, step_input_json, status, step_kind, step_output_json, created_at, updated_at) " +
                        "VALUES (?, ?, '{}'::jsonb, 'completed', 'evolve_iteration', '{\"iteration\":1,\"kept\":true}'::jsonb, NOW(), NOW())",
                stepId, runId))
                .doesNotThrowAnyException();

        String stepKind = jdbcTemplate.queryForObject(
                "SELECT step_kind FROM t_flywheel_run_step WHERE id = ?", String.class, stepId);
        assertThat(stepKind).isEqualTo("evolve_iteration");

        jdbcTemplate.update("DELETE FROM t_flywheel_run_step WHERE id = ?", stepId);
        jdbcTemplate.update("DELETE FROM t_flywheel_run WHERE id = ?", runId);
    }

    @Test
    @DisplayName("V131: evolve-orchestrator agent seeded with the 6 tool_ids + mimo model")
    void v131_orchestratorAgent_seeded() {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT model_id, agent_type, status, tool_ids::text AS tool_ids, config::text AS config " +
                        "FROM t_agent WHERE name = 'evolve-orchestrator'");

        assertThat(row.get("model_id")).isEqualTo("xiaomi-mimo:mimo-v2.5-pro");
        assertThat(row.get("agent_type")).isEqualTo("system");
        assertThat(row.get("status")).isEqualTo("active");

        String toolIds = (String) row.get("tool_ids");
        assertThat(toolIds)
                .contains("RunWorkflow")
                .contains("GenerateCandidate")
                .contains("TriggerAbEval")
                .contains("GetAbResult")
                .contains("RecordIteration")
                .contains("PromoteCandidate");

        // config JSON also carries tool_ids (ChatService runtime allowlist read path).
        String config = (String) row.get("config");
        assertThat(config).contains("tool_ids").contains("RecordIteration");
    }
}
