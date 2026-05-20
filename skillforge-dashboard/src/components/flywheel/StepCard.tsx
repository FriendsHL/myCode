import React from 'react';
import type { StepDescriptor, StepMetrics } from './types';
import { computeHealth, formatLag } from './types';

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
const StepCard: React.FC<StepCardProps> = React.memo(({ step, metrics, onSelect }) => {
  const health = computeHealth(step, metrics);
  const lag = formatLag(metrics.lastActivityAt);
  const isDormant = step.nodeType === 'dormant';
  const pend = metrics.pendingActionCount ?? 0;

  const inner = (
    <>
      <div className="fw-step-head">
        <span className="fw-step-icon" aria-hidden="true">
          {nodeIcon(step.nodeType)}
        </span>
        <span className="fw-step-title">{step.labelCn}</span>
        {/* a11y — dual channel: colored dot + single-letter marker for
            deuteranopia / protanopia. H/W/S/D/E correspond to PRD N3
            health buckets. aria-label reads the full word. */}
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
      </div>
      <div className="fw-step-metrics" data-testid={`metrics-${step.id}`}>
        <Metric label="in-flight" value={fmtCount(metrics, metrics.inFlight)} />
        <Metric
          label="lag"
          value={isDormant ? '—' : lag}
          muted={isDormant}
        />
      </div>
      <div className="fw-step-foot">
        {step.nodeType === 'user' && pend > 0 && (
          <span
            className="fw-step-chip-pend"
            data-testid={`pend-chip-${step.id}`}
            title={`${pend} pending action${pend === 1 ? '' : 's'}`}
          >
            PEND {pend}
          </span>
        )}
        {!isDormant && metrics.recentErrorCount > 0 && (
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
});

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
