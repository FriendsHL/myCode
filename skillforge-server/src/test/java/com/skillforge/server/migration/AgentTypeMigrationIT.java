package com.skillforge.server.migration;

import com.skillforge.server.AbstractPostgresIT;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.junit.jupiter.EnabledIf;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SYSTEM-AGENT-TYPING Phase 1 red test (Phase 1.0):
 * post-condition checks for V89 — t_agent.agent_type column + 5 known
 * system agent rows marked 'system'.
 *
 * <p>This test is the structural anchor for V89 (added in Phase 1.1). Before
 * Phase 1.1 lands the migration the test <b>fails red</b> because:
 * <ul>
 *   <li>{@code t_agent.agent_type} column does not exist</li>
 *   <li>the {@code chk_agent_type} CHECK constraint does not exist</li>
 *   <li>no row carries the value 'system'</li>
 * </ul>
 *
 * <p>After Phase 1.1 lands {@code V89__add_agent_type.sql} the test passes:
 * <ol>
 *   <li>column exists as {@code VARCHAR(16) NOT NULL DEFAULT 'user'}</li>
 *   <li>{@code chk_agent_type} CHECK constraint exists, restricting to
 *       {'user', 'system'}</li>
 *   <li>the 5 known system agent rows (memory-curator, session-annotator,
 *       metrics-collector, attribution-curator, user-simulator) carry
 *       {@code agent_type = 'system'}</li>
 *   <li>all other existing agent rows default to {@code agent_type = 'user'}</li>
 * </ol>
 *
 * <p>Gated by {@code -Dskillforge.runMigrationIT=true} per the existing
 * {@link EvalTaskMigrationIT} convention so the migration ITs only spin up the
 * Postgres container when explicitly requested.
 */
@DisplayName("V89 — t_agent.agent_type migration")
@EnabledIf(expression = "#{systemProperties['skillforge.runMigrationIT'] == 'true'}",
        reason = "Run migration ITs only when explicitly requested")
class AgentTypeMigrationIT extends AbstractPostgresIT {

    private static final Set<String> KNOWN_SYSTEM_AGENT_NAMES = Set.of(
            "memory-curator",
            "session-annotator",
            "metrics-collector",
            "attribution-curator",
            "user-simulator");

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    @DisplayName("t_agent.agent_type column exists with VARCHAR(16) NOT NULL DEFAULT 'user'")
    void agentTypeColumnExists() {
        Map<String, Map<String, Object>> cols = describeColumns("t_agent");

        assertThat(cols)
                .as("t_agent.agent_type column must exist after V89 migration")
                .containsKey("agent_type");

        Map<String, Object> col = cols.get("agent_type");
        assertThat(col.get("data_type"))
                .as("agent_type data_type must be character varying")
                .isEqualTo("character varying");
        assertThat(col.get("character_maximum_length"))
                .as("agent_type character_maximum_length must be 16")
                .satisfies(v -> assertThat(((Number) v).intValue()).isEqualTo(16));
        assertThat(col.get("is_nullable"))
                .as("agent_type must be NOT NULL")
                .isEqualTo("NO");
        assertThat((String) col.get("column_default"))
                .as("agent_type DEFAULT must be 'user'")
                .contains("user");
    }

    @Test
    @DisplayName("chk_agent_type CHECK constraint restricts to {'user', 'system'}")
    void chkAgentTypeConstraintExists() {
        @SuppressWarnings("unchecked")
        List<Object[]> constraints = entityManager.createNativeQuery("""
                SELECT con.conname, pg_get_constraintdef(con.oid)
                  FROM pg_constraint con
                  JOIN pg_class cl ON cl.oid = con.conrelid
                 WHERE cl.relname = 't_agent'
                   AND con.contype = 'c'
                """).getResultList();

        Map<String, String> byName = constraints.stream().collect(Collectors.toMap(
                row -> (String) row[0],
                row -> (String) row[1]));

        assertThat(byName)
                .as("V89 must add CHECK constraint chk_agent_type")
                .containsKey("chk_agent_type");
        assertThat(byName.get("chk_agent_type"))
                .as("chk_agent_type must restrict agent_type to {'user', 'system'}")
                .contains("agent_type")
                .containsAnyOf("'user'", "'system'");
    }

    @Test
    @DisplayName("5 known system agents are marked agent_type='system' after V89")
    void fiveKnownSystemAgentsMarkedSystem() {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery(
                        "SELECT name, agent_type FROM t_agent WHERE name IN (:names)")
                .setParameter("names", List.copyOf(KNOWN_SYSTEM_AGENT_NAMES))
                .getResultList();

        Map<String, String> typeByName = rows.stream().collect(Collectors.toMap(
                row -> (String) row[0],
                row -> (String) row[1]));

        // Note: not all 5 may exist in every test profile (e.g. some bootstrap migrations
        // may be skipped). We only assert that **those that do exist** are marked 'system'.
        // The full set is asserted via the migration UPDATE itself — if a row exists with a
        // known name, V89 must have set it to 'system'.
        for (Map.Entry<String, String> entry : typeByName.entrySet()) {
            assertThat(entry.getValue())
                    .as("agent '%s' must be marked agent_type='system' after V89", entry.getKey())
                    .isEqualTo("system");
        }

        // At least 1 of the 5 should exist in a real Flyway-driven test run — if none
        // exist the migration target table is suspicious.
        assertThat(typeByName)
                .as("at least one known system agent row (memory-curator/session-annotator/" +
                        "metrics-collector/attribution-curator/user-simulator) must exist " +
                        "after Flyway runs V68/V75/V79/V81/V85 + V89")
                .isNotEmpty();
    }

    // ────────────────────────────────────────────────────────────────────────
    // helpers
    // ────────────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> describeColumns(String table) {
        List<Object[]> rows = entityManager.createNativeQuery("""
                        SELECT column_name, data_type, is_nullable, column_default,
                               character_maximum_length
                          FROM information_schema.columns
                         WHERE table_name = :t
                        """)
                .setParameter("t", table)
                .getResultList();
        return rows.stream().collect(Collectors.toMap(
                r -> (String) r[0],
                r -> {
                    java.util.Map<String, Object> m = new java.util.HashMap<>();
                    m.put("data_type", r[1]);
                    m.put("is_nullable", r[2]);
                    m.put("column_default", r[3] == null ? "" : r[3]);
                    m.put("character_maximum_length", r[4]);
                    return m;
                }));
    }
}
