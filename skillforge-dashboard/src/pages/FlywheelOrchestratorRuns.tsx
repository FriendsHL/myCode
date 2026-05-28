/**
 * OPT-LOOP-FRAMEWORK Sprint 4 (FR-5 / FR-6 / AC-5 / AC-6) — `/flywheel-runs`
 * page.
 *
 * 3-pane layout (Plan §2 D2):
 *   - Left   FilterBar (loopKind / agentId / status + refresh)
 *   - Middle RunListPanel (clickable rows)
 *   - Right  RunDetailPanel + StepTimelinePanel (stacked)
 *
 * Polling cadence (Plan §2 D5): list + detail each poll every 5s while any
 * visible row / the selected run is non-terminal. WS push
 * (`flywheel_run_status_changed` + `flywheel_step_state_changed`) supplies
 * between-tick liveness via `useFlywheelOrchestratorRunsWS`.
 *
 * `?runId=…` URL search param drives detail-pane selection so the WS
 * `View run →` toast (future enhancement) can deep-link.
 */
import React, { useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import './FlywheelOrchestratorRuns.css';
import { useAuth } from '../contexts/AuthContext';
import {
  useFlywheelOrchestratorRuns,
  flywheelOrchestratorRunsQueryKey,
} from '../hooks/useFlywheelOrchestratorRuns';
import { useFlywheelOrchestratorRunDetail } from '../hooks/useFlywheelOrchestratorRunDetail';
import { useFlywheelOrchestratorRunsWS } from '../hooks/useFlywheelOrchestratorRunsWS';
import FilterBar, {
  type FilterBarValue,
} from '../components/flywheelOrchestratorRun/FilterBar';
import RunListPanel from '../components/flywheelOrchestratorRun/RunListPanel';
import RunDetailPanel from '../components/flywheelOrchestratorRun/RunDetailPanel';
import StepTimelinePanel from '../components/flywheelOrchestratorRun/StepTimelinePanel';

const PAGE_LIMIT = 50;

const FlywheelOrchestratorRunsPage: React.FC = () => {
  const [searchParams, setSearchParams] = useSearchParams();
  const { userId } = useAuth();
  const queryClient = useQueryClient();

  const [filter, setFilter] = useState<FilterBarValue>({
    loopKind: searchParams.get('loopKind'),
    agentId: searchParams.get('agentId') ? Number(searchParams.get('agentId')) : null,
    status: searchParams.get('status'),
  });
  const selectedRunId = searchParams.get('runId');

  const setSelectedRunId = (id: string | null) => {
    setSearchParams(
      (prev) => {
        const out = new URLSearchParams(prev);
        if (id == null) out.delete('runId');
        else out.set('runId', id);
        return out;
      },
      { replace: true },
    );
  };

  const handleFilterChange = (next: FilterBarValue) => {
    setFilter(next);
    setSearchParams(
      (prev) => {
        const out = new URLSearchParams(prev);
        if (next.loopKind == null) out.delete('loopKind');
        else out.set('loopKind', next.loopKind);
        if (next.agentId == null) out.delete('agentId');
        else out.set('agentId', String(next.agentId));
        if (next.status == null) out.delete('status');
        else out.set('status', next.status);
        // Reset selection on filter change — the previously selected row
        // may no longer be in the filtered list.
        out.delete('runId');
        return out;
      },
      { replace: true },
    );
  };

  const listParams = useMemo(
    () => ({
      loopKind: filter.loopKind,
      agentId: filter.agentId,
      status: filter.status,
      limit: PAGE_LIMIT,
      offset: 0,
    }),
    [filter.loopKind, filter.agentId, filter.status],
  );

  const listQuery = useFlywheelOrchestratorRuns(listParams);
  const detailQuery = useFlywheelOrchestratorRunDetail(selectedRunId);

  // WS subscription — patches both list + detail caches on incoming events.
  useFlywheelOrchestratorRunsWS({
    userId,
    selectedRunId,
  });

  const handleRefresh = () => {
    // Invalidate both list and detail so the user gets a fresh server
    // snapshot (use after suspecting a missed WS event).
    queryClient.invalidateQueries({ queryKey: ['flywheel-orchestrator-runs'] });
    if (selectedRunId) {
      queryClient.invalidateQueries({
        queryKey: ['flywheel-orchestrator-run-detail', selectedRunId],
      });
    }
    // Also explicitly refetch the current list query key.
    void queryClient.refetchQueries({
      queryKey: flywheelOrchestratorRunsQueryKey(listParams),
    });
  };

  const runs = listQuery.data?.items ?? [];
  const total = listQuery.data?.total ?? null;
  const detail = detailQuery.data ?? null;

  return (
    <div
      data-testid="flywheel-orch-page"
      style={{
        display: 'flex',
        flexDirection: 'column',
        height: '100%',
        padding: 'var(--sp-6, 24px) var(--sp-8, 32px)',
        maxWidth: 1800,
        width: '100%',
        margin: '0 auto',
        boxSizing: 'border-box',
      }}
    >
      {/* Page header */}
      <div style={{ marginBottom: 20 }}>
        <h1
          style={{
            fontFamily: 'var(--font-serif)',
            fontSize: 28,
            fontWeight: 500,
            letterSpacing: '-0.02em',
            margin: '0 0 4px',
            lineHeight: 1.2,
            color: 'var(--fg-1)',
          }}
        >
          Flywheel Runs
        </h1>
        <p style={{ color: 'var(--fg-3)', fontSize: 'var(--font-size-sm)', margin: 0 }}>
          OPT-LOOP-FRAMEWORK orchestrator runs — opt_report, memory_curation, attribution, …
          Each row is one <code>t_flywheel_run</code> driven by the BE orchestrator.
        </p>
      </div>

      {/* 3-pane grid (responsive — see FlywheelOrchestratorRuns.css):
       *   ≥1280px: 3 cols (filter+list / detail / timeline)
       *   <1280px: 2 cols (filter+list spans 2 rows; detail above timeline)
       *    ≤767px: single column stack (filter+list → detail → timeline) */}
      <div className="flywheel-runs-grid">
        {/* Pane 1 — filters + list */}
        <div className="flywheel-runs-pane flywheel-runs-pane--filters">
          <FilterBar
            value={filter}
            onChange={handleFilterChange}
            onRefresh={handleRefresh}
            total={total}
            isFetching={listQuery.isFetching}
          />
          <RunListPanel
            runs={runs}
            selectedRunId={selectedRunId}
            onSelect={setSelectedRunId}
            isLoading={listQuery.isLoading}
            isError={!!listQuery.error}
            errorMsg={listQuery.error?.message ?? null}
          />
        </div>

        {/* Pane 2 — detail metadata */}
        <div className="flywheel-runs-pane flywheel-runs-pane--detail">
          <RunDetailPanel
            run={detail?.run ?? null}
            isLoading={detailQuery.isLoading}
            isError={!!detailQuery.error}
            errorMsg={detailQuery.error?.message ?? null}
          />
        </div>

        {/* Pane 3 — step timeline */}
        <div className="flywheel-runs-pane flywheel-runs-pane--timeline">
          {selectedRunId != null && detail != null && (
            <StepTimelinePanel steps={detail.steps} />
          )}
          {selectedRunId == null && (
            <div
              style={{
                padding: 32,
                fontSize: 13,
                color: 'var(--fg-3)',
                textAlign: 'center',
              }}
            >
              Select a run to view its step timeline.
            </div>
          )}
        </div>
      </div>
    </div>
  );
};

export default FlywheelOrchestratorRunsPage;
