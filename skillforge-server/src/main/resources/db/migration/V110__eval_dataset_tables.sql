-- EVAL-DATASET-LAYER V1 (2026-05-24): introduce the three-tier
-- Dataset / DatasetVersion / DatasetVersionScenario tables.
--
-- Why three tiers (aligns with Langfuse Dataset / DatasetItem / DatasetRunItem,
-- and τ-bench's gt_data_hash version pinning):
-- - t_eval_dataset           : named collection (owner_id-scoped name).
-- - t_eval_dataset_version   : immutable snapshot — once published, scenario
--                              membership is frozen so cross-run comparison
--                              against a given version_number stays meaningful.
-- - t_eval_dataset_version_scenario : n:n bridge → a single scenario may
--                              participate in many dataset versions (e.g. a
--                              GAIA Lv1 scenario sits in main-assistant-baseline
--                              and code-agent-baseline simultaneously).
--
-- ★ r4 B2 fix: created_at/updated_at use TIMESTAMPTZ (not TIMESTAMP) because
--   Hibernate's @CreatedDate Instant binding requires it. TIMESTAMP loses
--   tz info → off-by-zone-offset bugs in non-UTC environments. See V108 +
--   t_prompt_ab_run project convention.
-- ★ r4 W3/W5 fix: ON DELETE policy is intentional:
--   - dataset_version_id → t_eval_dataset_version : CASCADE (deleting a
--     version reaps its membership rows).
--   - scenario_id → t_eval_scenario             : RESTRICT (don't allow
--     deletion of a scenario referenced by any version → preserves snapshot
--     completeness).
--   V1 implication: after V112 seeds, every existing scenario is referenced
--   → scenarios are effectively undeletable. Workaround: use
--   EvalScenarioEntity.status='archived' for soft delete. See V2 backlog
--   for DatasetVersion.status='deprecated' lifecycle.

-- 1) Dataset (collection identity).
CREATE TABLE IF NOT EXISTS t_eval_dataset (
    id           VARCHAR(36)  PRIMARY KEY,
    name         VARCHAR(128) NOT NULL,
    description  TEXT,
    owner_id     BIGINT       NOT NULL,
    agent_id     VARCHAR(36),                                  -- NULL = cross-agent
    tags         JSONB,                                        -- e.g. ["gaia","lv1"]
    is_public    BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_eval_dataset_owner_name
    ON t_eval_dataset(owner_id, name);
CREATE INDEX IF NOT EXISTS idx_eval_dataset_agent
    ON t_eval_dataset(agent_id) WHERE agent_id IS NOT NULL;

-- 2) Dataset Version (immutable snapshot — once created, scenario_ids frozen).
--    composition_hash = SHA256(sorted(scenario_ids)) for cross-version diff
--    detection (τ-bench-style gt_data_hash). actual_baseline_pass_rate is
--    back-written by the first completed A/B run (D1 fix).
CREATE TABLE IF NOT EXISTS t_eval_dataset_version (
    id                          VARCHAR(36)      PRIMARY KEY,
    dataset_id                  VARCHAR(36)      NOT NULL
                                REFERENCES t_eval_dataset(id) ON DELETE CASCADE,
    version_number              INTEGER          NOT NULL,
    composition_stats           JSONB,
    composition_hash            VARCHAR(64),
    actual_baseline_pass_rate   DOUBLE PRECISION,
    created_at                  TIMESTAMPTZ      NOT NULL DEFAULT now(),
    created_by                  BIGINT
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_eval_dataset_version
    ON t_eval_dataset_version(dataset_id, version_number);
CREATE INDEX IF NOT EXISTS idx_eval_dataset_version_hash
    ON t_eval_dataset_version(composition_hash) WHERE composition_hash IS NOT NULL;

-- 3) n:n bridge. PK = (dataset_version_id, scenario_id). CASCADE on the
--    version side, RESTRICT on the scenario side (see top-of-file rationale).
CREATE TABLE IF NOT EXISTS t_eval_dataset_version_scenario (
    dataset_version_id  VARCHAR(36)  NOT NULL
                        REFERENCES t_eval_dataset_version(id) ON DELETE CASCADE,
    scenario_id         VARCHAR(36)  NOT NULL
                        REFERENCES t_eval_scenario(id) ON DELETE RESTRICT,
    PRIMARY KEY (dataset_version_id, scenario_id)
);

CREATE INDEX IF NOT EXISTS idx_evds_scenario
    ON t_eval_dataset_version_scenario(scenario_id);
