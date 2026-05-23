package com.skillforge.server.migration;

import com.skillforge.server.AbstractPostgresIT;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.junit.jupiter.EnabledIf;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EVAL-DATASET-LAYER V1 — post-condition checks for V109 / V110 / V111 / V112
 * migrations. Mirrors {@link V91MigrationIT} style + {@link AgentTypeMigrationIT}
 * convention (gated by {@code -Dskillforge.runMigrationIT=true} so the
 * migration ITs only spin up the Postgres container when explicitly requested).
 */
@DisplayName("EVAL-DATASET-LAYER V109/V110/V111/V112 migrations")
@EnabledIf(expression = "#{systemProperties['skillforge.runMigrationIT'] == 'true'}",
        reason = "Run migration ITs only when explicitly requested")
class EvalDatasetLayerMigrationIT extends AbstractPostgresIT {

    @PersistenceContext
    private EntityManager entityManager;

    // -----------------------------------------------------------------------
    // V109 — t_eval_scenario source_type + source_ref + purpose
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("V109 — t_eval_scenario new columns + CHECK constraints")
    class V109Schema {

        @Test
        @DisplayName("agent_id is now nullable")
        void agentIdIsNullable() {
            Map<String, Object> col = describeColumns("t_eval_scenario").get("agent_id");
            assertThat(col).isNotNull();
            assertThat(col.get("is_nullable")).isEqualTo("YES");
        }

        @Test
        @DisplayName("source_type column exists VARCHAR(32) NOT NULL")
        void sourceTypeColumnExists() {
            Map<String, Object> col = describeColumns("t_eval_scenario").get("source_type");
            assertThat(col).isNotNull();
            assertThat(col.get("data_type")).isEqualTo("character varying");
            assertThat(((Number) col.get("character_maximum_length")).intValue()).isEqualTo(32);
            assertThat(col.get("is_nullable")).isEqualTo("NO");
        }

        @Test
        @DisplayName("source_ref column exists VARCHAR(256) NULL")
        void sourceRefColumnExists() {
            Map<String, Object> col = describeColumns("t_eval_scenario").get("source_ref");
            assertThat(col).isNotNull();
            assertThat(col.get("data_type")).isEqualTo("character varying");
            assertThat(((Number) col.get("character_maximum_length")).intValue()).isEqualTo(256);
            assertThat(col.get("is_nullable")).isEqualTo("YES");
        }

        @Test
        @DisplayName("purpose column exists VARCHAR(32) NOT NULL")
        void purposeColumnExists() {
            Map<String, Object> col = describeColumns("t_eval_scenario").get("purpose");
            assertThat(col).isNotNull();
            assertThat(col.get("is_nullable")).isEqualTo("NO");
        }

        @Test
        @DisplayName("chk_eval_scenario_source_type CHECK constraint exists")
        void sourceTypeCheckConstraintExists() {
            List<String> constraints = entityManager.createNativeQuery(
                    "SELECT conname FROM pg_constraint WHERE conname = 'chk_eval_scenario_source_type'")
                    .getResultList();
            assertThat(constraints).contains("chk_eval_scenario_source_type");
        }

        @Test
        @DisplayName("chk_eval_scenario_purpose CHECK constraint exists")
        void purposeCheckConstraintExists() {
            List<String> constraints = entityManager.createNativeQuery(
                    "SELECT conname FROM pg_constraint WHERE conname = 'chk_eval_scenario_purpose'")
                    .getResultList();
            assertThat(constraints).contains("chk_eval_scenario_purpose");
        }

        @Test
        @DisplayName("indexes created on source_type / purpose / partial source_ref")
        void indexesPresent() {
            List<String> indexNames = entityManager.createNativeQuery(
                    "SELECT indexname FROM pg_indexes WHERE tablename = 't_eval_scenario'")
                    .getResultList();
            assertThat(indexNames).contains(
                    "idx_eval_scenario_source_type",
                    "idx_eval_scenario_purpose",
                    "idx_eval_scenario_source_ref");
        }

