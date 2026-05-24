-- BEHAVIOR-RULE-AB-EVAL V1 (2026-05-24): augment t_behavior_rule_ab_run with
-- dual-criteria fields + dataset linkage. Existing rows (V4 era — likely 0
-- in prod since FlywheelAutoTriggerListener.dispatchBehaviorRuleAutoAb was a
-- stub) gain nullable fields defaulting to NULL → backward compatible.
--
-- target_delta_pp / regression_delta_pp store the split-deltas; legacy
-- delta_pass_rate retained as "global delta over whole dataset" for FE
-- backwards-compat (PromptAbRunResponse-style shapes still render it). The
-- dual-criteria gate in §3.3 reads target/regression columns, not legacy.
--
-- target_trigger_tags moved to t_behavior_rule_version (not ab_run) because
-- it's a property of the rule (set when version created), not the A/B run.

ALTER TABLE t_behavior_rule_ab_run
    ADD COLUMN IF NOT EXISTS target_delta_pp     DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS regression_delta_pp DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS target_count        INTEGER,
    ADD COLUMN IF NOT EXISTS regression_count    INTEGER,
    ADD COLUMN IF NOT EXISTS dataset_version_id  VARCHAR(36),
    ADD COLUMN IF NOT EXISTS candidate_eval_run_id VARCHAR(36),
    ADD COLUMN IF NOT EXISTS ab_run_kind         VARCHAR(16) NOT NULL DEFAULT 'with_vs_without';

-- ★ r1-FIX (database WARN-2): align with V111 (t_prompt_ab_run) + V113
--   (t_skill_ab_run) — both use REAL FK to t_eval_dataset_version. V115
--   was originally drafted as soft FK; matching the project convention.
--   ON DELETE RESTRICT: deleting a dataset version mid-AB-run would silently
--   strand the run pointer; force operators to retire runs first.
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_brar_dataset_version') THEN
    ALTER TABLE t_behavior_rule_ab_run
        ADD CONSTRAINT fk_brar_dataset_version
        FOREIGN KEY (dataset_version_id) REFERENCES t_eval_dataset_version(id)
        ON DELETE RESTRICT;
  END IF;
END $$;

DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_brar_ab_run_kind') THEN
    ALTER TABLE t_behavior_rule_ab_run ADD CONSTRAINT chk_brar_ab_run_kind
      CHECK (ab_run_kind IN ('with_vs_without','variant_a_vs_b'));
  END IF;
END $$;

-- Index backs the FK constraint above (fk_brar_dataset_version, real FK to
-- t_eval_dataset_version) + per-dataset run-history scans. r1-FIX promoted
-- the original soft-FK draft to a real FK; this index pairs with that.
CREATE INDEX IF NOT EXISTS idx_brar_dataset_version
    ON t_behavior_rule_ab_run(dataset_version_id)
    WHERE dataset_version_id IS NOT NULL;

-- Augment BehaviorRuleVersion with target_trigger_tags (JSONB array). Default
-- '[]' = "no targeting; runs as regression-check only" (fallback per D1).
ALTER TABLE t_behavior_rule_version
    ADD COLUMN IF NOT EXISTS target_trigger_tags JSONB NOT NULL DEFAULT '[]'::jsonb;

CREATE INDEX IF NOT EXISTS idx_brv_target_trigger_tags_gin
    ON t_behavior_rule_version USING GIN (target_trigger_tags)
    WHERE jsonb_array_length(target_trigger_tags) > 0;
