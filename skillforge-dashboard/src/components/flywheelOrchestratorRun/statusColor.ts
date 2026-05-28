/**
 * OPT-LOOP-FRAMEWORK Sprint 4 — shared status → color helper for the
 * `/flywheel-runs` page (FilterBar / RunListPanel / RunDetailPanel /
 * StepTimelinePanel all share the mapping so a `pending` row in the list
 * matches the `pending` dot on the timeline visually).
 *
 * Returns an Ant Design Tag / Timeline `color` string (not a CSS color):
 *   - `completed`            → green
 *   - `running`              → blue
 *   - `pending` / `queued`   → gold
 *   - `error` / `failed`     → red
 *   - `skipped` / `canceled` → default (gray)
 *   - anything else          → default
 *
 * `t_flywheel_run.status` historically holds `pending|running|completed|error`
 * (see V124 schema + FlywheelRunService.transitionStatus). Step rows reuse
 * the same vocab plus an occasional `skipped` for short-circuited fan-outs.
 */
export function statusColor(status: string | null | undefined): string {
  switch (status) {
    case 'completed':
      return 'green';
    case 'running':
      return 'blue';
    case 'pending':
    case 'queued':
      return 'gold';
    case 'error':
    case 'failed':
      return 'red';
    case 'skipped':
    case 'canceled':
    case 'cancelled':
      return 'default';
    default:
      return 'default';
  }
}

/**
 * `true` when the row is still in motion (FE keeps polling). Terminal states
 * stop polling — see useFlywheelOrchestratorRuns.refetchInterval.
 */
export function isNonTerminal(status: string | null | undefined): boolean {
  return status === 'pending' || status === 'queued' || status === 'running';
}