        @Test
        @DisplayName("CHECK constraint rejects invalid source_type via INSERT")
        @Transactional
        void invalidSourceTypeRejected() {
            try {
                entityManager.createNativeQuery("""
                        INSERT INTO t_eval_scenario (id, name, task, status, source_type, purpose, version)
                        VALUES (:id, 'bad', 'bad', 'draft', 'INVALID', 'baseline_anchor', 1)
                        """).setParameter("id", java.util.UUID.randomUUID().toString())
                        .executeUpdate();
                org.junit.jupiter.api.Assertions.fail("expected CHECK constraint violation");
            } catch (Exception expected) {
                // Postgres throws a CHECK constraint violation here — we don't
                // care about exact class, just that the insert is rejected.
                assertThat(expected.getMessage().toLowerCase())
                        .containsAnyOf("check", "constraint", "invalid", "chk_eval_scenario_source_type");
            }
        }
    }

    // -----------------------------------------------------------------------
    // V110 — t_eval_dataset / version / version_scenario tables
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("V110 — dataset tables + FK CASCADE/RESTRICT")
    class V110Schema {

        @Test
        @DisplayName("t_eval_dataset table exists with TIMESTAMPTZ created_at/updated_at")
        void datasetTableShape() {
            Map<String, Map<String, Object>> cols = describeColumns("t_eval_dataset");
            assertThat(cols).containsKeys("id", "name", "owner_id", "agent_id", "tags",
                    "is_public", "created_at", "updated_at");
            // ★ r4 B2 fix verify: TIMESTAMPTZ not TIMESTAMP.
            assertThat(cols.get("created_at").get("data_type"))
                    .as("created_at must be TIMESTAMPTZ (timestamp with time zone)")
                    .isEqualTo("timestamp with time zone");
            assertThat(cols.get("updated_at").get("data_type"))
                    .isEqualTo("timestamp with time zone");
        }

        @Test
        @DisplayName("t_eval_dataset_version exists with composition_hash + actual_baseline_pass_rate")
        void versionTableShape() {
            Map<String, Map<String, Object>> cols = describeColumns("t_eval_dataset_version");
            assertThat(cols).containsKeys("id", "dataset_id", "version_number",
                    "composition_stats", "composition_hash", "actual_baseline_pass_rate",
                    "created_at", "created_by");
            // ★ r4 W1 fix verify: composition_hash column exists.
            assertThat(cols.get("composition_hash").get("data_type"))
                    .isEqualTo("character varying");
            // ★ r4 D1 fix verify: actual_baseline_pass_rate column exists.
            assertThat(cols.get("actual_baseline_pass_rate").get("data_type"))
                    .isEqualTo("double precision");
        }

        @Test
        @DisplayName("t_eval_dataset_version_scenario composite PK exists")
        void bridgeTableHasCompositePk() {
            List<Object[]> pks = entityManager.createNativeQuery("""
                    SELECT a.attname FROM pg_index i
                      JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey)
                     WHERE i.indrelid = 't_eval_dataset_version_scenario'::regclass
                       AND i.indisprimary
                    """).getResultList();
            List<String> pkCols = pks.stream().map(r -> r.toString()).collect(Collectors.toList());
            assertThat(pkCols).hasSize(2);
            assertThat(pkCols).containsExactlyInAnyOrder("dataset_version_id", "scenario_id");
        }

        @Test
        @DisplayName("uq_eval_dataset_owner_name unique index present")
        void uniqueIndexes() {
            List<String> indexNames = entityManager.createNativeQuery(
                    "SELECT indexname FROM pg_indexes WHERE tablename = 't_eval_dataset'")
                    .getResultList();
            assertThat(indexNames).contains("uq_eval_dataset_owner_name");
        }

        @Test
        @DisplayName("uq_eval_dataset_version (dataset_id, version_number) unique index present")
        void versionUniqueIndex() {
            List<String> indexNames = entityManager.createNativeQuery(
                    "SELECT indexname FROM pg_indexes WHERE tablename = 't_eval_dataset_version'")
                    .getResultList();
            assertThat(indexNames).contains("uq_eval_dataset_version");
        }
    }

