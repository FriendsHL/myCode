-- MEMORY-LLM-SYNTHESIS — LLM-driven memory dedup/reflection/optimize via proposal table.
--
-- All LLM output flows through t_memory_proposal (status='proposed') and only mutates
-- t_memory after human approve. No LLM-driven delete path exists. See tech-design.md.
--
-- B-1..B-5 fixes applied; W-1..W-10 + N-1..N-5 fixes applied; F-N1..F-N4 nit follow-ups
-- applied at code level (not schema-level).
--
-- Note: this is V68 (NOT V67) — V67__drop_trace_span.sql already occupies V67.

CREATE TABLE IF NOT EXISTS t_memory_proposal (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    synthesis_run_id VARCHAR(64) NOT NULL,
    proposal_type VARCHAR(16) NOT NULL,
    source_memory_ids JSONB NOT NULL,
    winner_memory_id BIGINT,
    suggested_title VARCHAR(256),
    suggested_content TEXT,
    suggested_importance VARCHAR(16),
    reasoning VARCHAR(256),
    llm_prompt_hash VARCHAR(64),
    llm_response_excerpt TEXT,
    status VARCHAR(16) NOT NULL DEFAULT 'proposed',
    reviewed_by_user_id BIGINT,
    reviewed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    -- W-1 fix: generated column — caller can't miscompute the auto-archive deadline
    auto_archive_after TIMESTAMP GENERATED ALWAYS AS (created_at + INTERVAL '7 days') STORED,
    CONSTRAINT chk_proposal_type CHECK (proposal_type IN ('dedup','reflection','optimize','contradiction')),
    CONSTRAINT chk_proposal_status CHECK (status IN ('proposed','approved','rejected','auto_archived','stale'))
);

COMMENT ON TABLE  t_memory_proposal IS
  'MEMORY-LLM-SYNTHESIS: LLM-generated memory edit proposals awaiting human review.';
COMMENT ON COLUMN t_memory_proposal.proposal_type IS
  'dedup | reflection | optimize | contradiction — strict enum, parser rejects unknown.';
COMMENT ON COLUMN t_memory_proposal.source_memory_ids IS
  'JSON array of bigint memory IDs the LLM cited as input. dedup requires size in [2,5].';
COMMENT ON COLUMN t_memory_proposal.winner_memory_id IS
  'dedup: required (the winning memory id); contradiction: NULL until user picks via UI; '
  'reflection/optimize: NULL.';
COMMENT ON COLUMN t_memory_proposal.auto_archive_after IS
  'Generated: created_at + 7 days. auto-archive-stale endpoint flips status to auto_archived.';

-- B-5 fix: 4 indexes covering common query paths
CREATE INDEX IF NOT EXISTS idx_proposal_user_status
    ON t_memory_proposal(user_id, status);
CREATE INDEX IF NOT EXISTS idx_proposal_user_created
    ON t_memory_proposal(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_proposal_synthesis_run
    ON t_memory_proposal(synthesis_run_id);
CREATE INDEX IF NOT EXISTS idx_proposal_source_memory_ids_gin
    ON t_memory_proposal USING GIN (source_memory_ids jsonb_path_ops);

-- B-2 fix: memory_kind (not memory_type — t_memory.type already exists as business taxonomy)
ALTER TABLE t_memory
    ADD COLUMN IF NOT EXISTS memory_kind VARCHAR(16) DEFAULT 'observation',
    ADD COLUMN IF NOT EXISTS derived_from_memory_ids JSONB,
    ADD COLUMN IF NOT EXISTS original_content TEXT,
    ADD COLUMN IF NOT EXISTS synthesis_run_id VARCHAR(64);

COMMENT ON COLUMN t_memory.memory_kind IS
  'MEMORY-LLM-SYNTHESIS: observation (default) / reflection (LLM-synthesized) / optimized '
  '(LLM-rewritten). Orthogonal to t_memory.type which is the business taxonomy '
  '(preference/feedback/knowledge/project/reference).';
COMMENT ON COLUMN t_memory.derived_from_memory_ids IS
  'MEMORY-LLM-SYNTHESIS: JSON array of source memory ids; only set for memory_kind=reflection.';
COMMENT ON COLUMN t_memory.original_content IS
  'MEMORY-LLM-SYNTHESIS: pre-optimize content; supports revert path for memory_kind=optimized.';
COMMENT ON COLUMN t_memory.synthesis_run_id IS
  'MEMORY-LLM-SYNTHESIS: links the memory back to the synthesis run; set by approve for all '
  'three proposal types (dedup/reflection/optimize). W-6 fix.';
