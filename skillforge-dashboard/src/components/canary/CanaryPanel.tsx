import React, { useMemo, useState } from 'react';
import { Tag, Tooltip, Modal, InputNumber, message } from 'antd';
import ReactECharts from 'echarts-for-react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  startCanary,
  stepUp,
  publish,
  rollback,
  getCanaryMetrics,
  listCanaries,
  type CanaryRolloutResponse,
  type MetricSnapshotResponse,
  type RolloutStage,
} from '../../api/canary';

/**
 * SKILL-CANARY-ROLLOUT V2 Phase 1.5 — Skill canary rollout control surface.
 *
 * Embedded under `SkillAbPanel`. Shows four zones (tech-design §7.2):
 *
 *   A. Rollout gauge — current % + stage chip
 *   B. 4-dim quality/efficiency/latency/cost comparison (bar chart)
 *   C. Outcome distribution — control vs candidate fail counts +
 *      `failRateRatio` against the auto-rollback threshold (1.5),
 *      with low-sample warning per tech-design §6.2
 *   D. Action buttons — start / step-up / publish / rollback, gated
 *      by rollout stage
 *
 * No new dependencies — reuses `echarts-for-react` (already in
 * `package.json`, used by `EvalHistoryChart.tsx`).
 *
 * The panel scopes itself to one (agentId, baselineSkillName) tuple. BE
 * `uq_canary_active` partial UNIQUE index guarantees at most one active
 * canary per (agentId, surfaceType), so we treat the filtered `listCanaries`
 * result as a single-or-zero match.
 */

const SURFACE_TYPE = 'skill';
/** Mirrors BE auto-rollback trigger (tech-design §6 — fail_rate_ratio > 1.5 + sample ≥ 50). */
const AUTO_ROLLBACK_THRESHOLD = 1.5;
const LOW_SAMPLE_THRESHOLD = 50;

export interface CanaryPanelProps {
  /** Agent that owns the rollout; null disables the panel (no source agent picked). */
  agentId: number | null;
  /** Baseline skill name (display + start payload). */
  parentSkillName: string;
  /** Candidate skill name (start payload). When null, "Start Canary" is disabled. */
  candidateSkillName: string | null;
}

/** Best-effort error message extraction from axios reject reasons. */
function asErrorMessage(err: unknown, fallback: string): string {
  const e = err as { response?: { data?: { error?: string } }; message?: string };
  return e?.response?.data?.error || e?.message || fallback;
}

function stageMeta(stage: RolloutStage): {
  color: string;
  bg: string;
  label: string;
  gaugeColor: string;
} {
  switch (stage) {
    case 'canary':
      return {
        color: '#8b8df5',
        bg: 'rgba(99,102,241,0.12)',
        label: 'Canary',
        gaugeColor: '#6366f1',
      };
    case 'production':
      return {
        color: '#36b37e',
        bg: 'rgba(54,179,126,0.14)',
        label: 'Production',
        gaugeColor: '#36b37e',
      };
    case 'rolled_back':
      return {
        color: '#f0616d',
        bg: 'rgba(240,97,109,0.14)',
        label: 'Rolled back',
        gaugeColor: '#f0616d',
      };
    case 'disabled':
    default:
      return {
        color: 'var(--fg-4, #8a8a93)',
        bg: 'var(--bg-hover, #1d1d22)',
        label: 'Disabled',
        gaugeColor: '#8a8a93',
      };
  }
}

interface GaugeProps {
  percentage: number;
  stage: RolloutStage;
}

/**
 * Rollout gauge — echarts gauge in the same dark palette as
 * `EvalHistoryChart`. Color tracks stage (gray/disabled, blue/canary,
 * green/production, red/rolled_back) so the operator sees state from the
 * gauge alone, before reading the chip.
 */
