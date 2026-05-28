/**
 * OPT-LOOP-FRAMEWORK Sprint 4 — right pane: vertical Ant Design Timeline
 * showing the steps for the selected run, sorted ASC by createdAt (BE
 * already orders this way in `FlywheelRunService.listStepsByRunId`).
 *
 * Each timeline item:
 *   - dot color: from statusColor (green/blue/red/gold)
 *   - title: stepKind + short stepRunId
 *   - subAgentSessionId chip clickable → /sessions/{id}
 *   - duration: updatedAt - createdAt (humanized)
 *   - errorReason: red Alert when present
 *   - stepOutputJson: collapsed Collapse (pretty-printed)
 */
import React from 'react';
import { Alert, Collapse, Empty, Timeline } from 'antd';
import { Link } from 'react-router-dom';
import type { FlywheelOrchestratorStepDto } from '../../api/flywheelOrchestratorRun';
import { statusColor } from './statusColor';

export interface StepTimelinePanelProps {
  steps: FlywheelOrchestratorStepDto[];
}

function durationMs(start: string, end: string): number | null {
  try {
    const a = new Date(start).getTime();
    const b = new Date(end).getTime();
    if (!Number.isFinite(a) || !Number.isFinite(b)) return null;
    return Math.max(0, b - a);
  } catch {
    return null;
  }
}

function humanizeMs(ms: number | null): string {
  if (ms == null) return '—';
  if (ms < 1_000) return `${ms}ms`;
  const secs = ms / 1_000;
  if (secs < 60) return `${secs.toFixed(1)}s`;
  const mins = secs / 60;
  if (mins < 60) return `${mins.toFixed(1)}m`;
  const hours = mins / 60;
  return `${hours.toFixed(1)}h`;
}

function prettyJson(raw: string | null): string | null {
  if (!raw) return null;
  try {
    return JSON.stringify(JSON.parse(raw), null, 2);
  } catch {
    return null;
  }
}

const StepTimelinePanel: React.FC<StepTimelinePanelProps> = ({ steps }) => {
  if (steps.length === 0) {
    return (
      <div data-testid="flywheel-orch-timeline-empty" style={{ padding: 24 }}>
        <Empty description="No steps recorded for this run yet." />
      </div>
    );
  }

  const items = steps.map((step) => {
    const dur = durationMs(step.createdAt, step.updatedAt);
    const prettyOut = prettyJson(step.stepOutputJson);
    return {
      key: step.stepRunId,
      color: statusColor(step.status),
      children: (
        <div
          data-testid={`flywheel-orch-step-${step.stepRunId.slice(0, 8)}`}
          style={{ display: 'flex', flexDirection: 'column', gap: 6, paddingBottom: 4 }}
        >
          <div
            style={{
              display: 'flex',
              gap: 8,
              alignItems: 'baseline',
              flexWrap: 'wrap',
              fontSize: 13,
            }}
          >
            <strong style={{ color: 'var(--fg-1)' }}>{step.stepKind}</strong>
            <span
              style={{
                fontFamily: 'var(--font-mono, ui-monospace, Menlo, monospace)',
                fontSize: 11,
                color: 'var(--fg-4)',
              }}
            >
              {step.stepRunId.slice(0, 8)}…
            </span>
            <span style={{ fontSize: 11, color: 'var(--fg-3)' }}>
              · {step.status} · {humanizeMs(dur)}
            </span>
          </div>

          {step.subAgentSessionId && (
            <div style={{ fontSize: 11, color: 'var(--fg-3)' }}>
              SubAgent session{' '}
              {/* W-FE-2: React Router <Link> avoids full-page reload. */}
              <Link
                to={`/sessions/${step.subAgentSessionId}`}
                style={{
                  fontFamily: 'var(--font-mono, ui-monospace, Menlo, monospace)',
                  fontSize: 11,
                }}
                data-testid={`flywheel-orch-step-session-${step.stepRunId.slice(0, 8)}`}
              >
                {step.subAgentSessionId.slice(0, 8)}…
              </Link>
            </div>
          )}

          {step.stepOutputCount != null && (
            <div style={{ fontSize: 11, color: 'var(--fg-3)' }}>
              Output rows: <strong style={{ color: 'var(--fg-2)' }}>{step.stepOutputCount}</strong>
            </div>
          )}

          {step.errorReason && (
            <Alert
              type="error"
              showIcon
              message={
                <span style={{ fontSize: 12 }}>
                  <strong>Error:</strong> {step.errorReason}
                </span>
              }
              style={{ marginTop: 4 }}
            />
          )}

          {prettyOut && (
            <Collapse
              ghost
              size="small"
              items={[
                {
                  key: 'out',
                  label: (
                    <span style={{ fontSize: 11, color: 'var(--fg-3)' }}>Output JSON</span>
                  ),
                  children: (
                    <pre
                      style={{
                        margin: 0,
                        padding: 10,
                        background: 'var(--bg-code, #1c1c1e)',
                        color: 'var(--text-on-accent, #ffffff)',
                        fontFamily: 'var(--font-mono, ui-monospace, Menlo, monospace)',
                        fontSize: 11,
                        borderRadius: 6,
                        overflow: 'auto',
                        maxHeight: 240,
                      }}
                    >
                      {prettyOut}
                    </pre>
                  ),
                },
              ]}
            />
          )}
        </div>
      ),
    };
  });

  return (
    <div style={{ padding: 20 }} data-testid="flywheel-orch-timeline">
      <h3
        style={{
          fontFamily: 'var(--font-serif)',
          fontSize: 16,
          fontWeight: 500,
          margin: '0 0 16px',
          color: 'var(--fg-1)',
        }}
      >
        Steps ({steps.length})
      </h3>
      <Timeline items={items} />
    </div>
  );
};

export default StepTimelinePanel;
