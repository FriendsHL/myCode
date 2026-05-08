-- SKILL-EVOLVE-LOOP V63: t_skill_eval_history
-- Stores per-skill EVAL run results (manual or scheduled) so SkillSelfImproveLoop
-- can decide whether a skill drifted below threshold and needs a self-improve cycle.
-- One row per (skillId, eval run) — composite_score is mandatory; the 4 dim scores
-- are nullable for legacy rows / future EvalScoreFormula refactors.

CREATE TABLE t_skill_eval_history (
    id BIGSERIAL PRIMARY KEY,
    skill_id BIGINT NOT NULL,
    eval_run_id VARCHAR(64),
    composite_score DOUBLE PRECISION NOT NULL,
    quality_score DOUBLE PRECISION,
    efficiency_score DOUBLE PRECISION,
    latency_score DOUBLE PRECISION,
    cost_score DOUBLE PRECISION,
    triggered_by VARCHAR(16) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_seh_triggered_by CHECK (triggered_by IN ('manual','scheduled'))
);

-- INV-8: idx for findFirstBySkillIdOrderByCreatedAtDesc + countBySkillIdAndCreatedAtAfter (7d skip).
CREATE INDEX idx_seh_skill_created ON t_skill_eval_history(skill_id, created_at DESC);

COMMENT ON TABLE t_skill_eval_history IS
  'P0 SKILL-EVOLVE-LOOP: skill EVAL 历史，self-improve 取 latest_score 判断是否触发 evolve';