const RolloutGauge: React.FC<GaugeProps> = ({ percentage, stage }) => {
  const meta = stageMeta(stage);
  const option = useMemo(
    () => ({
      series: [
        {
          type: 'gauge',
          radius: '95%',
          startAngle: 200,
          endAngle: -20,
          min: 0,
          max: 100,
          splitNumber: 5,
          axisLine: {
            lineStyle: {
              width: 10,
              color: [
                [percentage / 100, meta.gaugeColor],
                [1, 'rgba(138,138,147,0.25)'],
              ],
            },
          },
          axisTick: { show: false },
          splitLine: { length: 6, lineStyle: { color: '#8a8a93', width: 1 } },
          axisLabel: { color: '#8a8a93', fontSize: 9, distance: -14 },
          pointer: { length: '60%', width: 4, itemStyle: { color: meta.gaugeColor } },
          detail: {
            valueAnimation: true,
            fontSize: 18,
            fontWeight: 700,
            color: meta.gaugeColor,
            offsetCenter: [0, '40%'],
            formatter: '{value}%',
          },
          data: [{ value: percentage }],
        },
      ],
    }),
    [percentage, meta.gaugeColor],
  );
  return <ReactECharts option={option} style={{ height: 160 }} notMerge lazyUpdate />;
};

interface FourDimChartProps {
  snapshot: MetricSnapshotResponse | undefined;
}

/**
 * 4-dim comparison bar chart. `null` dimension values render as gaps (not 0)
 * per EvalHistoryChart precedent — a missing measurement must not look like
 * "scored 0". Tooltip surfaces "not measured" explicitly so the operator
 * doesn't read the absence as a bad score.
 */
const FourDimChart: React.FC<FourDimChartProps> = ({ snapshot }) => {
  const option = useMemo(() => {
    if (!snapshot) return null;
    const dims = [
      {
        name: 'Quality',
        control: snapshot.controlAvgQuality,
        candidate: snapshot.candidateAvgQuality,
      },
      {
        name: 'Efficiency',
        control: snapshot.controlAvgEfficiency,
        candidate: snapshot.candidateAvgEfficiency,
      },
      {
        name: 'Latency',
        control: snapshot.controlAvgLatency,
        candidate: snapshot.candidateAvgLatency,
      },
      {
        name: 'Cost',
        control: snapshot.controlAvgCost,
        candidate: snapshot.candidateAvgCost,
      },
    ];
    return {
      tooltip: {
        trigger: 'axis' as const,
        axisPointer: { type: 'shadow' as const },
        valueFormatter: (v: number | null | undefined) =>
          v == null ? 'not measured' : Number(v).toFixed(2),
      },
      legend: { data: ['Control', 'Candidate'], top: 0, textStyle: { fontSize: 11 } },
      grid: { left: 60, right: 16, top: 28, bottom: 24 },
      xAxis: {
        type: 'category' as const,
        data: dims.map((d) => d.name),
        axisLabel: { color: '#8a8a93', fontSize: 10 },
      },
      yAxis: {
        type: 'value' as const,
        axisLabel: { color: '#8a8a93', fontSize: 10 },
        splitLine: { lineStyle: { color: 'rgba(138,138,147,0.18)' } },
      },
      series: [
        {
          name: 'Control',
          type: 'bar' as const,
          data: dims.map((d) => d.control),
          itemStyle: { color: '#8a8a93' },
        },
        {
          name: 'Candidate',
          type: 'bar' as const,
          data: dims.map((d) => d.candidate),
          itemStyle: { color: '#6366f1' },
        },
      ],
    };
  }, [snapshot]);

  if (!option) {
    return (
      <div className="sf-empty-state" style={{ minHeight: 200, fontSize: 12 }}>
        No metric snapshots yet — hourly aggregator writes after the first bucket.
      </div>
    );
  }
  return <ReactECharts option={option} style={{ height: 200 }} notMerge lazyUpdate />;
};

interface OutcomePanelProps {
  snapshot: MetricSnapshotResponse | undefined;
}

/**
 * Outcome distribution panel (zone C). Per tech-design §6.1, the V2
 * `t_canary_metric_snapshot` only persists success/failure counts; the
 * source `outcome` annotation has 4 values (success / partial_success /
 * failure / cancelled) but BE collapses them 2-way. We surface the chip +
 * threshold line + low-sample warning here.
 */
