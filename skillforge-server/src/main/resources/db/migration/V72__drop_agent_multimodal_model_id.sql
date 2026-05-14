-- V72__drop_agent_multimodal_model_id.sql — MULTIMODAL-MVP Phase 1 redesign.
--
-- Revert V71's t_agent.multimodal_model_id column. Phase 1 originally introduced
-- a separate field so an agent could keep a text-only main model and a vision
-- model just for image/PDF turns. Real-world feedback was that the two-picker
-- UX adds friction without enough payoff — users would rather just switch the
-- agent's main model to a vision-capable one when they want to upload.
--
-- The replacement design: single agent.model_id; FE picker tags vision-capable
-- models with a "多模态" chip; upload button gates on whether agent.model_id is
-- in LlmProperties.supportsVision(); ChatService keeps MultimodalNoVisionException
-- as defense-in-depth only (no effective-model switching).

ALTER TABLE t_agent DROP COLUMN IF EXISTS multimodal_model_id;
