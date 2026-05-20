import React, { useEffect } from 'react';
import { Link } from 'react-router-dom';
import type { ActivityEvent, StepDescriptor, StepMetrics } from './types';
import { computeHealth, formatLag } from './types';

interface FlywheelStepDrawerProps {
  /** Selected step descriptor; null = drawer closed. */
  step: StepDescriptor | null;
  /** Live metrics for the active step (may be undefined while loading). */
  metrics?: StepMetrics;
  /** Activity events filtered to this step's recent 24h (caller responsibility). */
  recentEvents?: ActivityEvent[];
  /** Close handler — parent should setSelected(null). */
  onClose: () => void;
}

/**
 * FLYWHEEL-FLOWCHART — right slide-out detail panel.
 *
 * Renders on top of the flowchart shell when a step is selected. Keeps the
 * DAG visible behind it for context (Linear / Raycast pattern). Esc closes;
 * backdrop click closes. The Drawer is read-only per PRD N5 — the only
 * action is a "open in page" link to the existing operate URL.
 *
 * Content order matches what an operator reads top-to-bottom when
 * debugging "why is this step stuck":
 *   1. Header (step name + node type + health letter)
 *   2. One-line Chinese description (what this step does)
 *   3. Live metrics — 5 dims
 *   4. Recent error label (if any)
 *   5. Recent activity (filtered events) — most recent first
 *   6. Footer: "open in operate page" link
 */
const FlywheelStepDrawer: React.FC<FlywheelStepDrawerProps> = ({
  step,
  metrics,
  recentEvents,
  onClose,
}) => {
  // Esc-to-close. Cleanup matters per frontend.md known footgun #5 — must
  // remove listener when drawer unmounts or step changes (otherwise a hover
  // on an unmounted drawer would keep responding to Esc).
  useEffect(() => {
    if (!step) return;
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') onClose();
    }
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [step, onClose]);

  if (!step) return null;

  const m = metrics;
  const health = m ? computeHealth(step, m) : 'empty';
  const isDormant = step.nodeType === 'dormant';
  const pend = m?.pendingActionCount ?? 0;
  const eventsToShow = recentEvents ?? [];

  return (
    <>
      {/* Backdrop — semi-transparent click target. Subtle (panel underneath
          should stay readable for context). */}
      <div
        className="fw-drawer-backdrop"
        onClick={onClose}
        data-testid="fw-drawer-backdrop"
        aria-hidden="true"
      />
      <aside
        className="fw-drawer"
        role="dialog"
        aria-labelledby="fw-drawer-title"
        data-testid="fw-drawer"
        data-step-id={step.id}
      >
        <header className="fw-drawer-head">
          <span className="fw-drawer-node-type" data-node-type={step.nodeType}>
            {nodeTypeLabelCn(step.nodeType)}
          </span>
          <h2 id="fw-drawer-title" className="fw-drawer-title">
            {step.labelCn}
          </h2>
          <span
            className="fw-drawer-health-dot"
            data-health={health}
            title={`health: ${health}`}
            aria-label={`health: ${health}`}
          >
            {healthLetter(health)}
          </span>
          <button
            type="button"
            className="fw-drawer-close"
            onClick={onClose}
            aria-label="Close detail panel"
          >
            ×
          </button>
        </header>

        <section className="fw-drawer-section">
          <p className="fw-drawer-desc">{step.descriptionCn}</p>
          {step.subtitle && (
            <p className="fw-drawer-meta">
              <span className="fw-drawer-meta-label">触发：</span>
              <code>{step.subtitle}</code>
              {step.cronIntervalMinutes != null && (
                <span className="fw-drawer-meta-extra">
                  · 健康阈值 lag &lt; {2 * step.cronIntervalMinutes}m
                </span>
              )}
            </p>
          )}
        </section>

        <section className="fw-drawer-section">
          <h3 className="fw-drawer-section-title">实时指标</h3>
          {!m || !m.loaded ? (
            <p className="fw-drawer-empty">加载中…</p>
          ) : (
            <dl className="fw-drawer-metrics" data-testid="fw-drawer-metrics">
              <DrawerMetric label="处理中" value={String(m.inFlight)} />
              <DrawerMetric label="今日累计" value={String(m.todayCount)} />
              <DrawerMetric
                label="lag"
                value={isDormant ? '—' : formatLag(m.lastActivityAt)}
              />
              <DrawerMetric
                label="24h 错误"
                value={String(m.recentErrorCount)}
                tone={m.recentErrorCount > 0 ? 'danger' : 'default'}
              />
              {step.nodeType === 'user' && (
                <DrawerMetric
                  label="待审"
                  value={String(pend)}
                  tone={pend > 0 ? 'attention' : 'default'}
                />
              )}
            </dl>
          )}
          {m?.recentErrorLabel && (
            <p className="fw-drawer-error-label">
              最近错误：<code>{m.recentErrorLabel}</code>
            </p>
          )}
        </section>

        <section className="fw-drawer-section">
          <h3 className="fw-drawer-section-title">
            最近活动
            <span className="fw-drawer-section-sub">
              （过去 24h，限本节点）
            </span>
          </h3>
          {eventsToShow.length === 0 ? (
            <p className="fw-drawer-empty">暂无活动。</p>
          ) : (
            <ul className="fw-drawer-events" data-testid="fw-drawer-events">
              {eventsToShow.slice(0, 8).map((e) => (
                <li
                  key={e.id}
                  className={`fw-drawer-event${e.isError ? ' is-error' : ''}`}
                >
                  <span className="fw-drawer-event-time">
                    {formatLag(e.at)}
                  </span>
                  <span className="fw-drawer-event-label">{e.label}</span>
                  {e.meta && (
                    <span className="fw-drawer-event-meta">{e.meta}</span>
                  )}
                </li>
              ))}
            </ul>
          )}
        </section>

        <footer className="fw-drawer-foot">
          {step.drillDown ? (
            <Link
              to={step.drillDown}
              className="fw-drawer-drill-link"
              data-testid="fw-drawer-drill-link"
              onClick={onClose}
            >
              在 page 中打开 →
            </Link>
          ) : (
            <span className="fw-drawer-drill-disabled">
              此节点无 drill-down 页（dormant）
            </span>
          )}
        </footer>
      </aside>
    </>
  );
};

const DrawerMetric: React.FC<{
  label: string;
  value: string;
  tone?: 'default' | 'danger' | 'attention';
}> = ({ label, value, tone = 'default' }) => (
  <div className="fw-drawer-metric" data-tone={tone}>
    <dt className="fw-drawer-metric-label">{label}</dt>
    <dd className="fw-drawer-metric-value">{value}</dd>
  </div>
);

function nodeTypeLabelCn(t: StepDescriptor['nodeType']): string {
  switch (t) {
    case 'auto':    return '自动';
    case 'user':    return '人工';
    case 'hybrid':  return '混合';
    case 'entry':   return '入口';
    case 'dormant': return '暂停';
  }
}

function healthLetter(h: ReturnType<typeof computeHealth>): string {
  switch (h) {
    case 'healthy': return 'H';
    case 'warn':    return 'W';
    case 'stale':   return 'S';
    case 'dormant': return 'D';
    case 'empty':   return 'E';
  }
}

export default FlywheelStepDrawer;
