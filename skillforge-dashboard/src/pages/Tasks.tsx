import React, { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { Tooltip } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { listTaskRuns, type TaskRunItem, type TaskRunSource } from '../api/tasks';
import SchedulesPage from './Schedules';
import TabBar from '../components/TabBar';
import '../components/tasks/tasks.css';

/**
 * Unified Tasks page — shows runs across all subsystems (scheduled tasks,
 * sub-agents, skill evolution, A/B evals, multi-agent collab) so operators
 * have a single feed for "what has been running".
 */

const SOURCE_OPTIONS: { value: TaskRunSource; label: string }[] = [
  { value: 'scheduled_task', label: 'Scheduled' },
  { value: 'subagent', label: 'SubAgent' },
  { value: 'skill_evolution', label: 'Skill Evo' },
  { value: 'skill_ab', label: 'Skill A/B' },
  { value: 'prompt_ab', label: 'Prompt A/B' },
  { value: 'collab', label: 'Collab' },
];
const SOURCE_LABEL: Record<TaskRunSource, string> = SOURCE_OPTIONS.reduce(
  (acc, o) => ({ ...acc, [o.value]: o.label }),
  {} as Record<TaskRunSource, string>,
);

function statusClass(status: string | null): string {
  if (!status) return 's-default';
  const s = status.toLowerCase();
  if (['success', 'completed', 'promoted', 'ok'].includes(s)) return 's-success';
  if (['failure', 'failed', 'error', 'timeout'].includes(s)) return 's-failure';
  if (['running', 'pending', 'initialized', 'in_progress'].includes(s)) return 's-running';
  return 's-default';
}

function fmtRelative(iso: string | null): string {
  if (!iso) return '—';
  const t = new Date(iso).getTime();
  const diff = Date.now() - t;
  if (diff < 0) return new Date(iso).toLocaleString();
  const sec = Math.floor(diff / 1000);
  if (sec < 60) return `${sec}s ago`;
  const min = Math.floor(sec / 60);
  if (min < 60) return `${min}m ago`;
  const hour = Math.floor(min / 60);
  if (hour < 24) return `${hour}h ago`;
  const day = Math.floor(hour / 24);
  if (day < 30) return `${day}d ago`;
  return new Date(iso).toLocaleDateString();
}

function fmtDuration(start: string | null, end: string | null): string {
  if (!start || !end) return '—';
  const ms = new Date(end).getTime() - new Date(start).getTime();
  if (ms < 1000) return `${ms}ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`;
  return `${(ms / 60_000).toFixed(1)}m`;
}

const PAGE_SIZE = 25;

const Tasks: React.FC = () => {
  const [activeTab, setActiveTab] = useState('runs');
  const [source, setSource] = useState<TaskRunSource | undefined>(undefined);
  const [page, setPage] = useState(1);

  const { data, isLoading, refetch, isFetching } = useQuery({
    queryKey: ['task-runs', source, 200],
    queryFn: () => listTaskRuns({ source, limit: 200 }).then((r) => r.data ?? []),
    refetchInterval: 30_000,
  });

  const rows: TaskRunItem[] = data ?? [];

  const stats = useMemo(() => {
    const running = rows.filter((r) => {
      const s = (r.status ?? '').toLowerCase();
      return s === 'running' || s === 'pending' || s === 'in_progress';
    }).length;
    const failed = rows.filter((r) => {
      const s = (r.status ?? '').toLowerCase();
      return s === 'failed' || s === 'error' || s === 'timeout';
    }).length;
    const succeeded = rows.filter((r) => {
      const s = (r.status ?? '').toLowerCase();
      return s === 'success' || s === 'completed' || s === 'promoted' || s === 'ok';
    }).length;
    return { total: rows.length, running, failed, succeeded };
  }, [rows]);

  const totalPages = Math.max(1, Math.ceil(rows.length / PAGE_SIZE));
  const pagedRows = rows.slice((page - 1) * PAGE_SIZE, page * PAGE_SIZE);

  const TAB_ITEMS = [
    { key: 'runs', label: 'Runs' },
    { key: 'schedules', label: 'Schedules' },
  ];

  if (activeTab === 'schedules') {
    return (
      <div style={{ display: 'flex', flexDirection: 'column', height: 'calc(100vh - var(--header-height, 44px))' }}>
        <TabBar tabs={TAB_ITEMS} activeTab={activeTab} onSwitch={setActiveTab} />
        <div style={{ flex: 1, minHeight: 0, overflow: 'auto', scrollbarGutter: 'stable' }}>
          <SchedulesPage />
        </div>
      </div>
    );
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: 'calc(100vh - var(--header-height, 44px))' }}>
      <TabBar tabs={TAB_ITEMS} activeTab={activeTab} onSwitch={setActiveTab} />
      <div style={{ flex: 1, minHeight: 0, overflow: 'auto', scrollbarGutter: 'stable', padding: 'var(--sp-6, 24px) var(--sp-8, 32px)', maxWidth: 1400, margin: '0 auto', width: '100%', boxSizing: 'border-box' }}>
        {/* Header */}
        <div style={{ marginBottom: 'var(--sp-6, 24px)' }}>
          <h1 style={{ fontFamily: 'var(--font-serif)', fontSize: 28, fontWeight: 500, letterSpacing: '-0.02em', color: 'var(--fg-1)', margin: '0 0 4px', lineHeight: 1.2 }}>
            Task Runs
          </h1>
          <p style={{ color: 'var(--fg-3)', fontSize: 'var(--font-size-sm)', margin: 0 }}>
            Unified feed across all subsystems — scheduled tasks, sub-agents, skill evolution, A/B evals, and collab runs.
          </p>
        </div>

        {/* Stats */}
        <div className="tasks-stats">
          <div className="tasks-stat">
            <span className="tasks-stat-n">{stats.total}</span>
            <span className="tasks-stat-l">total</span>
          </div>
          <div className="tasks-stat">
            <span className="tasks-stat-n" style={{ color: 'var(--color-warn)' }}>{stats.running}</span>
            <span className="tasks-stat-l">running</span>
          </div>
          <div className="tasks-stat">
            <span className="tasks-stat-n" style={{ color: 'var(--color-err)' }}>{stats.failed}</span>
            <span className="tasks-stat-l">failed</span>
          </div>
          <div className="tasks-stat">
            <span className="tasks-stat-n" style={{ color: 'var(--color-ok)' }}>{stats.succeeded}</span>
            <span className="tasks-stat-l">succeeded</span>
          </div>
        </div>

        {/* Toolbar */}
        <div className="tasks-toolbar">
          <div className="tasks-source-filter">
            <button
              className={source == null ? 'on' : ''}
              onClick={() => { setSource(undefined); setPage(1); }}
            >
              All
            </button>
            {SOURCE_OPTIONS.map((o) => (
              <button
                key={o.value}
                className={source === o.value ? 'on' : ''}
                onClick={() => { setSource(source === o.value ? undefined : o.value); setPage(1); }}
              >
                {o.label}
              </button>
            ))}
          </div>
          <button className="tasks-refresh" onClick={() => refetch()} disabled={isFetching}>
            {isFetching ? '↻ Loading…' : '↻ Refresh'}
          </button>
          <span className="tasks-toolbar-count">
            {rows.length} run{rows.length === 1 ? '' : 's'}
          </span>
        </div>

        {/* Table or empty state */}
        {isLoading ? (
          <div className="tasks-empty">
            <p className="tasks-empty-title">Loading…</p>
          </div>
        ) : rows.length === 0 ? (
          <div className="tasks-empty">
            <div className="tasks-empty-icon">
              <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5">
                <path d="M13 2L3 14h9l-1 8 10-12h-9l1-8z" />
              </svg>
            </div>
            <p className="tasks-empty-title">No task runs yet</p>
            <p className="tasks-empty-desc">Runs from scheduled tasks, sub-agents, skill evolution, and A/B evals will appear here.</p>
          </div>
        ) : (
          <>
            <div className="tasks-table">
              <div className="tasks-table-h">
                <span>Source</span>
                <span>Name</span>
                <span>Status</span>
                <span>Triggered</span>
                <span>Duration</span>
                <span>Session</span>
                <span>Detail</span>
                <span>Error</span>
              </div>
              {pagedRows.map((row) => (
                <div className="tasks-row" key={row.runId}>
                  <span className={`tasks-source-badge src-${row.source}`}>
                    {SOURCE_LABEL[row.source] ?? row.source}
                  </span>
                  <Tooltip title={row.name}>
                    <span className="tasks-mono">{row.name}</span>
                  </Tooltip>
                  <span className={`tasks-status-badge ${statusClass(row.status)}`}>
                    {row.status ?? '—'}
                  </span>
                  <Tooltip title={row.triggeredAt ? new Date(row.triggeredAt).toLocaleString() : undefined}>
                    <span className="tasks-dim">{fmtRelative(row.triggeredAt)}</span>
                  </Tooltip>
                  <span className="tasks-mono">{fmtDuration(row.triggeredAt, row.finishedAt)}</span>
                  {row.sessionId ? (
                    <Link to={`/chat/${row.sessionId}`} className="tasks-link">
                      {row.sessionId.slice(0, 8)}…
                    </Link>
                  ) : (
                    <span className="tasks-dim">—</span>
                  )}
                  <Tooltip title={row.detail ?? undefined} placement="topLeft">
                    <span className="tasks-detail">{row.detail ?? '—'}</span>
                  </Tooltip>
                  {row.errorMessage ? (
                    <Tooltip title={row.errorMessage} placement="topLeft">
                      <span className="tasks-err">{row.errorMessage}</span>
                    </Tooltip>
                  ) : (
                    <span className="tasks-dim">—</span>
                  )}
                </div>
              ))}
            </div>
            {/* Pagination */}
            {totalPages > 1 && (
              <div className="tasks-pagination">
                <button disabled={page <= 1} onClick={() => setPage((p) => Math.max(1, p - 1))}>← Prev</button>
                <span className="tasks-pagination-info">Page {page} / {totalPages}</span>
                <button disabled={page >= totalPages} onClick={() => setPage((p) => Math.min(totalPages, p + 1))}>Next →</button>
              </div>
            )}
          </>
        )}
      </div>
    </div>
  );
};

export default Tasks;
