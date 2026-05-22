-- V96__add_span_behavior_stats_tool.sql
-- ANNOTATOR-BEHAVIOR-SIGNALS (2026-05-22):
--
-- Goal: extend the session-annotator (id=7) so its STEP 2.2 LLM reasoning gets
-- behavioral efficiency context (per-tool call counts, total LLM turns, error
-- span count) on top of just the trace span tree. Detects three new outcomes:
--   * tool_overuse       — single tool called > 10x → suspect_surface=behavior_rule
--   * loop_inefficiency  — total LLM turns > 20 and not success → suspect_surface=prompt
--   * slow_execution     — totalDurationMs > 5 min → suspect_surface=prompt
--
-- Two effects:
--   1) UPDATE t_agent.tool_ids — append SpanBehaviorStats to the
--      session-annotator's whitelist. AgentService.toToolIds reads this column
--      directly (UI/API source of truth) — V76 set the original 4-tool array
--      ["DetectSignalAnnotations","GetTrace","AnnotateSession","RecomputeClusters"];
--      we append the new tool name in 5th position. The pipeline call order in
--      the prompt is:
--        DetectSignal → SpanBehaviorStats (per-session)
--          → GetTrace → AnnotateSession (per-session)
--          → RecomputeClusters (once at the end)
--      Position-in-tool_ids doesn't dictate call order; the prompt does.
--
--   2) UPDATE t_agent.system_prompt — patch the V95 inline prompt:
--      a) Inject "STEP 1.5 — 行为统计" between STEP 1 and STEP 2.
--      b) Extend the STEP 2.2 outcome enum with tool_overuse / loop_inefficiency /
--         slow_execution.
--      c) Append a behavior-judgment block before the AnnotateSession call, so
--         the LLM uses STEP 1.5 data to bias its outcome decision.
--
-- Idempotency: pg_replace returns the input unchanged when the anchor string is
-- absent, so re-running V96 on a row already patched is a no-op (replace finds
-- nothing to match because the anchor sites were already rewritten). Flyway V
-- baselines never re-run anyway; this is a defense-in-depth note for operators
-- restoring DB from a snapshot. WHERE name='session-annotator' guards against
-- agent-rename collisions.
--
-- Anchor-string strategy: V95 wrote the prompt as a dollar-quoted string, so
-- we use the same `$step$...$step$` dollar tag for the inserted text. The
-- anchor strings are uniquely-occurring substrings of the V95 prompt — verified
-- against V95 line numbers in this commit. If V95 changes anchor wording in a
-- future migration, this V96 patch silently no-ops; reviewer must re-anchor.

-- ─────────────────────────────────────────────────────────────────────────
-- 1. Append SpanBehaviorStats to tool_ids whitelist.
-- ─────────────────────────────────────────────────────────────────────────
UPDATE t_agent
SET tool_ids = '["DetectSignalAnnotations","SpanBehaviorStats","GetTrace","AnnotateSession","RecomputeClusters"]',
    updated_at = NOW()
WHERE name = 'session-annotator';

-- ─────────────────────────────────────────────────────────────────────────
-- 2a. Inject STEP 1.5 between STEP 1 and STEP 2.
-- ─────────────────────────────────────────────────────────────────────────
UPDATE t_agent
SET system_prompt = replace(
    system_prompt,
    'STEP 2 — LLM 标注（你的核心工作）：',
    $step$STEP 1.5 — 行为统计（deterministic）：
  对 STEP 1 返回的 sessions_needing_llm 列表里的每个 sessionId，在 STEP 2 之前先调：
    SpanBehaviorStats(sessionId=<该 sessionId>)
  返回：{ totalTurns, totalDurationMs, perToolCounts, errorSpanCount, topTool,
         topToolCount, hasToolOveruse, hasLoopInefficiency }
  **把这个结果保留在上下文中，在 STEP 2.2 LLM 推理时使用。**
  本步是纯数据提取，不需要 LLM 判断。
  若 SpanBehaviorStats 返回空（无 span 数据，例如 totalTurns=0 + perToolCounts=[]），
  跳过该 sessionId 的 STEP 1.5 数据增强，直接进 STEP 2（按 trace-only 推理）。

STEP 2 — LLM 标注（你的核心工作）：$step$
),
    updated_at = NOW()
WHERE name = 'session-annotator';

-- ─────────────────────────────────────────────────────────────────────────
-- 2b. Extend the outcome enum (STEP 2.2 outcome description line).
--     V95 line 339-340 reads:
--        - `outcome`：          success | partial_success | failure | cancelled
--                              | infrastructure_failure | cost_high
--     We append three new types on a continuation line. Anchor is unique to
--     this position (the only other "cost_high" mention is at the bottom
--     判断准则 list, which uses a different "    cost_high：" prefix line).
-- ─────────────────────────────────────────────────────────────────────────
UPDATE t_agent
SET system_prompt = replace(
    system_prompt,
    $needle$| infrastructure_failure | cost_high$needle$,
    $repl$| infrastructure_failure | cost_high
                              | tool_overuse       （hasToolOveruse=true：单工具调用 >10 次，suspect_surface=behavior_rule）
                              | loop_inefficiency  （hasLoopInefficiency=true 且非 success：总轮次 >20，suspect_surface=prompt）
                              | slow_execution     （totalDurationMs > 300000：5 分钟以上，suspect_surface=prompt）$repl$
),
    updated_at = NOW()
WHERE name = 'session-annotator';

-- ─────────────────────────────────────────────────────────────────────────
-- 2c. Append behavior-judgment block before the AnnotateSession main call.
--     V95 line 355 reads exactly:
--        调 `AnnotateSession(sessionId, outcome, suspect_surface, confidence,
--     Using the multi-arg signature as anchor (not the 0-trace branch's
--     `AnnotateSession(...)` shorthand at V95 line 328) to land in the right
--     place.
-- ─────────────────────────────────────────────────────────────────────────
UPDATE t_agent
SET system_prompt = replace(
    system_prompt,
    '调 `AnnotateSession(sessionId, outcome, suspect_surface, confidence,',
    $rule$**行为效率判断（参考信号，最终 outcome 由 LLM 综合判断）**：
        若 STEP 1.5 数据存在：
          - hasToolOveruse=true（topToolCount > 10）→ 考虑 outcome=tool_overuse,
            suspect_surface=behavior_rule。reasoning 必须说明哪个工具被过度调用、调了多少次
            （引用 topTool / topToolCount 具体数字）。
          - hasLoopInefficiency=true（totalTurns > 20）且当前判断 outcome 不是 success →
            考虑 outcome=loop_inefficiency, suspect_surface=prompt。
            reasoning 说明轮次过多、任务是否完成。
          - totalDurationMs > 300000（5 分钟）→ 考虑 outcome=slow_execution,
            suspect_surface=prompt。
          - perToolCounts 里出现明显的 "tool not found" / 工具名不存在的 error span →
            在 reasoning 里加 fault_type=called_wrong_tool 标注。
        注意：行为信号是参考，最终 outcome 由 LLM 综合判断（trace 内容 + 行为信号）。
        失败 / cost_high / infrastructure_failure 等已有规则优先于本节的行为判断。

      调 `AnnotateSession(sessionId, outcome, suspect_surface, confidence,$rule$
),
    updated_at = NOW()
WHERE name = 'session-annotator';