const OutcomePanel: React.FC<OutcomePanelProps> = ({ snapshot }) => {
  if (!snapshot) {
    return null;
  }
  const ratio = snapshot.failRateRatio;
  const candidateSample = snapshot.candidateSampleSize;
  const controlSample = snapshot.controlSampleSize;
  const lowSample =
    candidateSample < LOW_SAMPLE_THRESHOLD || controlSample < LOW_SAMPLE_THRESHOLD;
  const ratioColor =
    ratio != null && ratio > AUTO_ROLLBACK_THRESHOLD
      ? '#f0616d'
      : ratio != null && ratio > 1
        ? '#ff9f43'
        : '#36b37e';

  return (
    <div style={{ fontSize: 12 }}>
      <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', marginBottom: 8 }}>
        <div style={{ flex: 1, minWidth: 140 }}>
          <div style={{ fontSize: 10, color: 'var(--fg-3)', marginBottom: 2 }}>CONTROL</div>
          <div style={{ fontFamily: 'var(--font-mono, monospace)' }}>
            n={controlSample} · ✓{snapshot.controlSuccessCount} · ✗
            {snapshot.controlFailureCount}
          </div>
        </div>
        <div style={{ flex: 1, minWidth: 140 }}>
          <div style={{ fontSize: 10, color: 'var(--fg-3)', marginBottom: 2 }}>CANDIDATE</div>
          <div style={{ fontFamily: 'var(--font-mono, monospace)', color: '#6366f1' }}>
            n={candidateSample} · ✓{snapshot.candidateSuccessCount} · ✗
            {snapshot.candidateFailureCount}
          </div>
        </div>
      </div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
        <Tooltip
          title={`Auto-rollback fires when fail_rate_ratio > ${AUTO_ROLLBACK_THRESHOLD} AND candidate sample ≥ ${LOW_SAMPLE_THRESHOLD} (BE tech-design §6).`}
        >
          <Tag color={ratioColor === '#f0616d' ? 'red' : ratioColor === '#ff9f43' ? 'orange' : 'green'}>
            fail_rate_ratio = {ratio == null ? '—' : ratio.toFixed(2)}
          </Tag>
        </Tooltip>
        <span style={{ fontSize: 11, color: 'var(--fg-4)' }}>
          threshold: {AUTO_ROLLBACK_THRESHOLD.toFixed(1)}
        </span>
        {lowSample && (
          <Tag color="orange" style={{ marginLeft: 'auto' }}>
            Low sample (&lt;{LOW_SAMPLE_THRESHOLD}) — ratio not reliable
          </Tag>
        )}
      </div>
      <div style={{ fontSize: 10, color: 'var(--fg-4)', marginTop: 8, lineHeight: 1.5 }}>
        Note: BE collapses 4-value outcome (success / partial_success / failure / cancelled)
        into 2 buckets — partial_success counted as success, cancelled as failure
        (tech-design §6.1).
      </div>
    </div>
  );
};

