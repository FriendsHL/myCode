import React from 'react';
import type {
  FlywheelMode,
  FlywheelRunDto,
  StepDescriptor,
  StepMetrics,
} from './types';
import { computeHealth, formatLag, REJECTED_STAGES } from './types';

interface StepCardProps {
  step: StepDescriptor;
  metrics: StepMetrics;
  /**
   * Click handler — opens the detail Drawer in the parent Flowchart. Optional
   * because the card also renders inside the legacy timeline path
   * (none today, but kept for forward-compat). When omitted on a non-dormant
   * step, the card becomes purely presentational (no click cursor).
   */
  onSelect?: (step: StepDescriptor) => void;
  /** FLYWHEEL-PER-RUN — current view mode; controls metric-row content. */
  mode?: FlywheelMode;
  /** Per-run mode: this step is the active run's current location. */
  isCurrentForRun?: boolean;
  /** Per-run mode: this step is in the pre-OptEvent context set. */
  isContextForRun?: boolean;
  /** Per-run mode: the run already passed through this step. */
  isCompletedForRun?: boolean;
  /** Per-run mode: the run errored at this step. */
  isErrorForRun?: boolean;
  /** Per-run mode: the active run; null when no run selected. */
  activeRun?: FlywheelRunDto | null;
}

/**
 * FLYWHEEL-FLOWCHART — compact node body used inside React Flow nodes.
 *
 * Renders a `<button>` when `onSelect` is provided and step is not dormant
 * (keyboard Tab + Enter/Space triggers Drawer open via the parent). Renders
 * a plain `<div>` for dormant nodes (V87 disabled) so they stay inert.
 *
 * Read-only — PRD N5 forbids action buttons. The drill-down "open in page"
 * link lives in the detail Drawer footer, not on the card itself.
 */
const StepCard: React.FC<StepCardProps> = React.memo(
  ({
    step,
    metrics,
    onSelect,
    mode = 'aggregate',
    isCurrentForRun = false,
    isContextForRun = false,
    isCompletedForRun = false,
    isErrorForRun = false,
    activeRun = null,
  }) => {
    const health = computeHealth(step, metrics);
    const lag = formatLag(metrics.lastActivityAt);
    const isDormant = step.nodeType === 'dormant';
    const pend = metrics.pendingActionCount ?? 0;
    const isPerRun = mode === 'perRun';

    const inner = (
      <>
        <div className="fw-step-head">
          <span className="fw-step-icon" aria-hidden="true">
            {nodeIcon(step.nodeType)}
          </span>
          <span className="fw-step-title">{step.labelCn}</span>
          {/* Per-run mode swaps the health dot for a journey-position marker.
              Aggregate mode keeps the original health dot. */}
          {isPerRun ? (
            <RunPositionMark
              isCurrent={isCurrentForRun}
              isContext={isContextForRun}
              isCompleted={isCompletedForRun}
              isError={isErrorForRun}
              hasActiveRun={!!activeRun}
            />
          ) : (
            <span
              className="fw-health-dot"
              data-health={health}
              aria-label={`health: ${health}`}
              title={`health: ${health}`}
            >
              <span className="fw-health-letter" aria-hidden="true">
                {healthLetter(health)}
              </span>
            </span>
          )}
        </div>
        <div className="fw-step-metrics" data-testid={`metrics-${step.id}`}>
          {isPerRun ? (
            <PerRunMetricCells
              activeRun={activeRun}
              isCurrentForRun={isCurrentForRun}
              isContextForRun={isContextForRun}
              isCompletedForRun={isCompletedForRun}
            />
          ) : (
            <>
              <Metric label="in-flight" value={fmtCount(metrics, metrics.inFlight)} />
              <Metric
                label="lag"
                value={isDormant ? '—' : lag}
                muted={isDormant}
              />
            </>
          )}
        </div>
        <div className="fw-step-foot">
          {isPerRun && isContextForRun && (
            <span
              className="fw-step-chip-context"
              title="This step happens before the selected run was created."
              data-testid={`context-chip-${step.id}`}
            >
              context
            </span>
          )}
          {isPerRun && isCurrentForRun && activeRun && (
            <span
              className={`fw-step-chip-current${
                isErrorForRun
                  ? REJECTED_STAGES.has(activeRun.currentStage)
                    ? ' fw-step-chip-current--rejected'
                    : ' fw-step-chip-current--error'
                  : ''
              }`}
              data-testid={`current-chip-${step.id}`}
              title={`Run #${activeRun.optEventId} is at ${activeRun.currentStage}`}
            >
              {/* Distinguish operator-rejected (business outcome, amber)
                  from system-failed (needs investigation, red). Both fall
                  under ERROR_STAGES for highlight purposes but read very
                  differently for an operator triaging the panel. */}
              {isErrorForRun
                ? REJECTED_STAGES.has(activeRun.currentStage)
                  ? 'rejected here'
                  : 'failed here'
                : 'current'}
            </span>
          )}
          {!isPerRun && step.nodeType === 'user' && pend > 0 && (
            <span
              className="fw-step-chip-pend"
              data-testid={`pend-chip-${step.id}`}
              title={`${pend} pending action${pend === 1 ? '' : 's'}`}
            >
              PEND {pend}
            </span>
          )}
          {!isPerRun && !isDormant && metrics.recentErrorCount > 0 && (
            <span
              className="fw-step-chip-err"
              data-testid={`err-chip-${step.id}`}
              title={`${metrics.recentErrorCount} error${metrics.recentErrorCount === 1 ? '' : 's'} in last 24h`}
            >
              ERR {fmtCount(metrics, metrics.recentErrorCount)}
            </span>
          )}
          {isDormant && (
            <span
              className="fw-step-chip-disabled"
              title="V87 disabled this stage; pipeline stays inactive until ops re-enables the cron."
            >
              disabled
            </span>
          )}
        </div>
      </>
    );

    // Dormant nodes are inert — no click target.
    if (isDormant || !onSelect) {
      return (
        <div
          className="fw-step fw-step--dead"
          data-node-type={step.nodeType}
          data-testid={`step-${step.id}`}
        >
          {inner}
        </div>
      );
    }

    return (
      <button
        type="button"
        className="fw-step fw-step--clickable"
        data-node-type={step.nodeType}
        data-testid={`step-${step.id}`}
        aria-label={`${step.labelCn} — click for details`}
        onClick={() => onSelect(step)}
      >
        {inner}
      </button>
    );
  },
);

