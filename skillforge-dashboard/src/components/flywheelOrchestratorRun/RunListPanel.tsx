/**
 * OPT-LOOP-FRAMEWORK Sprint 4 — middle pane: clickable list of run rows
 * for the `/flywheel-runs` page. Selecting a row drives the right detail
 * + timeline panes.
 */
import React from 'react';
import { Alert, Empty, Spin, Tag } from 'antd';
import type { FlywheelOrchestratorRunDto } from '../../api/flywheelOrchestratorRun';
import { statusColor } from './statusColor';

export interface RunListPanelProps {
  runs: FlywheelOrchestratorRunDto[];
  selectedRunId: string | null;
  onSelect: (runId: string) => void;
  isLoading: boolean;
  isError: boolean;
  errorMsg?: string | null;
}

function formatRelative(iso: string): string {
  try {
    return new Date(iso).toLocaleString();
  } catch {
    return iso;
  }
}

const RunListPanel: React.FC<RunListPanelProps> = ({
  runs,
  selectedRunId,
  onSelect,
  isLoading,
  isError,
  errorMsg,
}) => {
  if (isLoading) {
    return (
      <div style={{ padding: 24, textAlign: 'center' }} data-testid="flywheel-orch-list-loading">
        <Spin size="small" />
      </div>
    );
  }

  if (isError) {
    return (
      <Alert
        type="error"
        showIcon
        message="Failed to load runs"
        description={errorMsg ?? 'Unknown error'}
        style={{ margin: 12 }}
        data-testid="flywheel-orch-list-error"
      />
    );
  }

  if (runs.length === 0) {
    return (
      <div data-testid="flywheel-orch-list-empty" style={{ padding: 24 }}>
        <Empty description="No runs match the current filters." />
      </div>
    );
  }

  return (
    <div
      style={{ display: 'flex', flexDirection: 'column', gap: 4, padding: 8 }}
      data-testid="flywheel-orch-list"
    >
      {runs.map((row) => {
        const active = row.runId === selectedRunId;
        return (
          <button
            key={row.runId}
            type="button"
            onClick={() => onSelect(row.runId)}
            data-testid={`flywheel-orch-row-${row.runId.slice(0, 8)}`}
            style={{
              textAlign: 'left',
              padding: '10px 12px',
              borderRadius: 6,
              border: `1px solid ${
                active
                  ? 'var(--accent, #6366f1)'
                  : 'var(--border-subtle, var(--border-1, #e0dbcf))'
              }`,
              background: active
                ? 'var(--accent-soft, rgba(99,102,241,0.08))'
                : 'var(--bg-primary, var(--bg-base, #fbfaf7))',
              color: 'var(--fg-1)',
              cursor: 'pointer',
              display: 'flex',
              flexDirection: 'column',
              gap: 4,
              fontFamily: 'inherit',
              transition: 'background 80ms ease',
            }}
          >
            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: 8,
                justifyContent: 'space-between',
              }}
            >
              <span
                style={{
                  fontFamily: 'var(--font-mono, ui-monospace, Menlo, monospace)',
                  fontSize: 11,
                  color: 'var(--fg-3)',
                }}
              >
                {row.runId.slice(0, 8)}…
              </span>
              <Tag color={statusColor(row.status)} style={{ marginInlineEnd: 0 }}>
                {row.status}
              </Tag>
            </div>
            <div style={{ fontSize: 13, color: 'var(--fg-2)' }}>
              <strong>{row.loopKind}</strong>
              {row.agentId != null && (
                <span style={{ color: 'var(--fg-3)', marginLeft: 6 }}>· agent {row.agentId}</span>
              )}
            </div>
            <div style={{ fontSize: 11, color: 'var(--fg-4)' }}>
              {row.triggerSource} · {formatRelative(row.createdAt)}
            </div>
          </button>
        );
      })}
    </div>
  );
};

export default RunListPanel;