export const CanaryPanel: React.FC<CanaryPanelProps> = ({
  agentId,
  parentSkillName,
  candidateSkillName,
}) => {
  const queryClient = useQueryClient();
  const [stepUpOpen, setStepUpOpen] = useState(false);
  const [stepUpValue, setStepUpValue] = useState<number>(50);

  // List rollouts scoped to (agent, surface=skill). `uq_canary_active` is per
  // (agent_id, surface_type) so this returns ≤ 1 row in stage='canary'; but
  // older completed/rolled_back rows may co-exist. We resolve "active" first
  // (canary), then fall back to the most recent terminal row of THIS baseline.
  const listQuery = useQuery<CanaryRolloutResponse[]>({
    queryKey: ['canary-rollouts', agentId, parentSkillName],
    queryFn: async () => {
      if (agentId == null) return [];
      const r = await listCanaries({ agentId, surfaceType: SURFACE_TYPE });
      return r.data;
    },
    enabled: agentId != null,
  });

  const matching = useMemo(() => {
    const all = listQuery.data ?? [];
    return all.filter((c) => c.baselineSkillName === parentSkillName);
  }, [listQuery.data, parentSkillName]);

  const active = useMemo(() => {
    // Prefer the canary row, then production, then most-recent terminal.
    const inFlight = matching.find((c) => c.rolloutStage === 'canary');
    if (inFlight) return inFlight;
    const prod = matching.find((c) => c.rolloutStage === 'production');
    if (prod) return prod;
    if (matching.length === 0) return undefined;
    return [...matching].sort(
      (a, b) => new Date(b.updatedAt).getTime() - new Date(a.updatedAt).getTime(),
    )[0];
  }, [matching]);

  const metricsQuery = useQuery<MetricSnapshotResponse[]>({
    queryKey: ['canary-metrics', active?.id],
    queryFn: () =>
      active ? getCanaryMetrics(active.id, { limit: 24 }).then((r) => r.data) : Promise.resolve([]),
    enabled: !!active,
  });

  // BE orders DESC; the freshest non-empty bucket is the natural "latest".
  // Fall back to the very latest (even if empty) so the operator still sees
  // an outcome row rather than nothing.
  const latestSnapshot = useMemo(() => {
    const arr = metricsQuery.data;
    if (!arr || arr.length === 0) return undefined;
    const nonEmpty = arr.find(
      (s) => s.controlSampleSize > 0 || s.candidateSampleSize > 0,
    );
    return nonEmpty ?? arr[0];
  }, [metricsQuery.data]);

  const invalidate = () => {
    queryClient.invalidateQueries({ queryKey: ['canary-rollouts', agentId, parentSkillName] });
    if (active) queryClient.invalidateQueries({ queryKey: ['canary-metrics', active.id] });
  };

  const startMutation = useMutation({
    mutationFn: async () => {
      if (agentId == null) throw new Error('agentId required');
      if (!candidateSkillName) throw new Error('candidate skill name unknown');
      const res = await startCanary({
        agentId,
        surfaceType: SURFACE_TYPE,
        baselineSkillName: parentSkillName,
        candidateSkillName,
        percentage: 10,
      });
      return res.data;
    },
    onSuccess: () => {
      message.success('Canary started at 10%');
      invalidate();
    },
    onError: (err: unknown) => {
      // 409 — active canary already exists; surface the BE message and
      // suggest rollback as the recovery path (per task brief UX rule).
      const e = err as { response?: { status?: number; data?: { error?: string } } };
      if (e?.response?.status === 409) {
        message.error(
          e.response?.data?.error ||
            'Active canary already exists — roll back the existing one before starting a new canary.',
        );
        return;
      }
      message.error(asErrorMessage(err, 'Failed to start canary'));
    },
  });

  const stepUpMutation = useMutation({
    mutationFn: async (pct: number) => {
      if (!active) throw new Error('no active canary');
      const res = await stepUp(active.id, { percentage: pct });
      return res.data;
    },
    onSuccess: (r) => {
      message.success(`Stepped up to ${r.rolloutPercentage}%`);
      invalidate();
    },
    onError: (err) => message.error(asErrorMessage(err, 'Step-up failed')),
  });

  const publishMutation = useMutation({
    mutationFn: async () => {
      if (!active) throw new Error('no active canary');
      const res = await publish(active.id);
      return res.data;
    },
    onSuccess: () => {
      message.success('Published — candidate promoted to production');
      invalidate();
    },
    onError: (err) => message.error(asErrorMessage(err, 'Publish failed')),
  });

  const rollbackMutation = useMutation({
    mutationFn: async () => {
      if (!active) throw new Error('no active canary');
      const res = await rollback(active.id, { reason: 'manual-dashboard' });
      return res.data;
    },
    onSuccess: () => {
      message.success('Rolled back — traffic returned to baseline');
      invalidate();
    },
    onError: (err) => message.error(asErrorMessage(err, 'Rollback failed')),
  });

  const handlePublish = () => {
    if (!active) return;
    Modal.confirm({
      title: 'Publish candidate to 100%?',
      content: (
        <div style={{ fontSize: 12.5, lineHeight: 1.55 }}>
          <p>
            <strong>{active.candidateSkillName}</strong> will replace{' '}
            <strong>{active.baselineSkillName}</strong> at 100% traffic. This action is
            terminal — rollback after publish is not available from this panel.
          </p>
        </div>
      ),
      okText: 'Publish 100%',
      cancelText: 'Cancel',
      onOk: () => publishMutation.mutate(),
    });
  };

  const handleRollback = () => {
    if (!active) return;
    Modal.confirm({
      title: 'Roll back canary?',
      content: (
        <div style={{ fontSize: 12.5, lineHeight: 1.55 }}>
          <p>
            Candidate <strong>{active.candidateSkillName}</strong> traffic will drop to 0%
            and the rollout will be marked rolled_back. New canaries can be started
            afterwards.
          </p>
        </div>
      ),
      okText: 'Roll back',
      cancelText: 'Cancel',
      okButtonProps: { danger: true },
      onOk: () => rollbackMutation.mutate(),
    });
  };

  const handleStepUpConfirm = () => {
    if (!active) return;
    if (stepUpValue <= active.rolloutPercentage) {
      message.warning(`Step-up % must be greater than current (${active.rolloutPercentage}%)`);
      return;
    }
    stepUpMutation.mutate(stepUpValue);
    setStepUpOpen(false);
  };

  if (agentId == null) {
    return null;
  }

  if (listQuery.isLoading) {
    return (
      <div
        style={{
          marginTop: 12,
          padding: 12,
          fontSize: 11,
          color: 'var(--fg-4)',
          borderTop: '1px solid var(--border-subtle, #2a2a31)',
        }}
      >
        Loading canary state…
      </div>
    );
  }

  if (listQuery.isError) {
    return (
      <div
        style={{
          marginTop: 12,
          padding: 12,
          fontSize: 11,
          color: 'var(--color-err, #f0616d)',
          borderTop: '1px solid var(--border-subtle, #2a2a31)',
        }}
      >
        Failed to load canary state.
      </div>
    );
  }

  const meta = active ? stageMeta(active.rolloutStage) : null;
  const stage = active?.rolloutStage;
  const pct = active?.rolloutPercentage ?? 0;
  const showStepUp = stage === 'canary' && pct < 100;
  const showPublishRollback = stage === 'canary';
  const terminal = stage === 'production' || stage === 'rolled_back';

  return (
    <div
      style={{
        marginTop: 14,
        padding: '12px 14px',
        borderTop: '1px solid var(--border-subtle, #2a2a31)',
        background: 'var(--bg-base, transparent)',
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 10 }}>
        <span style={{ fontWeight: 600, fontSize: 12, color: 'var(--fg-1)' }}>
          Canary Rollout
        </span>
        {meta && <Tag color={meta.color}>{meta.label}</Tag>}
        {active && (
          <span
            style={{
              marginLeft: 'auto',
              fontSize: 10,
              color: 'var(--fg-4)',
              fontFamily: 'var(--font-mono, monospace)',
            }}
          >
            #{active.id} · {active.baselineSkillName} → {active.candidateSkillName}
          </span>
        )}
      </div>

      {!active && (
        <div
          style={{
            padding: '14px 0',
            fontSize: 12,
            color: 'var(--fg-3)',
            display: 'flex',
            alignItems: 'center',
            gap: 12,
          }}
        >
          <span>No active canary for this skill.</span>
          <Tooltip
            title={
              !candidateSkillName
                ? 'A candidate (A/B fork) must exist before starting a canary.'
                : 'Start a canary rollout at 10% traffic.'
            }
          >
            <span>
              <button
                className="btn-ghost-sf"
                disabled={!candidateSkillName || startMutation.isPending}
                onClick={() => startMutation.mutate()}
                style={{
                  fontSize: 11,
                  padding: '4px 12px',
                  opacity: !candidateSkillName ? 0.55 : 1,
                  cursor: !candidateSkillName ? 'not-allowed' : 'pointer',
                }}
              >
                {startMutation.isPending ? 'Starting…' : 'Start Canary (10%)'}
              </button>
            </span>
          </Tooltip>
        </div>
      )}

      {active && (
        <>
          <div
            style={{
              display: 'grid',
              gridTemplateColumns: 'minmax(200px, 1fr) minmax(280px, 2fr)',
              gap: 16,
              alignItems: 'start',
            }}
          >
            <div>
              <RolloutGauge percentage={pct} stage={active.rolloutStage} />
              {terminal && active.decision && (
                <div
                  style={{
                    textAlign: 'center',
                    fontSize: 10,
                    color: 'var(--fg-4)',
                    marginTop: -8,
                  }}
                >
                  Decision: {active.decision}
                </div>
              )}
            </div>
            <div>
              {metricsQuery.isError ? (
                // Phase 1.5 r1 review code-HIGH: surface metrics-API failure
                // explicitly so the operator sees the error state instead of
                // a misleading "no snapshots yet" empty state (silent-failure
                // blocker per pipeline.md severity checklist).
                <div
                  className="sf-empty-state"
                  style={{
                    minHeight: 200,
                    color: 'var(--color-err, #f0616d)',
                    fontSize: 12,
                    display: 'flex',
                    flexDirection: 'column',
                    alignItems: 'center',
                    justifyContent: 'center',
                    gap: 8,
                  }}
                >
                  <span>Failed to load metrics.</span>
                  <button
                    type="button"
                    className="btn-ghost-sf"
                    onClick={() => metricsQuery.refetch()}
                    style={{ fontSize: 11, padding: '3px 10px' }}
                  >
                    Retry
                  </button>
                </div>
              ) : (
                <FourDimChart snapshot={latestSnapshot} />
              )}
            </div>
          </div>

          <div style={{ marginTop: 12 }}>
            {/* Suppress outcome panel on metrics error — its data source is
                the same query, showing "n=0" alongside the error would be
                doubly misleading. */}
            {!metricsQuery.isError && <OutcomePanel snapshot={latestSnapshot} />}
          </div>

          <div
            style={{
              marginTop: 14,
              display: 'flex',
              gap: 8,
              alignItems: 'center',
              flexWrap: 'wrap',
            }}
          >
            {showStepUp && (
              <button
                className="btn-ghost-sf"
                disabled={stepUpMutation.isPending}
                onClick={() => {
                  setStepUpValue(Math.min(100, Math.max(pct + 10, pct + 1)));
                  setStepUpOpen(true);
                }}
                style={{ fontSize: 11, padding: '3px 10px' }}
              >
                {stepUpMutation.isPending ? 'Stepping…' : 'Step Up %'}
              </button>
            )}
            {showPublishRollback && (
              <button
                className="btn-ghost-sf"
                disabled={publishMutation.isPending}
                onClick={handlePublish}
                style={{
                  fontSize: 11,
                  padding: '3px 10px',
                  color: '#36b37e',
                }}
              >
                {publishMutation.isPending ? 'Publishing…' : 'Publish 100%'}
              </button>
            )}
            {showPublishRollback && (
              <button
                className="btn-ghost-sf"
                disabled={rollbackMutation.isPending}
                onClick={handleRollback}
                style={{
                  fontSize: 11,
                  padding: '3px 10px',
                  color: 'var(--color-err, #f0616d)',
                }}
              >
                {rollbackMutation.isPending ? 'Rolling back…' : 'Rollback'}
              </button>
            )}
            {stage === 'production' && (
              <Tag color="green">Already promoted ✓</Tag>
            )}
            {stage === 'rolled_back' && (
              <Tag color="red">Rolled back ✗</Tag>
            )}
          </div>
        </>
      )}

      <Modal
        title="Step Up Canary %"
        open={stepUpOpen}
        onCancel={() => setStepUpOpen(false)}
        onOk={handleStepUpConfirm}
        okText="Step Up"
        cancelText="Cancel"
        confirmLoading={stepUpMutation.isPending}
        destroyOnClose
      >
        <div style={{ fontSize: 12.5, lineHeight: 1.55 }}>
          <p>
            Current: <strong>{pct}%</strong>. New percentage (must be greater than current,
            up to 100):
          </p>
          <InputNumber
            min={Math.min(pct + 1, 100)}
            max={100}
            value={stepUpValue}
            onChange={(v) => setStepUpValue(typeof v === 'number' ? v : pct + 10)}
            style={{ width: 120 }}
            autoFocus
          />
        </div>
      </Modal>
    </div>
  );
};
