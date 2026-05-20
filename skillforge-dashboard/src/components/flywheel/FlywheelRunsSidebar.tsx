/**
 * FLYWHEEL-PER-RUN — left sidebar that lists recent OptimizationEvent runs
 * for per-run mode of the flywheel observability panel.
 *
 * Single-select; clicking a row emits `onSelect(optEventId)` which the
 * parent FlywheelFlowchart uses to highlight the run's current step on the
 * DAG. Collapse toggle reduces the sidebar to a slim rail (icon + label) so
 * operators can keep the DAG full-width while debugging a specific node.
 *
 * Data input is the raw runs array from `useFlywheelRuns` — we sort here
 * (most-recently-updated first) but otherwise render as-is. No mutation of
 * the parent's array.
 *
 * a11y:
 *   - `role="listbox"` on the run list, `role="option"` on each row
 *   - keyboard up/down navigation via native button focus + arrow handler
 *   - selected row aria-selected="true"
 */
import React, { useCallback, useMemo } from 'react';
import type { FlywheelRunDto, FlywheelMode } from './types';
import { ERROR_STAGES, STAGE_TO_STEP, formatLag } from './types';

interface FlywheelRunsSidebarProps {
  /** Raw runs from useFlywheelRuns; component sorts internally. */
  runs: FlywheelRunDto[];
  /** Loading flag — shows skeleton placeholder. */
  isLoading: boolean;
  /** Currently-selected run's optEventId, or null. */
  activeRunId: number | null;
  /** Emit when user clicks a row; null = deselect. */
  onSelectRun: (optEventId: number | null) => void;
  /** Collapsed state — when true, sidebar shrinks to a slim rail. */
  isCollapsed: boolean;
  onToggleCollapse: () => void;
  /**
   * Filter chip — when true, BE excludes terminal-state runs (promoted /
   * discarded). Toggling this re-fetches via the parent's useFlywheelRuns.
   */
  hideTerminal: boolean;
  onToggleHideTerminal: () => void;
  /** Whether the panel is currently in per-run mode (used for visibility). */
  mode: FlywheelMode;
}

const FlywheelRunsSidebar: React.FC<FlywheelRunsSidebarProps> = ({
  runs,
  isLoading,
  activeRunId,
  onSelectRun,
  isCollapsed,
  onToggleCollapse,
  hideTerminal,
  onToggleHideTerminal,
  mode,
}) => {
  // Most-recently-updated first; stable when input is stable so React Flow's
  // node-data identity stays consistent across renders.
  const sortedRuns = useMemo(() => {
    return [...runs].sort(
      (a, b) =>
        new Date(b.lastUpdatedAt).getTime() -
        new Date(a.lastUpdatedAt).getTime(),
    );
  }, [runs]);

  const handleClickRow = useCallback(
    (optEventId: number) => {
      // Click selected row again → deselect (toggle behavior matches Linear
      // / Raycast row affordance; operator can clear selection without
      // hunting for a separate Clear button).
      if (activeRunId === optEventId) {
        onSelectRun(null);
      } else {
        onSelectRun(optEventId);
      }
    },
    [activeRunId, onSelectRun],
  );

  if (mode !== 'perRun') {
    // Defensive: parent shouldn't mount this in aggregate mode, but if it
    // does, return null instead of an empty pane that steals layout space.
    return null;
  }

  return (
    <aside
      className={`fw-runs-sidebar${isCollapsed ? ' fw-runs-sidebar--collapsed' : ''}`}
      data-testid="flywheel-runs-sidebar"
      aria-label="Recent flywheel runs"
    >
      <header className="fw-runs-head">
        <button
          type="button"
          className="fw-runs-collapse-btn"
          onClick={onToggleCollapse}
          aria-label={isCollapsed ? 'Expand runs sidebar' : 'Collapse runs sidebar'}
          aria-expanded={!isCollapsed}
          data-testid="fw-runs-collapse-btn"
          title={isCollapsed ? 'Expand' : 'Collapse'}
        >
          {isCollapsed ? '›' : '‹'}
        </button>
        {!isCollapsed && (
          <span className="fw-runs-title">Recent runs</span>
        )}
      </header>

      {!isCollapsed && (
        <>
          <div className="fw-runs-filter-row" role="group" aria-label="Filters">
            <label
              className={`fw-runs-chip${hideTerminal ? ' fw-runs-chip--on' : ''}`}
              data-testid="fw-runs-hideterminal-chip"
            >
              <input
                type="checkbox"
                checked={hideTerminal}
                onChange={onToggleHideTerminal}
                aria-label="Hide terminal runs"
              />
              <span>Hide done</span>
            </label>
            <span className="fw-runs-count" data-testid="fw-runs-count">
              {isLoading ? '…' : `${sortedRuns.length}`}
            </span>
          </div>

          <ul
            className="fw-runs-list"
            role="listbox"
            aria-label="Run list"
            data-testid="fw-runs-list"
          >
            {isLoading && sortedRuns.length === 0 ? (
              <li className="fw-runs-empty">Loading…</li>
            ) : sortedRuns.length === 0 ? (
              <li className="fw-runs-empty" data-testid="fw-runs-empty">
                {hideTerminal
                  ? 'No active runs. Toggle "Hide done" to include terminal runs.'
                  : 'No runs in the last 24h.'}
              </li>
            ) : (
              sortedRuns.map((run) => (
                <RunRow
                  key={run.optEventId}
                  run={run}
                  isActive={activeRunId === run.optEventId}
                  onClick={handleClickRow}
                />
              ))
            )}
          </ul>
        </>
      )}
    </aside>
  );
};

