-- SYSTEM-AGENT-TYPING Phase 1.1 (V89)
--
-- Distinguish system-managed agents (cron-driven, V1-V5 bootstrap-seeded:
-- memory-curator / session-annotator / metrics-collector /
-- attribution-curator / user-simulator) from user-created conversational
-- agents (Main Assistant / Design Agent / Research Agent / Code Agent /
-- Session Analyzer / future user-created agents).
--
-- See requirements/active/SYSTEM-AGENT-TYPING/{prd,tech-design}.md F1.
--
-- Why a new column instead of reusing owner_id / is_public:
--   owner_id is unreliable — V69 seeds memory-curator with owner_id=NULL,
--   V75/V79/V81/V85 seed with owner_id=1 (mixed). is_public is also
--   unreliable — users can create public agents. Explicit typing keeps
--   the semantics clear.
--
-- DEFAULT 'user' is intentional: every existing + future row defaults to
-- user agent semantics. The UPDATE below explicitly flips the 5 known
-- system agents to 'system'. New rows inserted by the user (via API /
-- import) stay 'user'. Bootstrap classes (Phase 1.1) self-heal newly
-- created system agent rows to 'system' on application start as a
-- defense-in-depth so future system agents don't depend on this UPDATE
-- knowing their name in advance.
--
-- Low risk: ALTER + UPDATE on t_agent (~10 rows in V1 dogfood). The
-- backfill is a single-statement UPDATE bounded by the name allow-list.

ALTER TABLE t_agent ADD COLUMN agent_type VARCHAR(16) NOT NULL DEFAULT 'user';
ALTER TABLE t_agent ADD CONSTRAINT chk_agent_type CHECK (agent_type IN ('user', 'system'));

UPDATE t_agent SET agent_type = 'system'
WHERE name IN (
    'memory-curator',
    'session-annotator',
    'metrics-collector',
    'attribution-curator',
    'user-simulator'
);
