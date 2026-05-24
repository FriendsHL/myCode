-- BEHAVIOR-RULE-AB-EVAL V1 — heuristic seed of rule_trigger_hints for the 49
-- main-assistant-mixed-v1 scenarios so target/regression split has signal
-- on day 1. Heuristic: task text keyword → hint tag. Hand-curated minimal
-- set; precision over recall (false-positive hints = wasting eval cycles
-- on regression-shaped scenarios; missing hint = scenario stays regression,
-- still informative).

-- ★ r1-FIX (database WARN-1): JSONB `||` concat + NOT @> idempotent guard
--   replace last-write-wins assignment. A scenario matching multiple ILIKE
--   patterns now accumulates ALL applicable tags (bash + multi_tool etc),
--   not just the last UPDATE's tag. Guard `NOT (... @> '["tag"]')` makes
--   each UPDATE idempotent — re-running V116 doesn't duplicate entries.

-- Token-heavy / multi-step scenarios → +"long_context"
UPDATE t_eval_scenario
   SET rule_trigger_hints = rule_trigger_hints || '["long_context"]'::jsonb
 WHERE source_type = 'session_derived'
   AND (task ILIKE '%research%' OR task ILIKE '%综合%'
        OR task ILIKE '%总结%'   OR task ILIKE '%汇总%')
   AND NOT (rule_trigger_hints @> '["long_context"]'::jsonb);

-- Bash / shell tool scenarios → +"uses_bash"
UPDATE t_eval_scenario
   SET rule_trigger_hints = rule_trigger_hints || '["uses_bash"]'::jsonb
 WHERE (task ILIKE '%bash%' OR task ILIKE '%命令%' OR task ILIKE '%shell%')
   AND NOT (rule_trigger_hints @> '["uses_bash"]'::jsonb);

-- File ops → +"uses_file_io"
UPDATE t_eval_scenario
   SET rule_trigger_hints = rule_trigger_hints || '["uses_file_io"]'::jsonb
 WHERE (task ILIKE '%文件%' OR task ILIKE '%file%'
        OR task ILIKE '%read%' OR task ILIKE '%write%')
   AND NOT (rule_trigger_hints @> '["uses_file_io"]'::jsonb);

-- Multi-tool sequence → +"multi_tool"
UPDATE t_eval_scenario
   SET rule_trigger_hints = rule_trigger_hints || '["multi_tool"]'::jsonb
 WHERE (task ILIKE '%step by step%' OR task ILIKE '%先%然后%' OR task ILIKE '%分步%')
   AND NOT (rule_trigger_hints @> '["multi_tool"]'::jsonb);

-- ★ r1-FIX (database WARN-3): enforce AC-7 at migration time. If fewer than
--   5 scenarios have hints after V116, fail the migration loudly so deploy
--   never silently violates acceptance. Threshold matches AC-7 floor.
DO $$
DECLARE v INTEGER;
BEGIN
    SELECT COUNT(*) INTO v FROM t_eval_scenario
        WHERE jsonb_array_length(rule_trigger_hints) > 0;
    IF v < 5 THEN
        RAISE EXCEPTION '[V116] AC-7 violation: only % scenarios have rule_trigger_hints (need >= 5). '
                        'Check V112 seed presence + ILIKE pattern coverage.', v;
    END IF;
    RAISE NOTICE '[V116] seeded rule_trigger_hints on % scenarios (AC-7 OK, threshold 5)', v;
END $$;