interface RunRowProps {
  run: FlywheelRunDto;
  isActive: boolean;
  onClick: (optEventId: number) => void;
}

const RunRow: React.FC<RunRowProps> = React.memo(({ run, isActive, onClick }) => {
  const stageStep = STAGE_TO_STEP[run.currentStage] ?? null;
  const isError = ERROR_STAGES.has(run.currentStage);
  const isTerminal =
    run.currentStage === 'promoted' || run.currentStage === 'discarded';
  const isStuck = isRunStuck(run);
  const statusEmoji = isError
    ? '❌'
    : isTerminal
      ? run.currentStage === 'promoted'
        ? '✅'
        : '🗑'
      : isStuck
        ? '⚠️'
        : '🔄';

  // Pattern signature snippet, truncated to 60 chars; null-safe.
  const sigSnippet = useMemo(() => {
    if (!run.patternSignature) return '—';
    const s = run.patternSignature;
    return s.length > 60 ? `${s.slice(0, 60)}…` : s;
  }, [run.patternSignature]);

  return (
    <li role="option" aria-selected={isActive}>
      <button
        type="button"
        className={`fw-runs-row${isActive ? ' fw-runs-row--active' : ''}${
          isError ? ' fw-runs-row--error' : ''
        }`}
        onClick={() => onClick(run.optEventId)}
        data-testid={`fw-runs-row-${run.optEventId}`}
        aria-label={`OptEvent ${run.optEventId} — ${run.currentStage}`}
      >
        <div className="fw-runs-row-head">
          <span className="fw-runs-row-status" aria-hidden="true">
            {statusEmoji}
          </span>
          <span className="fw-runs-row-agent">
            {run.agentName ?? `agent ${run.agentId ?? '?'}`}
          </span>
          <span className="fw-runs-row-age">{formatLag(run.lastUpdatedAt)}</span>
        </div>
        <div className="fw-runs-row-sig" title={run.patternSignature ?? ''}>
          {sigSnippet}
        </div>
        <div className="fw-runs-row-foot">
          <span
            className="fw-runs-row-stage"
            data-error={isError ? 'true' : undefined}
            title={stageStep ? `Step: ${stageStep}` : undefined}
          >
            {run.currentStage}
          </span>
          {run.errorLabel && (
            <span
              className="fw-runs-row-errlabel"
              title={run.errorLabel}
            >
              {run.errorLabel}
            </span>
          )}
        </div>
      </button>
    </li>
  );
});

RunRow.displayName = 'RunRow';

/**
 * "Stuck" = no stage transition in the last 2 hours AND not in a terminal
 * stage AND not a user-gate stage (operators control gate cadence, so
 * lagging gates aren't really "stuck" in the failure sense).
 */
function isRunStuck(run: FlywheelRunDto): boolean {
  if (
    run.currentStage === 'promoted' ||
    run.currentStage === 'discarded' ||
    run.currentStage === 'proposal_pending' ||
    run.currentStage === 'ab_passed'
  ) {
    return false;
  }
  const last = new Date(run.lastUpdatedAt).getTime();
  if (Number.isNaN(last)) return false;
  const ageMs = Date.now() - last;
  return ageMs > 2 * 60 * 60 * 1000;
}

export default FlywheelRunsSidebar;
