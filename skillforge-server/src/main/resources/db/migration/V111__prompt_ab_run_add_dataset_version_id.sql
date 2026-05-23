-- EVAL-DATASET-LAYER V1 (2026-05-24): lock each A/B run to a specific dataset
-- version so cross-run comparison stays scientifically meaningful even after
-- the dataset evolves (caller may bump v1 → v2; older runs still point at v1).
--
-- Nullable for backward compatibility:
--   - Existing rows from attribution-derived A/B (which used ephemeral
--     pattern-derived scenarios, not a dataset) have no version.
--   - V1 new runs through the new caller path will populate; the old 5-arg
--     PromptImproverService.runAbTestAgainst @Deprecated path still works.

ALTER TABLE t_prompt_ab_run
    ADD COLUMN IF NOT EXISTS dataset_version_id VARCHAR(36)
        REFERENCES t_eval_dataset_version(id);

CREATE INDEX IF NOT EXISTS idx_prompt_ab_run_dataset
    ON t_prompt_ab_run(dataset_version_id) WHERE dataset_version_id IS NOT NULL;
