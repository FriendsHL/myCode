-- V74__create_session_annotation_and_pattern.sql — PROD-LABEL-CLUSTER V1 schema.
--
-- Three tables backing the production-session annotation + clustering pipeline
-- (signal + LLM stages writing to t_session_annotation; bucket clustering
-- materialising into t_session_pattern + t_pattern_session_member).
--
-- Spec: docs/requirements/active/PROD-LABEL-CLUSTER/tech-design.md §2.1
-- Phase: 1.1 (DB schema + Entity + Repository + IT)
--
-- Schema decisions (vs tech-design.md):
--   * TIMESTAMPTZ (not TIMESTAMP) for all time columns so Hibernate {@link Instant}
--     round-trips cleanly. Tech-design §2.1 says TIMESTAMP "与现有迁移风格一致";
--     V70 (chat_attachments) already moved newer tables to TIMESTAMPTZ explicitly
--     for Instant roundtrip. Following the V70+ convention. (Reported to plan.)
--   * BIGINT GENERATED ALWAYS AS IDENTITY (matches tech-design.md §2.1 verbatim;
--     also matches the only style currently used in code via IDENTITY columns).
--   * t_session_annotation.session_id is VARCHAR(36) (matches t_session.id) but
--     intentionally has NO FK to t_session — see tech-design.md §2.1 注释.
--   * t_pattern_session_member.pattern_id has FK ON DELETE CASCADE to
--     t_session_pattern(id) so deleting a pattern auto-purges its membership rows.
--
-- Idempotency: this migration creates three brand-new tables. It is not
-- protected by IF NOT EXISTS — Flyway version uniqueness handles re-runs.

CREATE TABLE t_session_annotation (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    session_id          VARCHAR(36)  NOT NULL,
    annotation_type     VARCHAR(32)  NOT NULL,
    annotation_value    VARCHAR(64)  NOT NULL,
    source              VARCHAR(16)  NOT NULL,
    confidence          DECIMAL(3,2) NOT NULL DEFAULT 1.00,
    reasoning           TEXT,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_session_annotation
        UNIQUE (session_id, annotation_type, annotation_value, source)
);
CREATE INDEX idx_session_annotation_session
    ON t_session_annotation(session_id);
CREATE INDEX idx_session_annotation_type_value
    ON t_session_annotation(annotation_type, annotation_value);
CREATE INDEX idx_session_annotation_created
    ON t_session_annotation(created_at);

CREATE TABLE t_session_pattern (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    signature           VARCHAR(256) NOT NULL UNIQUE,
    outcome             VARCHAR(32)  NOT NULL,
    suspect_surface     VARCHAR(32)  NOT NULL,
    top_failing_tool    VARCHAR(128),
    agent_id            BIGINT,
    member_count        INT          NOT NULL DEFAULT 0,
    suggested_surface   VARCHAR(32),
    first_seen_at       TIMESTAMPTZ  NOT NULL,
    last_seen_at        TIMESTAMPTZ  NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_session_pattern_outcome
    ON t_session_pattern(outcome);
CREATE INDEX idx_session_pattern_agent
    ON t_session_pattern(agent_id);
CREATE INDEX idx_session_pattern_last_seen
    ON t_session_pattern(last_seen_at);

CREATE TABLE t_pattern_session_member (
    pattern_id  BIGINT      NOT NULL REFERENCES t_session_pattern(id) ON DELETE CASCADE,
    session_id  VARCHAR(36) NOT NULL,
    added_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (pattern_id, session_id)
);
CREATE INDEX idx_pattern_member_session
    ON t_pattern_session_member(session_id);
