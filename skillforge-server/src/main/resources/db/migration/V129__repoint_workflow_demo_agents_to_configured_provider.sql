-- V129__repoint_workflow_demo_agents_to_configured_provider.sql
--
-- AUTOEVOLVING V1 — fix discovered during Sprint 4 end-to-end testing.
--
-- V128 seeded the two opt-report DSL demo agents (opt-report-orchestrator /
-- opt-report-aggregator) with model_id 'claude:claude-sonnet-4-20250514'. The
-- reference deployment, however, only has a xiaomi-mimo key configured
-- (ANTHROPIC_API_KEY is blank → the claude provider is skipped at startup,
-- see SkillForgeConfig#llmProviderFactory). Running the opt-report workflow
-- therefore failed at the first agent() call.
--
-- Companion core fix (AgentLoopEngine.resolveProvider): an explicitly-named but
-- unconfigured provider now fails fast with a clear error instead of silently
-- handing the default provider a foreign, prefixed model name (which xiaomi-mimo
-- mis-reports as HTTP 401 "Invalid API Key"). With that fail-fast in place these
-- demo agents MUST point at a configured provider, so repoint them to the
-- deployment's default provider/model (xiaomi-mimo:mimo-v2.5-pro, the current
-- usable provider per application.yml default-provider).
--
-- Idempotent: the WHERE guard only touches rows still on the V128 seed value, so
-- re-running (flyway repair) or running against a DB where an operator has
-- already changed the model is a no-op. A deployment that DOES have an Anthropic
-- key can set these two agents back to a claude model via the dashboard.

UPDATE t_agent
SET model_id = 'xiaomi-mimo:mimo-v2.5-pro'
WHERE name IN ('opt-report-orchestrator', 'opt-report-aggregator')
  AND model_id = 'claude:claude-sonnet-4-20250514';
