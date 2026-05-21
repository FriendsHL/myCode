-- V94__update_attribution_dispatcher_tool_ids.sql
-- DISPATCHER-ORCHESTRATOR-REFACTOR r2 fix (BLOCKER B2):
--
-- V93 seeded attribution-dispatcher with the old monolithic Tool
-- `DispatchAttributionPatterns` (Tool body did filter + fan-out via Java
-- service). This refactor splits that into:
--   - ListAttributionCandidates  (new Tool: side-effect-free filter + sentinel reserve)
--   - SubAgent                   (existing system Tool: per-pattern fan-out, LLM decides)
--
-- The DispatchAttributionPatternsTool class itself is deleted in this
-- refactor. Fresh installs running V93 would land tool_ids =
-- ["DispatchAttributionPatterns"] and AgentLoop.toolAllowlist would block
-- ListAttributionCandidates + SubAgent → dispatcher LLM cannot call any
-- tool → entire attribution loop dead.
--
-- AttributionDispatcherBootstrap only swaps system_prompt (not tool_ids /
-- config), so it cannot self-heal this. V94 patches both:
--   * top-level tool_ids column (V81 column-based filtering compatibility)
--   * config JSON inner tool_ids (ChatService.java:742 actual runtime read path)
--   * config maxTokens 1024 → 4096 (new LLM-as-orchestrator loop needs more
--     budget: 1 ListCandidates call + N SubAgent calls + 1 summary JSON)
--
-- Idempotent on rerun: UPDATE is no-op if values already match (existing
-- install was hand-patched via psql before V94 landed — V94 overwrites
-- with identical values, no harm).

UPDATE t_agent
SET
    tool_ids   = '["ListAttributionCandidates","SubAgent"]',
    config     = '{"maxTokens": 4096, "temperature": 0.0, "execution_mode": "auto", "tool_ids": ["ListAttributionCandidates","SubAgent"]}',
    updated_at = NOW()
WHERE name = 'attribution-dispatcher';
