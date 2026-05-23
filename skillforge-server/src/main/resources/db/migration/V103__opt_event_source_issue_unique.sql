-- OPT-REPORT-V1.2 r2 fix (2026-05-23, reviewer W1):
-- Application-level idempotency in OptReportToEventBridge does check-then-insert
-- inside @Transactional but without a DB-level guard. Two concurrent operator
-- "Convert to Event" clicks could both pass the check and both INSERT.
--
-- Adds UNIQUE on (source_report_id, source_issue_id) so duplicate inserts fail
-- at the DB layer (PostgreSQL treats NULL values as distinct → existing rows
-- with NULL source_report_id are unaffected, only report-derived rows get
-- constrained).
--
-- The non-unique partial composite index idx_opt_event_source_report_issue
-- (V101) is intentionally kept — the UNIQUE constraint creates its own
-- implicit unique index, but the partial one is also useful for the soft
-- "find by report_id without issue_id" path. Postgres query planner will
-- pick whichever is best.

ALTER TABLE t_optimization_event
    ADD CONSTRAINT uq_opt_event_source_issue
    UNIQUE (source_report_id, source_issue_id);
