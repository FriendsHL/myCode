-- V130__add_evolve_loop_kind.sql
--
-- AUTOEVOLVE-AGENT-FLYWHEEL Module C — the agent-driven auto-evolving loop
-- persists its run into t_flywheel_run with loop_kind='evolve'
-- (EvolveController creates the run; each iteration is a t_flywheel_run_step
-- with step_kind='evolve_iteration'). The V126 CHECK constraint
-- (chk_flywheel_run_loop_kind) allowed
-- opt_report / memory_curation / attribution / metrics_collection / custom /
-- workflow, which rejected the new value. Extend the allow-list with 'evolve'.
--
-- Mirrors V126__add_workflow_loop_kind.sql exactly: DROP CONSTRAINT IF EXISTS +
-- ADD CONSTRAINT with the full allow-list (carrying every previously-allowed
-- value forward) plus the new 'evolve' value.
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
                             'metrics_collection', 'custom', 'workflow', 'evolve'));
