-- BEHAVIOR-RULE-AB-EVAL V1 (2026-05-24): give every EvalScenario an optional
-- JSONB tag list that BehaviorRuleAbEvalService consumes to split the dataset
-- into target subset (scenario hints ∩ rule target_trigger_tags ≠ ∅) and
-- regression subset (no intersection).
--
-- Nullable + default '[]' → existing 49 mixed scenarios start as "regression-
-- only" (no target hits). V116 seeds 5-15 scenarios with real hints derived
-- from task text heuristics, satisfying mrd.md AC-7 (≥5 non-empty).
--
-- JSONB chosen over TEXT (json) for two reasons:
--   1. Container @> operator allows index-supported "any overlap" queries
--      (`rule_trigger_hints ?| array['uses_bash','long_tool_output']`).
--   2. Matches V109 + V110 prior art (composition_stats / tags are JSONB).
--
-- NOT NULL with default '[]' avoids three-way ternary (null vs empty vs filled)
-- — caller code only branches on size > 0.

ALTER TABLE t_eval_scenario
    ADD COLUMN IF NOT EXISTS rule_trigger_hints JSONB NOT NULL DEFAULT '[]'::jsonb;

-- GIN index supports the `?|` (any-overlap) operator used by the target subset
-- query. Partial index (only non-empty arrays) keeps index small since the
-- vast majority of scenarios are regression-only.
CREATE INDEX IF NOT EXISTS idx_eval_scenario_rule_trigger_hints_gin
    ON t_eval_scenario USING GIN (rule_trigger_hints)
    WHERE jsonb_array_length(rule_trigger_hints) > 0;
