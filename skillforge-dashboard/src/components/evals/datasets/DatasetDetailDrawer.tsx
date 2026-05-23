import { useMemo, useState } from 'react';
import { Drawer, Tag, Table, Alert, Skeleton, Empty, Tooltip } from 'antd';
import { useQuery } from '@tanstack/react-query';
import {
  listVersions,
  getDatasetVersion,
  getDatasetVersionHealth,
  type EvalDataset,
  type EvalDatasetVersion,
  type EvalScenarioBrief,
  formatBaselinePassRate,
} from '../../../api/evalDataset';
import CompositionBadge from './CompositionBadge';

interface DatasetDetailDrawerProps {
  open: boolean;
  dataset: EvalDataset | null;
  onClose: () => void;
}

/**
 * EVAL-DATASET-LAYER V1 §5.2 — dataset detail drawer.
 *
 * Surfaces:
 *   1. Header — dataset meta (agent, tags, description, created).
 *   2. Versions table — version_number / scenario count / composition / created_at.
 *   3. Selected version drill-down — scenarios in that version + composition
 *      health warning banner from `/dataset-versions/{id}/health`.
 */
function DatasetDetailDrawer({ open, dataset, onClose }: DatasetDetailDrawerProps) {
  const [selectedVersionId, setSelectedVersionId] = useState<string | null>(null);

  const datasetId = dataset?.id ?? null;

  // ── versions list ─────────────────────────────────────────────────────
  const versionsQ = useQuery({
    queryKey: ['eval-dataset-versions', datasetId],
    queryFn: () => listVersions(datasetId!).then((r) => r.data ?? []),
    enabled: open && !!datasetId,
  });

  // Auto-select the most recent version once the list loads.
  // r2 ts W6 fix: wrap in useMemo so `versions ?? []` 不产生新引用每渲染 (避免
  // effectiveVersionId useMemo deps 不稳 + react-hooks/exhaustive-deps ESLint flag)
  const versions = useMemo(() => versionsQ.data ?? [], [versionsQ.data]);
  const effectiveVersionId = useMemo(() => {
    if (selectedVersionId) return selectedVersionId;
    if (versions.length === 0) return null;
    return versions[0].id;
  }, [selectedVersionId, versions]);

  // ── version detail (scenarios) ────────────────────────────────────────
  const versionDetailQ = useQuery({
    queryKey: ['eval-dataset-version', effectiveVersionId],
    queryFn: () => getDatasetVersion(effectiveVersionId!).then((r) => r.data),
    enabled: open && !!effectiveVersionId,
  });

  // ── version health ────────────────────────────────────────────────────
  const healthQ = useQuery({
    queryKey: ['eval-dataset-version-health', effectiveVersionId],
    queryFn: () => getDatasetVersionHealth(effectiveVersionId!).then((r) => r.data),
    enabled: open && !!effectiveVersionId,
  });

  const versionsColumns = [
    {
      title: 'Version',
      dataIndex: 'versionNumber',
      key: 'versionNumber',
      width: 80,
      render: (v: number, row: EvalDatasetVersion) => (
        <span style={{ fontFamily: 'var(--font-mono, monospace)' }}>
          v{v}
          {effectiveVersionId === row.id && <Tag color="blue" style={{ marginLeft: 6 }}>active</Tag>}
        </span>
      ),
    },
    {
      title: 'Scenarios',
      key: 'scenarioCount',
      width: 100,
      render: (_: unknown, row: EvalDatasetVersion) => row.compositionStats?.total ?? '—',
    },
    {
      title: 'Composition',
      key: 'composition',
      render: (_: unknown, row: EvalDatasetVersion) => (
        <CompositionBadge
          stats={row.compositionStats}
          actualBaselinePassRate={row.actualBaselinePassRate}
        />
      ),
    },
    {
      title: 'Created',
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 160,
      render: (v: string) => v ? new Date(v).toLocaleString() : '—',
    },
  ];

  const scenarioColumns = [
    {
      title: 'Name',
      dataIndex: 'name',
      key: 'name',
      render: (v: string, row: EvalScenarioBrief) => (
        <div>
          <div style={{ fontWeight: 500 }}>{v}</div>
          {row.sourceRef && (
            <div style={{ fontSize: 11, color: 'var(--fg-4, #8a8a93)', fontFamily: 'var(--font-mono, monospace)' }}>
              {row.sourceRef}
            </div>
          )}
        </div>
      ),
    },
    {
      title: 'Source',
      dataIndex: 'sourceType',
      key: 'sourceType',
      width: 140,
      render: (v: EvalScenarioBrief['sourceType']) => {
        if (!v) return <span style={{ color: 'var(--fg-4, #8a8a93)' }}>—</span>;
        const color = v === 'benchmark' ? 'blue' : v === 'session_derived' ? 'purple' : 'cyan';
        return <Tag color={color}>{v}</Tag>;
      },
    },
    {
      title: 'Purpose',
      dataIndex: 'purpose',
      key: 'purpose',
      width: 140,
      render: (v: EvalScenarioBrief['purpose']) => (v ? <Tag>{v}</Tag> : <span style={{ color: 'var(--fg-4, #8a8a93)' }}>—</span>),
    },
    {
      title: 'Oracle',
      dataIndex: 'oracleType',
      key: 'oracleType',
      width: 120,
      render: (v: string | null) => (v ? <Tag>{v}</Tag> : '—'),
    },
  ];

  return (
    <Drawer
      open={open}
      onClose={() => { setSelectedVersionId(null); onClose(); }}
      title={dataset ? dataset.name : 'Dataset'}
      width={920}
      destroyOnHidden
    >
      {!dataset ? (
        <Empty />
      ) : (
        <>
          {/* ── Header meta ── */}
          <div style={{ marginBottom: 16 }}>
            {dataset.description && (
              <p style={{ color: 'var(--fg-2, #c5c5cf)', marginBottom: 8 }}>{dataset.description}</p>
            )}
            <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', alignItems: 'center' }}>
              {dataset.agentId ? (
                <Tag>Agent: {dataset.agentId}</Tag>
              ) : (
                <Tooltip title="No agent binding — usable across agents.">
                  <Tag>generic</Tag>
                </Tooltip>
              )}
              {(dataset.tags ?? []).map((t) => (
                <Tag key={t} color="default">#{t}</Tag>
              ))}
              <span style={{ marginLeft: 'auto', fontSize: 11, color: 'var(--fg-4, #8a8a93)' }}>
                Created {dataset.createdAt ? new Date(dataset.createdAt).toLocaleString() : '—'}
              </span>
            </div>
          </div>

          {/* ── Versions table ── */}
          <h4 style={{ marginTop: 0, marginBottom: 8 }}>Versions</h4>
          <Table<EvalDatasetVersion>
            size="small"
            rowKey="id"
            loading={versionsQ.isLoading}
            dataSource={versions}
            columns={versionsColumns}
            pagination={false}
            onRow={(record) => ({
              onClick: () => setSelectedVersionId(record.id),
              style: { cursor: 'pointer' },
            })}
            locale={{ emptyText: 'No versions yet — publish one to get started.' }}
          />

          {/* ── Selected version health + scenarios ──
              BE returns `/dataset-versions/{id}` as an envelope:
                { version: VersionMap, scenarioIds: string[], scenarios: BriefMap[] }
              so we drill into `.version.X` for version fields. */}
          {effectiveVersionId && (
            <div style={{ marginTop: 24 }}>
              <h4 style={{ marginTop: 0, marginBottom: 8 }}>
                Version Detail
                {versionDetailQ.data?.version && (
                  <span style={{ marginLeft: 8, fontFamily: 'var(--font-mono, monospace)', fontWeight: 400 }}>
                    v{versionDetailQ.data.version.versionNumber}
                  </span>
                )}
              </h4>

              {/* Composition stats sourced from the version (BE health endpoint
                  returns only {isHealthy, warnings: string[]} — no stats echo). */}
              {versionDetailQ.data?.version && (
                <div style={{ marginBottom: 12 }}>
                  <CompositionBadge
                    stats={versionDetailQ.data.version.compositionStats}
                    actualBaselinePassRate={versionDetailQ.data.version.actualBaselinePassRate}
                    variant="full"
                  />
                </div>
              )}

              {healthQ.data && healthQ.data.warnings.length > 0 && (
                <div style={{ marginBottom: 12 }}>
                  {healthQ.data.warnings.map((w, i) => (
                    <Alert
                      key={`warn-${i}`}
                      type="warning"
                      message={w}
                      showIcon
                      style={{ marginBottom: 8 }}
                    />
                  ))}
                </div>
              )}

              {versionDetailQ.isLoading ? (
                <Skeleton active />
              ) : versionDetailQ.data ? (
                <>
                  <Table<EvalScenarioBrief>
                    size="small"
                    rowKey="id"
                    dataSource={versionDetailQ.data.scenarios}
                    columns={scenarioColumns}
                    pagination={{ pageSize: 10, hideOnSinglePage: true }}
                    locale={{ emptyText: 'No scenarios in this version.' }}
                  />
                  <div style={{ marginTop: 8, fontSize: 11, color: 'var(--fg-4, #8a8a93)', fontFamily: 'var(--font-mono, monospace)' }}>
                    composition_hash: {versionDetailQ.data.version.compositionHash ?? '—'}
                    {versionDetailQ.data.version.actualBaselinePassRate != null && (
                      <> · actual: {formatBaselinePassRate(versionDetailQ.data.version.actualBaselinePassRate)}</>
                    )}
                  </div>
                </>
              ) : (
                <Empty />
              )}
            </div>
          )}
        </>
      )}
    </Drawer>
  );
}

export default DatasetDetailDrawer;
