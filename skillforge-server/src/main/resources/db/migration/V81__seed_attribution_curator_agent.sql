-- V81__seed_attribution_curator_agent.sql — V3 ATTRIBUTION-AGENT Phase 1.1 seed.
--
-- Two idempotent INSERTs (V75 / V79 mirror):
--   1) t_agent row `attribution-curator` (system agent, owner_id=1, is_public=TRUE,
--      lifecycle_hooks=NULL, status=active). system_prompt is the
--      SEE_FILE:attribution-curator-system-prompt.md sentinel, swapped at boot
--      by AttributionCuratorBootstrap from
--      classpath:attribution-curator-system-prompt.md.
--   2) t_scheduled_task row `attribution-dispatcher-hourly` driving the above
--      agent. cron 0 15 * * * * (every hour at :15 — intentionally offset
--      from V75 session-annotator-hourly :00 + V79 metrics-collector-hourly :30
--      so the three flywheel jobs don't collide on top-of-hour resource
--      spikes). session_mode=new, enabled=TRUE,
--      concurrency_policy=skip-if-running. creator_user_id=0 (SYSTEM marker,
--      same convention as V69 / V75 / V79 / SkillSelfImproveLoop).
--
-- Conventions (V79 mirror):
--   * owner_id = 1 (admin) + is_public = TRUE — adopted in PROD-LABEL-CLUSTER V1.
--   * enabled = TRUE — V3 dispatcher path is the only attribution path; no
--     V1/V2 @Scheduled competitor exists. Operator can flip via /schedules.
--   * lifecycle_hooks = NULL — V3 doesn't broadcast SESSION_END for the
--     curator (dashboard polls t_optimization_event on visit + WS notifies
--     come from ProposeOptimization tool's downstream write path, not
--     lifecycle hooks).
--
-- Tools list: ["PatternRead","SessionAnnotationRead","GetTrace",
-- "ProposeOptimization","WriteOptimizationEvent"]. Phase 1.2 implements
-- PatternRead / SessionAnnotationRead / ProposeOptimization /
-- WriteOptimizationEvent; GetTrace is V1 V76 reused unchanged. This seed
-- declares the agent toolbox up-front so allowlist filtering works the
-- moment the tools register at Phase 1.2 boot.
--
-- Phase 1.1 校对 vs tech-design.md §2 / §4 (字段 BE-Dev 抄准):
--   * attribution-curator is a system AGENT (not a service @Scheduled) because
--     t_scheduled_task.agent_id is NOT NULL (V59 schema). The agent + tool
--     wrapping is the V1 V69 / V75 / V79 ratified pattern for system cron jobs.
--   * Default model claude-sonnet-4-6 matches V75 / V79 (the curator does
--     LLM reasoning per prd.md §4 STEP 3, but the model id is user-overridable
--     at runtime via /agents; the seed just provides a sane default).
--   * config temperature=0.2 — low-but-not-zero: STEP 1 / 2 / 4 are
--     deterministic tool calls, STEP 3 needs minimal creativity to phrase
--     description + expected_impact prose (matches V1 session-annotator).
--   * maxTokens=4096 — STEP 3 prose is at most a few hundred tokens, but
--     the tool-call iteration through STEP 2 (cap-5 member sessions ×
--     2 tools each + STEP 1 PatternRead + STEP 4 ProposeOptimization)
--     needs room to round-trip without truncation.

-- ---------- 1. attribution-curator system agent ----------
INSERT INTO t_agent (
    name,
    description,
    model_id,
    system_prompt,
    skill_ids,
    tool_ids,
    config,
    lifecycle_hooks,
    owner_id,
    is_public,
    status,
    execution_mode,
    created_at,
    updated_at
)
SELECT
    'attribution-curator',
    'System agent: hourly attribution of V1 production session patterns. '
        || 'Reads pattern + member sessions + traces, proposes which surface to optimize '
        || '(skill/prompt) and how. Writes t_optimization_event stage=proposal_pending '
        || 'pending human approval. Drives PROD-OPTIMIZATION-FLYWHEEL V3 step ③⑤⑥.',
    'claude-sonnet-4-6',
    -- Placeholder swapped at boot by AttributionCuratorBootstrap from
    -- classpath:attribution-curator-system-prompt.md.
    'SEE_FILE:attribution-curator-system-prompt.md',
    '[]',
    '["PatternRead","SessionAnnotationRead","GetTrace","ProposeOptimization","WriteOptimizationEvent"]',
    -- temperature=0.2: STEP 3 LLM reasoning is mostly classification +
    -- short rationale; 0.0 would make the prose stilted, 1.0 would
    -- destabilize the structured ProposeOptimization input.
    '{"temperature": 0.2, "maxTokens": 4096}',
    NULL,
    1,
    TRUE,
    'active',
    'auto',
    NOW(),
    NOW()
WHERE NOT EXISTS (
    SELECT 1 FROM t_agent WHERE name = 'attribution-curator'
);

-- ---------- 2. ScheduledTask: hourly trigger for attribution-curator ----------
INSERT INTO t_scheduled_task (
    name,
    creator_user_id,
    agent_id,
    cron_expr,
    timezone,
    prompt_template,
    session_mode,
    enabled,
    concurrency_policy,
    status,
    created_at,
    updated_at
)
SELECT
    'attribution-dispatcher-hourly',
    0,
    (SELECT id FROM t_agent WHERE name = 'attribution-curator'),
    -- 6-field Spring cron (sec min hr dom mon dow): hourly at :15, intentionally
    -- offset from V75 session-annotator-hourly (':00') + V79 metrics-collector-hourly
    -- (':30') to spread load.
    '0 15 * * * *',
    'Asia/Shanghai',
    'Attribution dispatcher: scan unprocessed patterns and propose optimizations',
    'new',
    TRUE,
    'skip-if-running',
    'idle',
    NOW(),
    NOW()
WHERE NOT EXISTS (
    SELECT 1 FROM t_scheduled_task WHERE name = 'attribution-dispatcher-hourly'
)
AND EXISTS (
    SELECT 1 FROM t_agent WHERE name = 'attribution-curator'
);
