-- EVAL-DATASET-LAYER V1 (2026-05-24): extend t_eval_scenario with source_type +
-- source_ref + purpose to disambiguate benchmark / session-derived / manual
-- scenarios and align with SWE-bench regression-aware (F2P / P2P) thinking.
--
-- ★ r3 BLOCKER fix: drop NOT NULL on agent_id so benchmark scenarios with
--   no specific agent owner are legal (agent_id stays nullable; older rows
--   keep their non-null value, no data lost).
-- ★ r4 W2 fix: every ADD COLUMN / ADD CONSTRAINT / CREATE INDEX guarded by
--   IF NOT EXISTS so the migration is idempotent on dev DB reset / repeated
--   apply against partially-migrated databases. PostgreSQL has no native
--   "ADD CONSTRAINT IF NOT EXISTS" so we wrap in DO $$ ... $$ blocks.
-- ★ r4 B1 fix: field name source_type (not "origin") — avoids collision with
--   SessionEntity.origin (which means production/eval, completely different
--   semantic dimension). source_type forms a type/instance pairing with the
--   sibling source_ref column.
--
-- Existing 6 t_eval_scenario rows are retroactively flagged source_type=
-- 'session_derived' + purpose='regression' (acceptance #1 of MRD). The same
-- 6 rows are kept (they're real session-failure ground truth) and become the
-- regression-v1 dataset payload in V112.

-- 1) Make agent_id nullable so benchmark scenarios can omit it.
ALTER TABLE t_eval_scenario ALTER COLUMN agent_id DROP NOT NULL;

-- 2) Add source_type (closed enum) + back-fill existing rows + tighten NOT NULL.
ALTER TABLE t_eval_scenario ADD COLUMN IF NOT EXISTS source_type VARCHAR(32);
UPDATE t_eval_scenario SET source_type='session_derived' WHERE source_type IS NULL;
ALTER TABLE t_eval_scenario ALTER COLUMN source_type SET NOT NULL;

DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_eval_scenario_source_type') THEN
    ALTER TABLE t_eval_scenario ADD CONSTRAINT chk_eval_scenario_source_type
      CHECK (source_type IN ('benchmark','session_derived','manual'));
  END IF;
END $$;

-- 3) Add source_ref (free-form reference back to origin: gaia/lv1/001 etc).
--    Nullable — only mandatory for new benchmark/manual/session_derived rows.
ALTER TABLE t_eval_scenario ADD COLUMN IF NOT EXISTS source_ref VARCHAR(256);

-- 4) Add purpose (closed enum) + back-fill existing rows + tighten NOT NULL.
--    Existing 6 rows are session-derived failure cases → purpose='regression'.
ALTER TABLE t_eval_scenario ADD COLUMN IF NOT EXISTS purpose VARCHAR(32);
UPDATE t_eval_scenario SET purpose='regression' WHERE purpose IS NULL;
ALTER TABLE t_eval_scenario ALTER COLUMN purpose SET NOT NULL;

DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_eval_scenario_purpose') THEN
    ALTER TABLE t_eval_scenario ADD CONSTRAINT chk_eval_scenario_purpose
      CHECK (purpose IN ('baseline_anchor','regression','ablation'));
  END IF;
END $$;

-- 5) Indexes — source_type/purpose are tab filters in the UI; source_ref index
--    is partial because nullable (only benchmark/manual rows carry refs).
CREATE INDEX IF NOT EXISTS idx_eval_scenario_source_type
    ON t_eval_scenario(source_type);
CREATE INDEX IF NOT EXISTS idx_eval_scenario_purpose
    ON t_eval_scenario(purpose);
CREATE INDEX IF NOT EXISTS idx_eval_scenario_source_ref
    ON t_eval_scenario(source_ref) WHERE source_ref IS NOT NULL;
