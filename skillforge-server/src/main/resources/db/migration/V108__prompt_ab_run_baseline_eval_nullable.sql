-- V6-FIX-AB-BASELINE-EVAL (2026-05-23): drop NOT NULL on
-- t_prompt_ab_run.baseline_eval_run_id so attribution-derived A/B runs can be
-- created (the genesis path materializes baseline from agent.system_prompt
-- via PromptImproverService.startImprovementFromAttribution — there's no
-- pre-existing eval run to anchor).
--
-- 历史：
-- - V4 (2026-04-19) 老的"标准 A/B"路径强制要求 caller 先跑 baseline eval 拿 evalRunId，
--   故 baseline_eval_run_id NOT NULL（见 V4 line 27）
-- - V6 (2026-05-17 FLYWHEEL-LOOP-CLOSURE) attribution-derived 路径接进来，
--   PromptImproverService.runAbTestAgainst 明示 "Leave baselineEvalRunId null"
--   (PromptImproverService.java:485-494) — AbEvalPipeline attribution overload
--   会 re-eval active prompt 拿 baselinePassRate
-- - 但 V4 NOT NULL 仍在 → INSERT 时撞 not-null violation
-- - V6-FIX-BASELINE 修了 v1 baseline 物化让 (agent_id, version_number)=1 不再冲突；
--   接着撞这个下一个 chain gap
-- - V15 (skill_ab_run) 跟 V82 (behavior_rule_ab_run) 同字段都已经是 nullable，
--   只有 V4 prompt 这一个是 NOT NULL — 此 migration 一致化
--
-- Schema 改动反向兼容：已有 prompt_ab_run 行都已有非空 baseline_eval_run_id，
-- 改 nullable 不影响。新 attribution-derived run 现在能合法 INSERT null。

ALTER TABLE t_prompt_ab_run
    ALTER COLUMN baseline_eval_run_id DROP NOT NULL;