    // -----------------------------------------------------------------------
    // V111 — t_prompt_ab_run.dataset_version_id column
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("V111 — t_prompt_ab_run.dataset_version_id column + index")
    class V111Schema {

        @Test
        @DisplayName("dataset_version_id column exists VARCHAR(36) NULL")
        void datasetVersionIdColumnExists() {
            Map<String, Object> col = describeColumns("t_prompt_ab_run").get("dataset_version_id");
            assertThat(col).isNotNull();
            assertThat(col.get("data_type")).isEqualTo("character varying");
            assertThat(((Number) col.get("character_maximum_length")).intValue()).isEqualTo(36);
            assertThat(col.get("is_nullable")).isEqualTo("YES");
        }

        @Test
        @DisplayName("idx_prompt_ab_run_dataset partial index present")
        void partialIndexPresent() {
            List<String> indexNames = entityManager.createNativeQuery(
                    "SELECT indexname FROM pg_indexes WHERE tablename = 't_prompt_ab_run'")
                    .getResultList();
            assertThat(indexNames).contains("idx_prompt_ab_run_dataset");
        }
    }

    // -----------------------------------------------------------------------
    // V112 — seed 30 benchmark scenarios + 3 datasets each with v1
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("V112 — benchmark scenario + dataset seed")
    class V112Seed {

        @Test
        @DisplayName("30 benchmark scenarios seeded")
        void thirtyBenchmarkScenariosSeeded() {
            Number count = (Number) entityManager.createNativeQuery(
                    "SELECT COUNT(*) FROM t_eval_scenario WHERE source_type='benchmark'")
                    .getSingleResult();
            assertThat(count.intValue()).isEqualTo(30);
        }

        @Test
        @DisplayName("baseline / regression / mixed datasets all seeded with v1")
        void threeDatasetsSeeded() {
            List<Object[]> rows = entityManager.createNativeQuery("""
                    SELECT d.name, v.version_number, v.composition_hash IS NOT NULL,
                           (v.composition_stats->>'total')::int
                      FROM t_eval_dataset d
                      JOIN t_eval_dataset_version v ON v.dataset_id = d.id
                     WHERE d.name IN ('main-assistant-baseline-v1',
                                       'main-assistant-regression-v1',
                                       'main-assistant-mixed-v1')
                     ORDER BY d.name, v.version_number
                    """).getResultList();
            assertThat(rows).hasSize(3);
            // baseline-v1 v1 has 30 scenarios.
            assertThat(rows.stream()
                    .filter(r -> "main-assistant-baseline-v1".equals(r[0]))
                    .findFirst().orElseThrow()[3])
                    .isEqualTo(30);
        }

        @Test
        @DisplayName("composition_hash is set on at least the baseline version")
        void baselineVersionHasCompositionHash() {
            String hash = (String) entityManager.createNativeQuery("""
                    SELECT v.composition_hash
                      FROM t_eval_dataset d
                      JOIN t_eval_dataset_version v ON v.dataset_id = d.id
                     WHERE d.name = 'main-assistant-baseline-v1'
                       AND v.version_number = 1
                    """).getSingleResult();
            assertThat(hash).isNotNull();
            assertThat(hash).hasSize(64);   // SHA256 hex.
        }

        @Test
        @DisplayName("baseline-v1 v1 has 30 bridge rows")
        void baselineBridgeRowCount() {
            Number bridgeCount = (Number) entityManager.createNativeQuery("""
                    SELECT COUNT(*)
                      FROM t_eval_dataset_version_scenario b
                      JOIN t_eval_dataset_version v ON v.id = b.dataset_version_id
                      JOIN t_eval_dataset d ON d.id = v.dataset_id
                     WHERE d.name = 'main-assistant-baseline-v1'
                       AND v.version_number = 1
                    """).getSingleResult();
            assertThat(bridgeCount.intValue()).isEqualTo(30);
        }
    }

    // -----------------------------------------------------------------------
    // helpers (mirrors V91MigrationIT.describeColumns)
    // -----------------------------------------------------------------------

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
