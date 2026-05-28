/**
 * OPT-LOOP-FRAMEWORK Sprint 4 — middle/upper-right pane: run metadata,
 * error reason (if any), summary JSON (collapsed by default).
 *
 * W1 (Plan-review): `generatorSessionId` renders as a clickable link to
 * `/sessions/{id}` so the operator can jump into the chat that produced
 * this run (e.g. the report-generator session for an opt_report run).
 */
import React from 'react';
import { Alert, Collapse, Empty, Spin, Tag } from 'antd';
import { Link } from 'react-router-dom';
import type { FlywheelOrchestratorRunDto } from '../../api/flywheelOrchestratorRun';
import { statusColor } from './statusColor';

export interface RunDetailPanelProps {
  run: FlywheelOrchestratorRunDto | null;
  isLoading: boolean;
  isError: boolean;
  errorMsg?: string | null;
}

function formatRelative(iso: string | null | undefined): string {
  if (!iso) return '—';
  try {
    return new Date(iso).toLocaleString();
  } catch {
    return iso;
  }
}

function formatWindow(start: string | null, end: string | null): string | null {
  if (!start && !end) return null;
  const fmt = (s: string | null) => {
    if (!s) return '—';
    try {
      const d = new Date(s);
      return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
    } catch {
      return s;
    }
  };
  return `${fmt(start)} → ${fmt(end)}`;
}

function prettyJson(raw: string | null): string | null {
  if (!raw) return null;
  try {
    return JSON.stringify(JSON.parse(raw), null, 2);
  } catch {
    return null;
  }
}

const RunDetailPanel: React.FC<RunDetailPanelProps> = ({
  run,
  isLoading,
  isError,
  errorMsg,
}) => {
  if (run === null && !isLoading && !isError) {
    return (
      <div
        style={{ padding: 32, color: 'var(--fg-3)', fontSize: 13, textAlign: 'center' }}
        data-testid="flywheel-orch-detail-empty"
      >
        Select a run from the list to view details.
      </div>
    );
  }

  if (isLoading) {
    return (
      <div
        style={{ padding: 32, textAlign: 'center' }}
        data-testid="flywheel-orch-detail-loading"
      >
        <Spin />
      </div>
    );
  }

  if (isError || !run) {
    return (
      <Alert
        type="error"
        showIcon
        message="Failed to load run detail"
        description={errorMsg ?? 'Unknown error'}
        style={{ margin: 16 }}
        data-testid="flywheel-orch-detail-error"
      />
    );
  }

  const windowStr = formatWindow(run.windowStart, run.windowEnd);
  const prettyInput = prettyJson(run.inputJson);
  const prettySummary = prettyJson(run.summaryJson);

  return (
    <div
      style={{ display: 'flex', flexDirection: 'column', gap: 14, padding: 20 }}
      data-testid="flywheel-orch-detail"
    >
      {/* Header */}
      <div
        style={{
          display: 'flex',
          flexDirection: 'column',
          gap: 6,
          borderBottom: '1px solid var(--border-subtle, var(--border-1, #e0dbcf))',
          paddingBottom: 12,
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
          <h2
            style={{
              fontFamily: 'var(--font-serif)',
              fontSize: 20,
              fontWeight: 500,
              margin: 0,
              color: 'var(--fg-1)',
            }}
          >
            {run.loopKind}
          </h2>
          <Tag color={statusColor(run.status)}>{run.status}</Tag>
          <span style={{ fontSize: 11, color: 'var(--fg-4)' }}>· {run.triggerSource}</span>
        </div>
        <div
          style={{
            fontFamily: 'var(--font-mono, ui-monospace, Menlo, monospace)',
            fontSize: 11,
            color: 'var(--fg-4)',
            wordBreak: 'break-all',
          }}
        >
          {run.runId}
        </div>
        <div style={{ fontSize: 12, color: 'var(--fg-3)', display: 'flex', flexWrap: 'wrap', gap: 12 }}>
          {run.agentId != null && (
            <span>
              <strong style={{ color: 'var(--fg-2)' }}>Agent</strong> {run.agentId}
            </span>
          )}
          <span>
            <strong style={{ color: 'var(--fg-2)' }}>Created</strong>{' '}
            {formatRelative(run.createdAt)}
          </span>
          <span>
            <strong style={{ color: 'var(--fg-2)' }}>Updated</strong>{' '}
            {formatRelative(run.updatedAt)}
          </span>
          {windowStr && (
            <span>
              <strong style={{ color: 'var(--fg-2)' }}>Window</strong> {windowStr}
            </span>
          )}
          {/* W1 (Plan-review): generatorSessionId as clickable link → /sessions/{id}.
              W-FE-2: React Router <Link> so navigation stays client-side
              instead of a full-page reload (was <a target="_blank">). */}
          {run.generatorSessionId && (
            <span>
              <strong style={{ color: 'var(--fg-2)' }}>Generator session</strong>{' '}
              <Link
                to={`/sessions/${run.generatorSessionId}`}
                data-testid="flywheel-orch-detail-generator-session-link"
                style={{
                  fontFamily: 'var(--font-mono, ui-monospace, Menlo, monospace)',
                  fontSize: 11,
                }}
              >
                {run.generatorSessionId.slice(0, 8)}…
              </Link>
            </span>
          )}
        </div>
      </div>

      {/* Running spinner */}
      {(run.status === 'pending' || run.status === 'running') && (
        <Alert
          type="info"
          showIcon
          icon={<Spin size="small" />}
          message="Run is in progress…"
          description="Status updates arrive via WebSocket; polling fallback every 5 seconds."
        />
      )}

      {/* Error */}
      {(run.status === 'error' || run.status === 'failed') && run.errorReason && (
        <Alert
          type="error"
          showIcon
          message="Run failed"
          description={
            <pre style={{ margin: 0, whiteSpace: 'pre-wrap', fontSize: 12 }}>
              {run.errorReason}
            </pre>
          }
          data-testid="flywheel-orch-detail-error-alert"
        />
      )}

      {/* Input JSON — collapsed */}
      {prettyInput && (
        <Collapse
          ghost
          size="small"
          items={[
            {
              key: 'input',
              label: 'Input (input_json)',
              children: (
                <pre
                  style={{
                    margin: 0,
                    padding: 12,
                    background: 'var(--bg-code, #1c1c1e)',
                    color: 'var(--text-on-accent, #ffffff)',
                    fontFamily: 'var(--font-mono, ui-monospace, Menlo, monospace)',
                    fontSize: 12,
                    borderRadius: 6,
                    overflow: 'auto',
                    maxHeight: 300,
                  }}
                >
                  {prettyInput}
                </pre>
              ),
            },
          ]}
        />
      )}

      {/* Summary JSON — collapsed */}
      {prettySummary && (
        <Collapse
          ghost
          size="small"
          items={[
            {
              key: 'summary',
              label: 'Summary (summary_json)',
              children: (
                <pre
                  style={{
                    margin: 0,
                    padding: 12,
                    background: 'var(--bg-code, #1c1c1e)',
                    color: 'var(--text-on-accent, #ffffff)',
                    fontFamily: 'var(--font-mono, ui-monospace, Menlo, monospace)',
                    fontSize: 12,
                    borderRadius: 6,
                    overflow: 'auto',
                    maxHeight: 360,
                  }}
                >
                  {prettySummary}
                </pre>
              ),
            },
          ]}
        />
      )}

      {!prettyInput && !prettySummary && run.status === 'completed' && (
        <Empty description="Completed but no input / summary payload available." />
      )}
    </div>
  );
};

export default RunDetailPanel;