StepCard.displayName = 'StepCard';

/** Single metric cell — vertical pair label/value. */
const Metric: React.FC<{
  label: string;
  value: string;
  muted?: boolean;
}> = ({ label, value, muted }) => (
  <div className="fw-step-metric">
    <span className="fw-step-metric-label">{label}</span>
    <span
      className={`fw-step-metric-value${muted ? ' fw-step-metric-value--muted' : ''}`}
    >
      {value}
    </span>
  </div>
);

/**
 * Per-run mode metric pair. For the current step, show:
 *   - "run start" (run.startedAt as a lag, e.g. "2h ago")
 *   - "stage age" (run.lastUpdatedAt as a lag — time since current stage entered)
 * For other steps (completed/pending/context), show muted "—" placeholders
 * so the metric row still occupies the same vertical space (DAG layout
 * stays consistent across mode switches; no node-height jitter).
 */
const PerRunMetricCells: React.FC<{
  activeRun: FlywheelRunDto | null;
  isCurrentForRun: boolean;
  isContextForRun: boolean;
  isCompletedForRun: boolean;
}> = ({ activeRun, isCurrentForRun, isContextForRun, isCompletedForRun }) => {
  if (!activeRun) {
    return (
      <>
        <Metric label="run start" value="—" muted />
        <Metric label="stage age" value="—" muted />
      </>
    );
  }
  if (isCurrentForRun) {
    return (
      <>
        <Metric label="run start" value={formatLag(activeRun.startedAt)} />
        <Metric label="stage age" value={formatLag(activeRun.lastUpdatedAt)} />
      </>
    );
  }
  // Completed / pending / context steps: show muted dashes (info would be
  // misleading — we don't track per-stage-per-run timestamps in MVP).
  return (
    <>
      <Metric
        label="run start"
        value={isCompletedForRun ? '✓' : isContextForRun ? 'pre' : '—'}
        muted
      />
      <Metric label="stage age" value="—" muted />
    </>
  );
};

/** Visual marker shown in the card head when in per-run mode. */
const RunPositionMark: React.FC<{
  isCurrent: boolean;
  isContext: boolean;
  isCompleted: boolean;
  isError: boolean;
  hasActiveRun: boolean;
}> = ({ isCurrent, isContext, isCompleted, isError, hasActiveRun }) => {
  if (!hasActiveRun) {
    return (
      <span
        className="fw-run-mark fw-run-mark--idle"
        aria-label="No run selected"
        title="No run selected"
      >
        ·
      </span>
    );
  }
  if (isContext) {
    return (
      <span
        className="fw-run-mark fw-run-mark--context"
        aria-label="Context step (pre-run)"
        title="This step happens before the run was created"
      >
        ·
      </span>
    );
  }
  if (isError && isCurrent) {
    return (
      <span
        className="fw-run-mark fw-run-mark--error"
        aria-label="Run errored at this step"
        title="Run errored at this step"
      >
        ✕
      </span>
    );
  }
  if (isCurrent) {
    return (
      <span
        className="fw-run-mark fw-run-mark--current"
        aria-label="Current step for selected run"
        title="Current step"
      >
        ●
      </span>
    );
  }
  if (isCompleted) {
    return (
      <span
        className="fw-run-mark fw-run-mark--completed"
        aria-label="Completed step"
        title="Run already passed through this step"
      >
        ✓
      </span>
    );
  }
  return (
    <span
      className="fw-run-mark fw-run-mark--pending"
      aria-label="Pending step"
      title="Not yet reached"
    >
      ·
    </span>
  );
};

function fmtCount(m: StepMetrics, n: number): string {
  if (!m.loaded) return '…';
  if (m.errored) return '—';
  if (n >= 1000) return `${(n / 1000).toFixed(1)}K`;
  return String(n);
}

function nodeIcon(t: StepDescriptor['nodeType']): string {
  switch (t) {
    case 'auto':    return '⚙';
    case 'user':    return '◆';
    case 'hybrid':  return '⚡';
    case 'entry':   return '↳';
    case 'dormant': return '⏸';
  }
}

/**
 * a11y — single-letter marker so the dot color isn't the only channel
 * carrying the health status. H/W/S/D/E correspond to PRD N3 health
 * buckets (Healthy / Warn / Stale / Dormant / Empty).
 */
function healthLetter(h: ReturnType<typeof computeHealth>): string {
  switch (h) {
    case 'healthy': return 'H';
    case 'warn':    return 'W';
    case 'stale':   return 'S';
    case 'dormant': return 'D';
    case 'empty':   return 'E';
  }
}

export default StepCard;
