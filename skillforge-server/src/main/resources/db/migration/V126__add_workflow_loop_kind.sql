-- V126__add_workflow_loop_kind.sql
--
-- AUTOEVOLVING V1 — Sprint 1: the DSL workflow runtime persists its runs into
-- t_flywheel_run with loop_kind='workflow' (WorkflowRunnerService.startRun).
-- The V124 CHECK constraint (chk_flywheel_run_loop_kind) only allowed
-- opt_report / memory_curation / attribution / metrics_collection / custom,
-- which rejected the new value. Extend the allow-list with 'workflow'.
--
-- Additive + backward compatible: no existing row changes, no column changes.
--
-- Locking note (db W1): each ALTER TABLE ... DROP/ADD CONSTRAINT takes a brief
-- ACCESS EXCLUSIVE lock on t_flywheel_run. The CHECK re-validation scans existing
-- rows, so on a very large table this blocks concurrent writes for the scan
-- duration. t_flywheel_run is low-volume (one row per loop run), so the window is
-- negligible here. DROP uses IF EXISTS for idempotency (re-runnable on a partially
-- migrated DB / repeatable flyway repair).

ALTER TABLE t_flywheel_run
    DROP CONSTRAINT IF EXISTS chk_flywheel_run_loop_kind;

ALTER TABLE t_flywheel_run
    ADD CONSTRAINT chk_flywheel_run_loop_kind
        CHECK (loop_kind IN ('opt_report', 'memory_curation', 'attribution',
                             'metrics_collection', 'custom', 'workflow'));
