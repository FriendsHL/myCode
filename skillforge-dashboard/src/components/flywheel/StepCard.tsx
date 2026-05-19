import React from 'react';
import { Link } from 'react-router-dom';
import type { StepDescriptor, StepMetrics } from './types';
import { computeHealth, formatLag } from './types';

interface StepCardProps {
  step: StepDescriptor;
  metrics: StepMetrics;
}

/**
 * FLYWHEEL-VISUAL-STATUS — single node card. PRD N2: 4-dim observability
 * metric grid (in-flight / today / lag / recent error) + health dot + USER
 * gate `[PEND N]` chip + drill-down link.
 *
 * Read-only — PRD N5 forbids action buttons. Card is a `<Link>` when
 * drillDown is set; renders as `<div>` for dormant nodes.
 */
const StepCard: React.FC<StepCardProps> = React.memo(({ step, metrics }) => {
  const health = computeHealth(step, metrics);
  const lag = formatLag(metrics.lastActivityAt);
  const isDormant = step.nodeType === 'dormant';
  const pend = metrics.pendingActionCount ?? 0;

  const inner = (
    <>
      <div className="fw-step-icon" aria-hidden="true">
        {nodeIcon(step.nodeType)}
      </div>
      <div className="fw-step-body">
        <div className="fw-step-head">
          <span className="fw-step-title">{step.title}</span>
          {step.subtitle && <span className="fw-step-sub">{step.subtitle}</span>}
        </div>
        <div className="fw-step-metrics" data-testid={`metrics-${step.id}`}>
          <Metric label="in-flight" value={fmtCount(metrics, metrics.inFlight)} />
          <Metric label="today" value={fmtCount(metrics, metrics.todayCount)} />
          <Metric label="lag" value={isDormant ? '—' : lag} muted={isDormant} />
          <Metric
            label="errors 24h"
            value={isDormant ? '—' : fmtCount(metrics, metrics.recentErrorCount)}
            muted={isDormant || metrics.recentErrorCount === 0}
          />
        </div>
      </div>
      <div className="fw-step-rail">
        {/* code-WARN-2 a11y — colour-only health encoding fails operators
            with deuteranopia / protanopia. Pair the colored dot with a
            single-letter marker (H/W/S/D/E) so the channel is dual-coded.
            The aria-label on the dot still reads the full word for screen
            readers. */}
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
        {step.nodeType === 'user' && pend > 0 && (
          <span
            className="fw-step-chip-pend"
            data-testid={`pend-chip-${step.id}`}
            title={`${pend} pending action${pend === 1 ? '' : 's'}`}
          >
            PEND {pend}
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

  if (!step.drillDown || isDormant) {
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
    <Link
      to={step.drillDown}
      className="fw-step"
      data-node-type={step.nodeType}
      data-testid={`step-${step.id}`}
    >
      {inner}
    </Link>
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
 * code-WARN-2 a11y — single-letter marker so the dot color isn't the only
 * channel carrying the health status. H/W/S/D/E correspond to PRD N3
 * health buckets (Healthy / Warn / Stale / Dormant / Empty).
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
