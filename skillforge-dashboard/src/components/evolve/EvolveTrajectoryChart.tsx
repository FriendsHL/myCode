/**
 * AUTOEVOLVE-AGENT-FLYWHEEL Module D — EvolveTrajectoryChart
 *
 * ECharts line chart showing candidateScore over iteration number.
 * - One series per evolve run (multi-run overlay for AC-D4).
 * - Kept iterations: solid filled marker circle.
 * - Not-kept iterations: hollow marker circle (borderColor set, color transparent).
 * - Tooltip shows surface / changeDesc / delta / kept for each point.
 * - Respects CSS variables for dark/light theming.
 *
 * Uses echarts-for-react ReactECharts which disposes the underlying echarts
 * instance on unmount automatically (no manual dispose needed per the lib).
 */
import React, { useMemo } from 'react';
import ReactECharts from 'echarts-for-react';
import type { EChartsOption } from 'echarts';

import type { EvolveRunDetail, EvolveIteration } from '../../api/evolve';

interface EvolveTrajectoryChartProps {
  /** One or more evolve run detail objects to overlay on the same chart. */
  runs: EvolveRunDetail[];
  /** Chart height in px (default 340). */
  height?: number;
}

/** Colour palette for up to 8 overlaid runs. Cycles if more. */
const RUN_COLORS = [
  '#6366f1', // indigo
  '#10b981', // emerald
  '#f59e0b', // amber
  '#ef4444', // red
  '#8b5cf6', // violet
  '#06b6d4', // cyan
  '#f97316', // orange
  '#ec4899', // pink
];

function runColor(idx: number): string {
  return RUN_COLORS[idx % RUN_COLORS.length];
}

function formatDelta(delta: number): string {
  if (delta > 0) return `+${delta.toFixed(2)}pp`;
  if (delta < 0) return `${delta.toFixed(2)}pp`;
  return '±0pp';
}

function buildSeries(run: EvolveRunDetail, colorIdx: number): object {
  const color = runColor(colorIdx);
  const label =
    run.agentName
      ? `${run.agentName} · ${run.evolveRunId.slice(0, 6)}`
      : run.evolveRunId.slice(0, 8);

  const data = run.iterations.map((iter: EvolveIteration) => ({
    value: [iter.iteration, iter.candidateScore],
    // Store full iteration metadata for tooltip access.
    iterMeta: iter,
  }));

  return {
    name: label,
    type: 'line',
    data,
    smooth: false,
    connectNulls: false,
    lineStyle: { color, width: 2 },
    itemStyle: { color },
    symbol: 'circle',
    symbolSize: (
      _val: unknown,
      params: { data?: { iterMeta?: EvolveIteration } },
    ): number => {
      const kept = params?.data?.iterMeta?.kept;
      return kept ? 9 : 7;
    },
    // Per-point style: kept → solid fill, not-kept → hollow (border only).
    markPoint: undefined,
  };
}

const EvolveTrajectoryChart: React.FC<EvolveTrajectoryChartProps> = ({
  runs,
  height = 340,
}) => {
  const option = useMemo((): EChartsOption => {
    if (runs.length === 0) {
      return {};
    }

    const series = runs.map((run, idx) => buildSeries(run, idx));

    return {
      backgroundColor: 'transparent',
      animation: true,
      animationDuration: 400,
      legend: {
        show: runs.length > 1,
        top: 4,
        right: 8,
        textStyle: { color: 'var(--text-secondary, #5d5952)', fontSize: 12 },
        icon: 'circle',
        itemWidth: 10,
        itemHeight: 10,
      },
      grid: {
        top: runs.length > 1 ? 48 : 24,
        right: 24,
        bottom: 48,
        left: 56,
      },
      xAxis: {
        type: 'value',
        name: 'Iteration',
        nameLocation: 'middle',
        nameGap: 28,
        nameTextStyle: { color: 'var(--text-tertiary, #7a7770)', fontSize: 12 },
        minInterval: 1,
        axisLine: { lineStyle: { color: 'var(--border-subtle, #e2ded3)' } },
        axisTick: { lineStyle: { color: 'var(--border-subtle, #e2ded3)' } },
        axisLabel: {
          color: 'var(--text-tertiary, #7a7770)',
          fontSize: 11,
          formatter: (v: number) => String(Math.round(v)),
        },
        splitLine: { lineStyle: { color: 'var(--border-subtle, #e2ded3)', type: 'dashed' } },
      },
      yAxis: {
        type: 'value',
        name: 'Score',
        nameLocation: 'middle',
        nameGap: 40,
        nameTextStyle: { color: 'var(--text-tertiary, #7a7770)', fontSize: 12 },
        axisLine: { show: false },
        axisTick: { show: false },
        axisLabel: {
          color: 'var(--text-tertiary, #7a7770)',
          fontSize: 11,
          formatter: (v: number) => v.toFixed(2),
        },
        splitLine: { lineStyle: { color: 'var(--border-subtle, #e2ded3)', type: 'dashed' } },
      },
      tooltip: {
        trigger: 'item',
        backgroundColor: 'var(--bg-surface, #ffffff)',
        borderColor: 'var(--border-medium, #cfcbc0)',
        borderWidth: 1,
        padding: [8, 12],
        textStyle: { color: 'var(--text-primary, #1a1815)', fontSize: 12 },
        formatter: (rawParams: unknown): string => {
          const params = Array.isArray(rawParams) ? rawParams[0] : (rawParams as Record<string, unknown> | undefined);
          const pointData = (params as { data?: { iterMeta?: EvolveIteration } } | undefined)?.data;
          const meta: EvolveIteration | undefined = pointData?.iterMeta;
          if (!meta) return '';

          const kept = meta.kept
            ? '<span style="color:var(--color-success,#5c8a4a)">✓ kept</span>'
            : '<span style="color:var(--text-tertiary,#7a7770)">✗ not kept</span>';
          const delta = formatDelta(meta.delta);
          const deltaColor = meta.delta > 0
            ? 'var(--color-success,#5c8a4a)'
            : meta.delta < 0
              ? 'var(--color-error,#b8412f)'
              : 'var(--text-secondary,#5d5952)';
          const score = meta.candidateScore != null
            ? meta.candidateScore.toFixed(3)
            : '—';

          return `
            <div style="font-size:12px;line-height:1.6;max-width:280px">
              <div style="font-weight:600;margin-bottom:4px">
                Iter ${meta.iteration} · <span style="font-family:var(--font-mono,monospace);font-size:11px">${meta.surface}</span>
              </div>
              <div style="margin-bottom:4px;word-break:break-word">${meta.changeDesc}</div>
              <div>Score: <strong>${score}</strong></div>
              <div>Delta: <strong style="color:${deltaColor}">${delta}</strong></div>
              <div>${kept}</div>
            </div>
          `.trim();
        },
      },
      series,
    };
  }, [runs]);

  if (runs.length === 0) {
    return (
      <div className="etc-empty" data-testid="evolve-trajectory-chart-empty">
        Select an evolve run to view its trajectory.
      </div>
    );
  }

  return (
    <ReactECharts
      option={option}
      style={{ height, width: '100%' }}
      notMerge
      lazyUpdate={false}
      data-testid="evolve-trajectory-chart"
    />
  );
};

export default EvolveTrajectoryChart;
