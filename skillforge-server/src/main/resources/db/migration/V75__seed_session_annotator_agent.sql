-- V75__seed_session_annotator_agent.sql — PROD-LABEL-CLUSTER V1 seed.
--
-- Two inserts (idempotent, mirroring V69 memory-curator dogfood structure):
--   1) t_agent row `session-annotator` (system agent, owner_id=1, is_public=TRUE,
--      lifecycle_hooks=NULL, enabled-via-status=active). system_prompt is a
--      SEE_FILE:* sentinel swapped at boot by SessionAnnotatorBootstrap from
--      classpath:session-annotator-system-prompt.md.
--   2) t_scheduled_task row `session-annotator-hourly` (P12 ScheduledTask)
--      pointing at the agent. cron 0 0 * * * * (top of every hour),
--      session_mode=new, enabled=TRUE, concurrency_policy=skip-if-running.
--      creator_user_id=0 (SYSTEM marker — same convention as V69 + SkillSelfImproveLoop.SYSTEM_USER_ID).
--
-- Conventions divergent from V69 (acknowledged, intentional):
--   * owner_id = 1 (admin user) + is_public = TRUE
--     V69 memory-curator left owner_id=NULL; this migration uses the new
--     "admin owns / public shared" convention adopted in tech-design.md §2.2.
--     V69 will NOT be retroactively patched — we accept the inconsistency.
--   * enabled = TRUE
--     V69 shipped enabled=FALSE so V1 LEGACY @Scheduled path stayed authoritative
--     until operator flipped it via dashboard. V1 PROD-LABEL-CLUSTER has no
--     pre-existing path competing for the trigger — we ship enabled=TRUE so the
--     dogfood pipeline starts running on first boot post-migration. Operator can
--     flip via /schedules page at any time.
--   * lifecycle_hooks = NULL
--     V69 wires a SESSION_END handler that broadcasts "proposals pending review"
--     to admin WS clients. PROD-LABEL-CLUSTER V1 has no such notification need
--     (Phase 1.5 dashboard polls on visit); lifecycle_hooks intentionally NULL.
--
-- Phase 1.1 校对 vs tech-design.md §2.2 (字段名 BE-Dev 抄准):
--   Tech-design hypothetical          → V69 actual (this migration follows V69):
--     * target_agent_name (VARCHAR)   → agent_id (BIGINT, FK via subselect on name)
--     * cron_expression               → cron_expr
--     * (missing fields in tech-design) → timezone, prompt_template, session_mode,
--                                          status — all NOT NULL per V59 schema
--   See V59__create_scheduled_tasks.sql for the canonical schema and
--   V69__memory_curator_dogfood.sql for the INSERT template this follows.

-- ---------- 1. session-annotator system agent ----------
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
    'session-annotator',
    'System agent: hourly orchestration of production session annotation + clustering. '
        || 'Calls DetectSignalAnnotations / AnnotateSession / RecomputeClusters tools in sequence. '
        || 'Drives PROD-LABEL-CLUSTER (V1) data flywheel step ①②.',
    -- Default model; runtime-overridable via dashboard /agents page. Matches
    -- memory-curator default tier (mid-large; cost-sensitive operators can swap
    -- to a smaller tier after observing first-week metrics).
    'claude-sonnet-4-6',
    -- Placeholder swapped at boot by SessionAnnotatorBootstrap from
    -- classpath:session-annotator-system-prompt.md (avoids SQL-escaping a
    -- multi-line system prompt).
    'SEE_FILE:session-annotator-system-prompt.md',
    '[]',
    '["DetectSignalAnnotations","AnnotateSession","RecomputeClusters"]',
    '{"temperature": 0.2, "maxTokens": 4096}',
    NULL,              -- lifecycle_hooks: V1 has no SESSION_END broadcast need
    1,                 -- owner_id = 1 (admin) — new convention vs V69 NULL
    TRUE,              -- is_public = TRUE
    'active',
    'auto',
    NOW(),
    NOW()
WHERE NOT EXISTS (
    SELECT 1 FROM t_agent WHERE name = 'session-annotator'
);

-- ---------- 2. ScheduledTask: hourly trigger for session-annotator ----------
-- Fields follow V59 schema + V69 INSERT template verbatim. NOT tech-design.md
-- §2.2 (which had placeholder field names "target_agent_name / cron_expression");
-- those names do not exist in V59.
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
    'session-annotator-hourly',
    0,
    (SELECT id FROM t_agent WHERE name = 'session-annotator'),
    -- 6-field Spring cron (second minute hour dom month dow): top of every hour.
    '0 0 * * * *',
    'Asia/Shanghai',
    'Run hourly session annotation + clustering pipeline. '
        || 'Call DetectSignalAnnotations(window="1h") first; then AnnotateSession '
        || 'on returned sessions_needing_llm (cap 10); finally '
        || 'RecomputeClusters(window="7d"). Never abort the pipeline on tool error.',
    'new',
    TRUE,              -- V1 dogfood default ON (vs V69 FALSE) — see header
    'skip-if-running',
    'idle',
    NOW(),
    NOW()
WHERE NOT EXISTS (
    SELECT 1 FROM t_scheduled_task WHERE name = 'session-annotator-hourly'
)
AND EXISTS (
    SELECT 1 FROM t_agent WHERE name = 'session-annotator'
);
