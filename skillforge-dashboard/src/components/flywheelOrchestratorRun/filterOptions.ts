/**
 * OPT-LOOP-FRAMEWORK Sprint 4 — filter dropdown options for the
 * `/flywheel-runs` page. Extracted out of `FilterBar.tsx` so the
 * component file only exports its React component (W-FE-1 mandatory fix —
 * `react-refresh/only-export-components` ESLint rule requires this so Vite
 * Fast Refresh keeps working when constants and components live together).
 *
 * loopKind options are an open set on the BE — any value in
 * `t_flywheel_run.loop_kind` works. We expose the values the BE currently
 * emits (opt_report, memory_curation, attribution, subagent_dispatch_test);
 * Plan §2 D1 / FlywheelRunService.transitionStatus is the source of truth
 * for adding more.
 */

export const LOOP_KIND_OPTIONS = [
  { value: 'opt_report', label: 'opt_report' },
  { value: 'memory_curation', label: 'memory_curation' },
  { value: 'attribution', label: 'attribution' },
  { value: 'subagent_dispatch_test', label: 'subagent_dispatch_test' },
];

export const STATUS_OPTIONS = [
  { value: 'pending', label: 'pending' },
  { value: 'running', label: 'running' },
  { value: 'completed', label: 'completed' },
  { value: 'error', label: 'error' },
];
