-- V59__create_scheduled_tasks.sql — P12 user-type scheduled tasks
--
-- Two tables:
--   * t_scheduled_task     — declarative spec (name / cron|one-shot / agent / channel target)
--   * t_scheduled_task_run — execution history (one row per trigger, success or skipped)
--
-- Key invariants enforced at schema layer (P12 brief §3 INV-3 / INV-4):
--   * cron_expr ⊻ one_shot_at         — exactly one of the two must be set
--   * concurrency_policy locked to    'skip-if-running' (MVP — V2 will widen)
--   * session_mode IN (new, reuse)
--   * status IN (idle, running, completed, error)
--   * channel_target stored as TEXT (JSON string), parsed in service layer
--     (matches existing project convention — see EvalScenarioEntity comment)
--
-- Indexes:
--   * idx_st_creator_enabled   — list-my-tasks queries
--   * idx_st_next_fire         — partial index on enabled rows for the scheduler's
--                                "what fires next" lookup
--   * idx_str_task_triggered   — DESC on triggered_at for /runs?limit=N

CREATE TABLE IF NOT EXISTS t_scheduled_task (
    id                  BIGSERIAL    PRIMARY KEY,
    name                VARCHAR(128) NOT NULL,
    creator_user_id     BIGINT       NOT NULL,
    agent_id            BIGINT       NOT NULL,
    cron_expr           VARCHAR(64),
    one_shot_at         TIMESTAMPTZ,
    timezone            VARCHAR(64)  NOT NULL DEFAULT 'Asia/Shanghai',
    prompt_template     TEXT         NOT NULL,
    session_mode        VARCHAR(16)  NOT NULL DEFAULT 'new',
    reused_session_id   VARCHAR(64),
    channel_target      TEXT,
    enabled             BOOLEAN      NOT NULL DEFAULT TRUE,
    concurrency_policy  VARCHAR(16)  NOT NULL DEFAULT 'skip-if-running',
    next_fire_at        TIMESTAMPTZ,
    last_fire_at        TIMESTAMPTZ,
    status              VARCHAR(16)  NOT NULL DEFAULT 'idle',
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_st_trigger_xor
        CHECK ((cron_expr IS NULL) <> (one_shot_at IS NULL)),
    CONSTRAINT chk_st_concurrency_policy
        CHECK (concurrency_policy = 'skip-if-running'),
    CONSTRAINT chk_st_session_mode
        CHECK (session_mode IN ('new', 'reuse')),
    CONSTRAINT chk_st_status
        CHECK (status IN ('idle', 'running', 'completed', 'error'))
);

CREATE INDEX IF NOT EXISTS idx_st_creator_enabled
    ON t_scheduled_task (creator_user_id, enabled);

CREATE INDEX IF NOT EXISTS idx_st_next_fire
    ON t_scheduled_task (next_fire_at)
    WHERE enabled = TRUE;

CREATE TABLE IF NOT EXISTS t_scheduled_task_run (
    id                    BIGSERIAL    PRIMARY KEY,
    task_id               BIGINT       NOT NULL,
    triggered_at          TIMESTAMPTZ  NOT NULL,
    finished_at           TIMESTAMPTZ,
    status                VARCHAR(16)  NOT NULL,
    error_message         TEXT,
    triggered_session_id  VARCHAR(64),
    manual                BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_str_task FOREIGN KEY (task_id)
        REFERENCES t_scheduled_task(id) ON DELETE CASCADE,
    CONSTRAINT chk_str_status
        CHECK (status IN ('running', 'success', 'failure', 'skipped', 'timeout', 'paused'))
);

CREATE INDEX IF NOT EXISTS idx_str_task_triggered
    ON t_scheduled_task_run (task_id, triggered_at DESC);
