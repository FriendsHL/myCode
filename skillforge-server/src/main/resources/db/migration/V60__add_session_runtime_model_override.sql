-- V60__add_session_runtime_model_override.sql — P10 slash commands MVP
--
-- Adds session-scoped model override field set by `/model <modelId>` slash command.
-- ChatService.runLoop reads this BEFORE falling back to agent.modelId, so a
-- per-session model switch does NOT mutate t_agent.model_id (INV-4).
--
-- Default null = use agent.modelId (preserves existing behaviour for all current sessions).

ALTER TABLE t_session ADD COLUMN runtime_model_override VARCHAR(64);
COMMENT ON COLUMN t_session.runtime_model_override IS
  'P10: session-scoped model override set by /model command. NULL = use agent.modelId.';
