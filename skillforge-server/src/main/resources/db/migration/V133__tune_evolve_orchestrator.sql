-- V133__tune_evolve_orchestrator.sql
--
-- AUTOEVOLVE-AGENT-FLYWHEEL Module C — tune the evolve-orchestrator (seeded V131,
-- prompt refined V132) so the autonomous full-chain (no-anchor) run works without
-- deadlocking or tripping session-resource caps. Three coupled changes:
--
--   1. autoApprove — when the orchestrator runs opt-report itself, it must pass
--      autoApprove=true so the workflow's humanApprove gate is skipped. Otherwise
--      the opt-report run pauses for a human and the orchestrator deadlocks,
--      polling GetOptReport for a report that never reaches 'completed'. (The
--      human gate moves to the END of the evolve loop, where the operator adopts
--      the trajectory.) Threaded by string-replacing the RunWorkflow args fragment
--      in the V132 prompt.
--
--   2. max_loops 25 → 60 — each evolve iteration spends ~5 agent loops
--      (GenerateCandidate + TriggerAbEval + GetAbResult + RecordIteration +
--      reasoning), so the default 25 caps the run at ~4 iterations and it dies
--      'max_loops_reached' mid-flight. GetOptReport / GetAbResult now block
--      server-side (each async wait costs ~1 loop, not dozens), but the per-
--      iteration cost still warrants a larger budget.
--
--   3. max_duration_seconds → 1800 (30 min) — a full no-anchor iteration blocks on
--      opt-report annotation/aggregation (~2-3 min) + a fresh A/B with no cached
--      baseline (~5-13 min), so a single maxIter=1 run can exceed the 10-min
--      engine default and trip 'duration_exceeded' before RecordIteration.
--
-- config tweaks (#2/#3) live in t_agent.config and are read per-run, so they could
-- also be tuned by direct SQL without a migration; they are captured here so a
-- fresh install reproduces the working configuration. All statements are
-- idempotent (guarded) — safe to re-run / flyway-repair.

-- 1. prompt: thread autoApprove into the RunWorkflow('opt-report') call
UPDATE t_agent
SET system_prompt = replace(
        system_prompt,
        'args={agentId: <targetAgentId>}',
        'args={agentId: <targetAgentId>, autoApprove: true}')
WHERE name = 'evolve-orchestrator'
  AND system_prompt LIKE '%args={agentId: <targetAgentId>}%';

-- 2 + 3. config: larger agent-loop + wall-clock budgets (merge, preserve the rest)
UPDATE t_agent
SET config = (config::jsonb || '{"max_loops": 60, "max_duration_seconds": 1800}'::jsonb)::text
WHERE name = 'evolve-orchestrator'
  AND ( (config::jsonb ? 'max_loops') IS NOT TRUE
        OR (config::jsonb ? 'max_duration_seconds') IS NOT TRUE );

UPDATE t_agent SET updated_at = NOW() WHERE name = 'evolve-orchestrator';
