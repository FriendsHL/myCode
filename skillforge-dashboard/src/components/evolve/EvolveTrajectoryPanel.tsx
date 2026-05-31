/**
 * AUTOEVOLVE-AGENT-FLYWHEEL Module D — EvolveTrajectoryPanel
 *
 * Assembles the full trajectory view:
 *   - Agent ID input + Load button
 *   - Run list sidebar (EvolveRunList) — multi-select via checkboxes
 *   - Score-over-iteration chart (EvolveTrajectoryChart) with overlaid series
 *
 * Data loading via react-query:
 *   1. listEvolveRuns (when agentId is entered)
 *   2. getEvolveRun   (for each selected run — parallel queries)
 *
 * No WebSocket needed — react-query refetch (staleTime 30s) is sufficient
 * because this is a trajectory history view, not a live feed.
 */
import React, { useState, useCallback } from 'react';
import { useQuery } from '@tanstack/react-query';
import { listEvolveRuns, getEvolveRun } from '../../api/evolve';
import type { EvolveRunSummary, EvolveRunDetail } from '../../api/evolve';
import EvolveRunList from './EvolveRunList';
import EvolveTrajectoryChart from './EvolveTrajectoryChart';
import './evolve.css';

const MAX_OVERLAY_RUNS = 4;

const EvolveTrajectoryPanel: React.FC = () => {
  // Agent ID controlled input
  const [agentIdInput, setAgentIdInput] = useState('');
  // The committed agent ID (on Load click)
  const [committedAgentId, setCommittedAgentId] = useState<number | null>(null);
  // Selected run IDs for charting (up to MAX_OVERLAY_RUNS)
  const [selectedRunIds, setSelectedRunIds] = useState<string[]>([]);

  // ── list runs ──
  const {
    data: runsEnvelope,
    isLoading: runsLoading,
    isError: runsError,
    error: runsErrObj,
  } = useQuery({
    queryKey: ['evolve-runs', committedAgentId],
    queryFn: () =>
      committedAgentId != null
        ? listEvolveRuns(committedAgentId).then((r) => r.data)
        : Promise.resolve({ items: [] as EvolveRunSummary[] }),
    enabled: committedAgentId != null,
    staleTime: 30_000,
  });

  const runs = runsEnvelope?.items ?? [];

  // ── detail queries for each selected run (parallel) ──
  const selectedRunQueries = selectedRunIds.map((id) =>
    // eslint-disable-next-line react-hooks/rules-of-hooks
    useQuery({
      queryKey: ['evolve-run-detail', id],
      queryFn: () => getEvolveRun(id).then((r) => r.data),
      enabled: selectedRunIds.includes(id),
      staleTime: 30_000,
    }),
  );

  const detailsLoading = selectedRunQueries.some((q) => q.isLoading);
  const detailRuns: EvolveRunDetail[] = selectedRunQueries
    .filter((q) => q.data != null)
    .map((q) => q.data as EvolveRunDetail);

  const handleLoad = useCallback(() => {
    const parsed = parseInt(agentIdInput, 10);
    if (!isNaN(parsed) && parsed > 0) {
      setCommittedAgentId(parsed);
      setSelectedRunIds([]);
    }
  }, [agentIdInput]);

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent<HTMLInputElement>) => {
      if (e.key === 'Enter') handleLoad();
    },
    [handleLoad],
  );

  const handleSelectRun = useCallback((runId: string) => {
    setSelectedRunIds((prev) => {
      if (prev.includes(runId)) {
        return prev.filter((id) => id !== runId);
      }
      // Enforce overlay cap — drop the oldest selection
      const next = [...prev, runId];
      return next.length > MAX_OVERLAY_RUNS ? next.slice(next.length - MAX_OVERLAY_RUNS) : next;
    });
  }, []);

  const runsErrMsg =
    runsError
      ? runsErrObj instanceof Error
        ? runsErrObj.message
        : 'Failed to load evolve runs.'
      : null;

  return (
    <section
      className="etraj-section"
      aria-label="Evolution trajectory"
      data-testid="evolve-trajectory-panel"
    >
      <div className="etraj-head">
        <h3 className="etraj-title">Evolution trajectory</h3>
        {detailsLoading && (
          <span className="etraj-subtitle">Loading…</span>
        )}
      </div>

      {/* Agent ID input */}
      <div className="etraj-agent-select">
        <label className="etraj-agent-label" htmlFor="etraj-agent-id-input">
          Agent ID
        </label>
        <input
          id="etraj-agent-id-input"
          className="etraj-agent-input"
          type="number"
          min="1"
          placeholder="e.g. 42"
          value={agentIdInput}
          onChange={(e) => setAgentIdInput(e.target.value)}
          onKeyDown={handleKeyDown}
          aria-label="Agent ID"
          data-testid="etraj-agent-id-input"
        />
        <button
          type="button"
          className="etraj-load-btn"
          onClick={handleLoad}
          disabled={agentIdInput.trim() === '' || runsLoading}
          data-testid="etraj-load-btn"
        >
          Load
        </button>
      </div>

      {runsErrMsg && (
        <p className="etraj-error" role="alert">
          {runsErrMsg}
        </p>
      )}

      <div className="etraj-body">
        {/* Run list sidebar */}
        <aside className="etraj-sidebar">
          <EvolveRunList
            runs={runs}
            selectedRunId={selectedRunIds[selectedRunIds.length - 1] ?? null}
            loading={runsLoading}
            onSelect={handleSelectRun}
          />
        </aside>

        {/* Chart area */}
        <div className="etraj-chart-area">
          <EvolveTrajectoryChart
            runs={detailRuns}
            height={320}
          />
        </div>
      </div>
    </section>
  );
};

export default EvolveTrajectoryPanel;
