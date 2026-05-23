-- EVAL-DATASET-LAYER V1 r2 mandatory fix (2026-05-24): extend the skill A/B
-- surface with the same dataset_version pin that PromptAbRun got in V111.
--
-- Why: SkillController.startAbTest receives a body with optional
-- {@code datasetVersionId}; without a column to persist it, Jackson silently
-- drops the field and the A/B run runs against the agent's default scenarios
-- → the FE selector looks like it works but the BE quietly ignores the choice
-- (pipeline.md severity B "static failure: looks like it works but loses data").
--
-- PRD note: V1 PRD originally scoped this as V2 backlog ("V1 只 prompt
-- surface"), but the FE-BE handshake review surfaced the silent-failure path,
-- and team-lead ratified extending into skill surface as a blocker fix rather
-- than carrying the gap forward.
--
-- Schema parity with V111 (t_prompt_ab_run.dataset_version_id):
--   - VARCHAR(36) nullable
--   - FK → t_eval_dataset_version(id) (no ON DELETE — version is immutable
--     once published; if you can DELETE a referenced version you broke the
--     immutability invariant first)
--   - Partial index so legacy/ephemeral runs (NULL) don't bloat the index

ALTER TABLE t_skill_ab_run
    ADD COLUMN IF NOT EXISTS dataset_version_id VARCHAR(36)
        REFERENCES t_eval_dataset_version(id);

CREATE INDEX IF NOT EXISTS idx_skill_ab_run_dataset
    ON t_skill_ab_run(dataset_version_id) WHERE dataset_version_id IS NOT NULL;
