-- V80__create_optimization_event.sql — V3 ATTRIBUTION-AGENT Phase 1.1 schema.
--
-- One table that materializes the V3 飞轮因果链 (pattern → proposal →
-- candidate → A/B → canary → final stage). Per requirements
-- docs/requirements/active/ATTRIBUTION-AGENT/tech-design.md §2 and
-- prd.md §功能需求 §1, t_optimization_event records every state transition
-- attribution-curator + AttributionApprovalService + downstream pipelines
-- drive for a single (pattern_id, agent_id) optimization attempt.
--
-- Spec: docs/requirements/active/ATTRIBUTION-AGENT/tech-design.md §2.V80
-- Phase: 1.1 (DB schema + Entity + Repository + JPA IT)
--
-- Schema decisions:
--   * TIMESTAMPTZ for all time columns (V70+ convention, Hibernate Instant
--     round-trips cleanly — same rule as V74 / V77).
--   * BIGINT GENERATED ALWAYS AS IDENTITY (matches V74 / V77 style).
--   * FK pattern_id → t_session_pattern(id) ON DELETE CASCADE so dropping
--     a pattern (e.g. cluster reseed) also purges its optimization history.
--     ratify locked: the pattern is the unit of dedupe so its events
--     don't outlive it. agent_id is intentionally NOT a FK — V1 V74
--     already keeps agent_id as a soft reference; consistency wins.
--   * surface_type VARCHAR(32) — values 'skill' / 'prompt' (V3 scope per
--     ratify #6); 'behavior_rule' (V4) / 'other' / 'unclear' are NOT
--     auto-dispatched but the column accepts them for forward compat.
--   * change_type VARCHAR(64) — free-form from the attribution-curator
--     LLM (rewrite_skill_md / tune_prompt / add_constraint / ...).
--   * description / expected_impact TEXT — agent-authored prose; will be
--     surfaced in dashboard Pending Approvals queue + Timeline view.
--   * confidence DECIMAL(3,2) + CHECK 0..1 — the agent rejects its own
--     proposal at <0.5 (writes stage=proposal_rejected reason='low_confidence');
--     the column tracks the actual self-scored confidence for the audit row.
--   * risk VARCHAR(16) — 'low' / 'medium' / 'high' (free string for V3,
--     can become enum check constraint when downstream consumers grow).
--   * stage VARCHAR(32) — the workflow state machine (proposal_pending →
--     proposal_approved / proposal_rejected → candidate_created → ab_running →
--     ab_passed / ab_failed → canary_started → promoted / rolled_back →
--     verified). All stage transitions land in
--     OptimizationEventService (Phase 1.3).
--   * candidate_skill_id / candidate_prompt_version_id BIGINT NULL — populated
--     when surface=skill / surface=prompt respectively; the two columns are
--     intentionally split rather than polymorphic FK so each path has a
--     clean type-safe link (mirrors how V77 split baseline/candidate
--     skill names).
--   * ab_run_id BIGINT NULL — Long pointer into SkillAbRunEntity or
--     PromptAbRunEntity. Same polymorphic compromise as ab_run_id in V1
--     SkillImprovement: callers know which surface drives it via
--     surface_type.
--   * canary_id BIGINT NULL — link to V2 t_canary_rollout(id). Not a FK
--     (would couple V3 to V2 deletion semantics; today V2 rows are not
--     deleted, but soft contract is enough).
--   * attribution_session_id VARCHAR(36) — the attribution-curator session
--     id (matches t_session.id length 36 UUID). Lets the dashboard "view
--     the curator's reasoning" link land on the existing chat replay UI.
--   * cooldown_expires_at TIMESTAMPTZ — 24h cooldown anchor per
--     prd.md ratify #2. Dispatcher's pattern eligibility filter excludes
--     any pattern whose latest event has cooldown_expires_at > NOW().
--     Partial index speeds that scan up at the cost of an extra btree.
--   * created_at / updated_at TIMESTAMPTZ DEFAULT NOW() — same audit
--     pattern as V77; @EntityListeners(AuditingEntityListener.class) is
--     intentionally NOT used because the audit-listener footgun rewrites
--     these columns when JPA save runs after V81 seed boot (V1 V69 lessons).
--
-- Indexing strategy:
--   * idx_optimization_event_pattern (pattern_id, stage) — drives
--     "latest event per pattern" cooldown lookup + per-pattern timeline.
--   * idx_optimization_event_agent (agent_id, stage) — Pending Approvals
--     view is "per agent in stage=proposal_pending"; covers the
--     dashboard query.
--   * idx_optimization_event_stage_time (stage, created_at DESC) — global
--     Timeline view sorted by recency, e.g. "all proposal_pending newest".
--   * idx_optimization_event_cooldown (cooldown_expires_at) WHERE NOT NULL —
--     partial because the column is NULL for events past cooldown / for
--     stages where cooldown doesn't apply. Speeds AttributionDispatcherService
--     scan (Phase 1.2).
--
-- Idempotency: brand-new table. Not protected by IF NOT EXISTS — Flyway
-- version uniqueness handles re-runs.

CREATE TABLE t_optimization_event (
    id                              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    pattern_id                      BIGINT       NOT NULL
        REFERENCES t_session_pattern(id) ON DELETE CASCADE,
    agent_id                        BIGINT       NOT NULL,
    surface_type                    VARCHAR(32)  NOT NULL,
    change_type                     VARCHAR(64),
    description                     TEXT,
    expected_impact                 TEXT,
    confidence                      DECIMAL(3,2)
        CHECK (confidence >= 0 AND confidence <= 1),
    risk                            VARCHAR(16),
    stage                           VARCHAR(32)  NOT NULL,
    candidate_skill_id              BIGINT,
    candidate_prompt_version_id     BIGINT,
    ab_run_id                       BIGINT,
    canary_id                       BIGINT,
    attribution_session_id          VARCHAR(36),
    cooldown_expires_at             TIMESTAMPTZ,
    created_at                      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at                      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_optimization_event_pattern
    ON t_optimization_event(pattern_id, stage);

CREATE INDEX idx_optimization_event_agent
    ON t_optimization_event(agent_id, stage);

CREATE INDEX idx_optimization_event_stage_time
    ON t_optimization_event(stage, created_at DESC);

CREATE INDEX idx_optimization_event_cooldown
    ON t_optimization_event(cooldown_expires_at)
    WHERE cooldown_expires_at IS NOT NULL;
