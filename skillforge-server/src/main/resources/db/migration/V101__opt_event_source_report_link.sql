-- OPT-REPORT-V1.2 (2026-05-23): bridge "report issue" → t_optimization_event.
--
-- New columns on t_optimization_event:
--   * source_report_id VARCHAR(36) REFERENCES t_opt_report(id) ON DELETE SET NULL
--     — when operator clicks "Convert to Event" on a report issue, the new
--     OptEvent row carries the originating reportId. ON DELETE SET NULL so
--     deleting a report doesn't cascade-delete derived events (operator may
--     still want the proposal in the queue).
--   * source_issue_id VARCHAR(64) — the stable id from
--     summary_json.topIssues[i].id (e.g. "issue-1"). Paired with
--     source_report_id this is the idempotency key for re-clicks.
--
-- Partial index for reverse-lookup ("find all events derived from report R"):
-- only indexes the non-NULL rows since legacy attribution-curator events
-- (the vast majority at this stage) won't carry these columns.
--
-- ALTER pattern_id DROP NOT NULL — report-derived events have no pattern
-- (pattern is the V1 cluster concept; reports are MVP-first observability).
-- Backward compatible: every existing row has a non-null patternId, and
-- only the new convert path inserts null. The Entity field is already
-- declared `Long` (not primitive `long`) so no Java-side change needed.
--
-- Idempotency: Flyway baseline behavior — column-add steps don't have IF
-- NOT EXISTS, but Flyway version uniqueness handles re-runs. If a manual
-- restart is needed mid-migration, DROP COLUMN by hand and re-run.

ALTER TABLE t_optimization_event
    ADD COLUMN source_report_id VARCHAR(36)
        REFERENCES t_opt_report(id) ON DELETE SET NULL,
    ADD COLUMN source_issue_id  VARCHAR(64);

-- Partial index — most rows (legacy attribution-curator) won't carry these
-- columns, so we keep the index small and selective.
CREATE INDEX idx_opt_event_source_report
    ON t_optimization_event(source_report_id)
    WHERE source_report_id IS NOT NULL;

-- Composite index for the (reportId, issueId) idempotency lookup used by
-- OptReportToEventBridge.convertIssueToEvent. Partial — same reason.
CREATE INDEX idx_opt_event_source_report_issue
    ON t_optimization_event(source_report_id, source_issue_id)
    WHERE source_report_id IS NOT NULL;

-- pattern_id nullable for report-derived events. Existing data unaffected
-- (V80 INSERTs all carry patternId; V1.2 only writes null via the new
-- convert path).
ALTER TABLE t_optimization_event
    ALTER COLUMN pattern_id DROP NOT NULL;
