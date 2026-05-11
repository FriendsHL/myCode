-- V69__memory_curator_dogfood.sql — MEMORY-LLM-SYNTHESIS option-A dogfood seed.
--
-- Replaces the Java-internal LlmMemorySynthesisScheduler cron with a P12
-- ScheduledTask driving a `memory-curator` system agent + 4 agent tools
-- (ListActiveUsers / ListMemoryCandidates / ClusterMemories / CreateMemoryProposal).
-- See requirements/active/MEMORY-LLM-SYNTHESIS/tech-design.md "Dogfood 路径架构（选项 A）".
--
-- ⚠️ MUTUAL EXCLUSION WITH V1 LEGACY PATH:
-- Two cron paths now exist for memory synthesis:
--   * V1 LEGACY: LlmMemorySynthesisScheduler @Scheduled(cron="0 30 4 * * *"),
--                gated by yaml flag `skillforge.memory.llm-synthesis.scheduled-enabled`.
--   * V2 DOGFOOD (this migration): memory-curator system agent + this P12 row.
-- Enable AT MOST ONE at any time, otherwise both will race against the same
-- active-user set at 04:30 and produce duplicate proposals. We ship this row
-- with enabled=FALSE so V1 stays the only active path on environments that
-- already had `scheduled-enabled=true`. Flip the V2 row enabled=true via the
-- dashboard /schedules page AFTER setting V1 `scheduled-enabled=false`.
-- The admin run-once endpoint auto-prefers V2 when the seed exists and only
-- falls back to V1 when it doesn't.
--
-- This migration is idempotent (uses WHERE NOT EXISTS guards) so it is safe to
-- re-apply on environments where a prior partial run inserted the agent row.
--
-- Seeded artifacts:
--   1) t_agent row `memory-curator` (owner_id=NULL, public=true, status=active).
--      system_prompt is a SEE_FILE:* placeholder swapped in at boot by
--      MemoryCuratorBootstrap.java reading classpath:memory-curator-system-prompt.md
--      (keeping the long prompt out of SQL string escaping hell).
--   2) t_scheduled_task row pointing at the above agent. cron 04:30 daily
--      (D1 ratify), session_mode=new, enabled=FALSE (D12 first-week observation).
--      creator_user_id=0 (SYSTEM marker — same convention as SkillSelfImproveLoop.SYSTEM_USER_ID).
--   3) t_agent.lifecycle_hooks JSON column is set on the seeded row so SESSION_END
--      fires `builtin.memory.proposal-ready-broadcaster` (notify connected admin
--      WS clients that proposals are pending review).

-- ---------- 1. memory-curator system agent ----------
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
    'memory-curator',
    'System agent: nightly LLM-driven memory consolidation (dedup / reflection / optimize / contradiction). '
        || 'Drives the MEMORY-LLM-SYNTHESIS option-A dogfood path via 4 agent tools + SubAgent fan-out.',
    'bailian-deepseek-v3',
    -- Placeholder swapped at boot by MemoryCuratorBootstrap from
    -- classpath:memory-curator-system-prompt.md so the prompt stays editable in git.
    'SEE_FILE:memory-curator-system-prompt.md',
    '[]',
    -- Tool list: master uses ListActiveUsers + SubAgent; sub-sessions use the
    -- 3 single-user tools + CreateMemoryProposal. SubAgent is the system tool
    -- exported by skillforge-tools (already on the toolRegistry by default —
    -- listing it here is informational, the registry doesn't enforce membership
    -- but agent_definition.allowed_tools narrows visibility to the LLM).
    '["ListActiveUsers","ListMemoryCandidates","ClusterMemories","CreateMemoryProposal","SubAgent"]',
    '{"temperature": 0.3, "maxTokens": 4096}',
    -- lifecycle_hooks: SESSION_END fires the proposal-ready broadcaster.
    -- Schema mirrors LifecycleHooksConfig wire format (see HookEvent.wireName).
    '{"version":1,"hooks":{"SessionEnd":[{"handler":{"type":"method","methodRef":"builtin.memory.proposal-ready-broadcaster"},"timeoutSeconds":15,"failurePolicy":"continue","async":false,"displayName":"Broadcast proposals ready"}]}}',
    NULL,
    TRUE,
    'active',
    'auto',
    NOW(),
    NOW()
WHERE NOT EXISTS (
    SELECT 1 FROM t_agent WHERE name = 'memory-curator'
);

-- ---------- 2. ScheduledTask: daily 04:30 trigger for memory-curator ----------
-- creator_user_id=0 marks SYSTEM-owned (see SkillSelfImproveLoop.SYSTEM_USER_ID).
-- enabled=FALSE per D12 observation period; flip via UI once we trust the output.
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
    'memory-curator nightly',
    0,
    (SELECT id FROM t_agent WHERE name = 'memory-curator'),
    '0 30 4 * * *',
    'Asia/Shanghai',
    'Run memory consolidation for active users in the last 7 days. '
        || 'Call ListActiveUsers first, then SubAgent fan-out per user. '
        || 'Each sub-session should ListMemoryCandidates → ClusterMemories → '
        || 'CreateMemoryProposal (batch) covering dedup / reflection / optimize / contradiction.',
    'new',
    FALSE,
    'skip-if-running',
    'idle',
    NOW(),
    NOW()
WHERE NOT EXISTS (
    SELECT 1 FROM t_scheduled_task WHERE name = 'memory-curator nightly'
)
AND EXISTS (
    SELECT 1 FROM t_agent WHERE name = 'memory-curator'
);
