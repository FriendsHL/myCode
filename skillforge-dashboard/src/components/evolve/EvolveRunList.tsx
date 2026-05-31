/**
 * AUTOEVOLVE-AGENT-FLYWHEEL Module D — EvolveRunList
 *
 * Renders a compact list of EvolveRunSummary rows. The selected row is
 * highlighted; clicking a row calls onSelect. Used by the trajectory panel
 * to let the user pick which evolve run to chart.
 */
import React from 'react';
import type { EvolveRunSummary } from '../../api/evolve';

interface EvolveRunListProps {
  runs: EvolveRunSummary[];
  selectedRunId: string | null;
  loading: boolean;
  onSelect: (runId: string) => void;
}

const STATUS_COLORS: Record<string, string> = {
  running: 'var(--color-info)',
  completed: 'var(--color-success)',
  error: 'var(--color-error)',
  cancelled: 'var(--fg-3)',
};

function formatDate(iso: string): string {
  try {
    return new Date(iso).toLocaleDateString(undefined, {
      month: 'short',
      day: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
    });
  } catch {
    return iso;
  }
}

function statusColor(status: string): string {
  return STATUS_COLORS[status] ?? 'var(--fg-3)';
}

const EvolveRunList: React.FC<EvolveRunListProps> = ({
  runs,
  selectedRunId,
  loading,
  onSelect,
}) => {
  if (loading) {
    return (
      <div className="erl-loading" data-testid="evolve-run-list-loading">
        Loading runs…
      </div>
    );
  }

  if (runs.length === 0) {
    return (
      <div className="erl-empty" data-testid="evolve-run-list-empty">
        No evolve runs yet.
      </div>
    );
  }

  return (
    <ul className="erl-list" data-testid="evolve-run-list" role="listbox" aria-label="Evolve runs">
      {runs.map((run) => {
        const isSelected = run.evolveRunId === selectedRunId;
        const delta = run.finalDelta;
        const deltaStr =
          delta == null ? '—' : delta >= 0 ? `+${delta.toFixed(1)}pp` : `${delta.toFixed(1)}pp`;
        const deltaPositive = delta != null && delta > 0;
        const deltaNegative = delta != null && delta < 0;

        return (
          <li key={run.evolveRunId} role="option" aria-selected={isSelected}>
            <button
              type="button"
              className={`erl-row${isSelected ? ' erl-row--selected' : ''}`}
              onClick={() => onSelect(run.evolveRunId)}
              data-testid={`evolve-run-row-${run.evolveRunId}`}
            >
              <div className="erl-row-top">
                <span
                  className="erl-status-dot"
                  style={{ color: statusColor(run.status) }}
                  title={run.status}
                  aria-label={run.status}
                >
                  ●
                </span>
                <span className="erl-run-id">{run.evolveRunId.slice(0, 8)}…</span>
                <span
                  className="erl-delta"
                  style={{
                    color: deltaPositive
                      ? 'var(--color-success)'
                      : deltaNegative
                        ? 'var(--color-error)'
                        : 'var(--fg-3)',
                  }}
                >
                  {deltaStr}
                </span>
              </div>
              <div className="erl-row-bottom">
                <span className="erl-iter-count">
                  {run.iterationCount} iter{run.iterationCount !== 1 ? 's' : ''}
                </span>
                <span className="erl-date">{formatDate(run.createdAt)}</span>
              </div>
            </button>
          </li>
        );
      })}
    </ul>
  );
};

export default EvolveRunList;
