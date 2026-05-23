import { useMemo, useState } from 'react';
import { Button, Input, Table, Tag, Tooltip, message } from 'antd';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import {
  listDatasets,
  type EvalDataset,
  type EvalDatasetSummary,
} from '../api/evalDataset';
import { getAgents, extractList } from '../api';
import { useAuth } from '../contexts/AuthContext';
import CreateDatasetModal from '../components/evals/datasets/CreateDatasetModal';
import DatasetDetailDrawer from '../components/evals/datasets/DatasetDetailDrawer';
import CompositionBadge from '../components/evals/datasets/CompositionBadge';
import '../components/evals/evals.css';

/**
 * EVAL-DATASET-LAYER V1 §5.2 — `/eval/datasets` route.
 *
 * Surfaces the named + versioned EvalDataset collection. Lists existing
 * datasets, supports create, and opens a detail drawer with versions table
 * + per-version scenarios + composition health.
 *
 * Iron Law: this is a NEW page; Chat.tsx / ChatWindow.tsx / Layout.tsx
 * remain untouched. Navigation to this route comes from the `/eval` page's
 * "Manage Datasets" link or via direct URL.
 */
export default function EvalDatasets() {
  const { userId } = useAuth();
  const queryClient = useQueryClient();

  const [q, setQ] = useState('');
  const [agentFilter, setAgentFilter] = useState<string>('');
  const [createOpen, setCreateOpen] = useState(false);
  const [drawerDataset, setDrawerDataset] = useState<EvalDataset | null>(null);

  // ── agents (for create modal + filter) ──────────────────────────────
  const agentsQ = useQuery({
    queryKey: ['agents'],
    queryFn: () => getAgents(),
  });
  const agents = useMemo(() => {
    if (!agentsQ.data) return [] as Array<{ id: string | number; name?: string | null }>;
    return extractList<Record<string, unknown>>(agentsQ.data).map((a) => ({
      id: String(a.id ?? ''),
      name: (a.name as string | null | undefined) ?? null,
    }));
  }, [agentsQ.data]);

  // ── datasets list ───────────────────────────────────────────────────
  const datasetsQ = useQuery({
    queryKey: ['eval-datasets', userId, agentFilter],
    queryFn: () =>
      listDatasets({
        ownerId: userId,
        agentId: agentFilter || undefined,
      }).then((r) => r.data ?? []),
  });

  const filtered = useMemo(() => {
    const list = datasetsQ.data ?? [];
    const ql = q.trim().toLowerCase();
    if (!ql) return list;
    return list.filter(
      (d) =>
        d.name.toLowerCase().includes(ql) ||
        (d.description ?? '').toLowerCase().includes(ql) ||
        (d.tags ?? []).some((t) => t.toLowerCase().includes(ql)),
    );
  }, [datasetsQ.data, q]);

  const columns = [
    {
      title: 'Name',
      dataIndex: 'name',
      key: 'name',
      render: (v: string, row: EvalDatasetSummary) => (
        <div>
          <div style={{ fontWeight: 500 }}>{v}</div>
          {row.description && (
            <div style={{ fontSize: 11, color: 'var(--fg-4, #8a8a93)' }}>{row.description}</div>
          )}
        </div>
      ),
    },
    {
      title: 'Agent',
      dataIndex: 'agentId',
      key: 'agentId',
      width: 140,
      render: (v: string | null | undefined) => {
        if (!v) {
          return (
            <Tooltip title="Cross-agent / generic dataset.">
              <Tag>generic</Tag>
            </Tooltip>
          );
        }
        const a = agents.find((x) => String(x.id) === String(v));
        return <Tag>{a?.name ? `${a.name}` : `#${v}`}</Tag>;
      },
    },
    {
      title: 'Tags',
      dataIndex: 'tags',
      key: 'tags',
      width: 200,
      render: (tags: string[] | null | undefined) => {
        const list = tags ?? [];
        if (list.length === 0) return <span style={{ color: 'var(--fg-4, #8a8a93)' }}>—</span>;
        return (
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 4 }}>
            {list.map((t) => (
              <Tag key={t}>#{t}</Tag>
            ))}
          </div>
        );
      },
    },
    {
      title: 'Versions',
      dataIndex: 'versionCount',
      key: 'versionCount',
      width: 110,
      render: (v: number | undefined, row: EvalDatasetSummary) => (
        <span style={{ fontFamily: 'var(--font-mono, monospace)' }}>
          {v ?? 0}
          {row.latestVersionNumber != null && ` (latest v${row.latestVersionNumber})`}
        </span>
      ),
    },
    {
      title: 'Scenarios',
      dataIndex: 'latestScenarioCount',
      key: 'latestScenarioCount',
      width: 100,
      render: (v: number | undefined) => v ?? '—',
    },
    {
      title: 'Baseline',
      key: 'baseline',
      width: 200,
      render: (_: unknown, row: EvalDatasetSummary) => (
        <CompositionBadge
          stats={{
            expected_baseline_pass_rate: row.latestExpectedBaselinePassRate ?? undefined,
          }}
          actualBaselinePassRate={row.latestActualBaselinePassRate}
        />
      ),
    },
    {
      title: 'Created',
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 160,
      render: (v: string) => (v ? new Date(v).toLocaleString() : '—'),
    },
  ];

  return (
    <div className="eval-page" style={{ padding: 24 }}>
      {/* ── Page header ── */}
      <header className="eval-page-header" style={{ marginBottom: 16 }}>
        <div className="eval-page-header-l">
          <h1 className="eval-page-title">
            <Link to="/eval" style={{ color: 'inherit', textDecoration: 'none' }}>Eval</Link>
            <span style={{ color: 'var(--fg-4, #8a8a93)', margin: '0 8px' }}>/</span>
            Datasets
          </h1>
          <p className="eval-page-sub">命名 + 版本化的 scenario 集合 · 跑 A/B 评测前选定版本</p>
        </div>
        <div className="eval-page-header-r">
          <Button type="primary" onClick={() => setCreateOpen(true)}>
            + Create Dataset
          </Button>
        </div>
      </header>

      {/* ── Filters ── */}
      <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginBottom: 16 }}>
        <Input
          placeholder="search name / description / tag…"
          allowClear
          value={q}
          onChange={(e) => setQ(e.target.value)}
          style={{ maxWidth: 320 }}
        />
        <select
          className="agents-search"
          value={agentFilter}
          onChange={(e) => setAgentFilter(e.target.value)}
          style={{ padding: '6px 10px', borderRadius: 6 }}
        >
          <option value="">All agents</option>
          {agents.map((a) => (
            <option key={String(a.id)} value={String(a.id)}>
              {a.name ? `${a.name} (#${a.id})` : `Agent #${a.id}`}
            </option>
          ))}
        </select>
        <span style={{ marginLeft: 'auto', fontFamily: 'var(--font-mono)', fontSize: 11, color: 'var(--fg-4)' }}>
          {filtered.length} of {datasetsQ.data?.length ?? 0} datasets
        </span>
      </div>

      {/* ── Table ── */}
      <Table<EvalDatasetSummary>
        rowKey="id"
        loading={datasetsQ.isLoading}
        dataSource={filtered}
        columns={columns}
        pagination={{ pageSize: 20, hideOnSinglePage: true }}
        onRow={(record) => ({
          onClick: () => setDrawerDataset(record),
          style: { cursor: 'pointer' },
        })}
        locale={{
          emptyText: datasetsQ.isError
            ? 'Failed to load datasets.'
            : 'No datasets yet — create one to start versioning scenarios for A/B runs.',
        }}
      />

      {/* ── Modals & Drawers ── */}
      <CreateDatasetModal
        open={createOpen}
        ownerId={userId}
        agents={agents}
        onClose={() => setCreateOpen(false)}
        onCreated={(d) => {
          setCreateOpen(false);
          message.success(`Created "${d.name}". Open it to publish a version.`);
          queryClient.invalidateQueries({ queryKey: ['eval-datasets'] });
          setDrawerDataset(d);
        }}
      />

      <DatasetDetailDrawer
        open={!!drawerDataset}
        dataset={drawerDataset}
        onClose={() => setDrawerDataset(null)}
      />
    </div>
  );
}
