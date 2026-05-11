import React, { useMemo } from 'react';
import ReactECharts from 'echarts-for-react';
import type { EvalHistoryEntry } from '../../api';

interface EvalHistoryChartProps {
  history: EvalHistoryEntry[] | undefined;
  loading?: boolean;
  /** Height of the echarts canvas in px. */
  height?: number;
}

interface SeriesSpec {
  key: keyof Pick<
    EvalHistoryEntry,
    'compositeScore' | 'qualityScore' | 'efficiencyScore' | 'latencyScore' | 'costScore'
  >;
  name: string;
  color: string;
  emphasis?: boolean;
}

const SERIES_SPECS: SeriesSpec[] = [
  // Composite is the primary metric — drawn last so it sits on top, and
  // emphasized via thicker stroke. The 4 sub-dimensions are dotted so the
  // composite reads first.
  { key: 'qualityScore', name: 'Quality', color: '#36b37e' },
  { key: 'efficiencyScore', name: 'Efficiency', color: '#ff9f43' },
  { key: 'latencyScore', name: 'Latency', color: '#6366f1' },
  { key: 'costScore', name: 'Cost', color: '#a78bfa' },
  { key: 'compositeScore', name: 'Composite', color: '#f0616d', emphasis: true },
];

/**
 * SKILL-EVOLVE-LOOP Phase 6 — multi-line chart of the 5-dim evaluation
 * scores over time. Reverses BE's DESC order so the curve reads left
 * (oldest) → right (newest).
 *
 * X-axis = `createdAt` (formatted compactly).
 * Y-axis = score 0-100 (composite + 4 sub-dims).
 *
 * Empty state stays visible so the operator knows the skill exists in
 * the registry but has no eval history yet (Phase 3 cron pending).
 *
 * `React.memo` per frontend.md — `history` reference is stable across
 * tab toggles inside the drawer (tanstack query returns the same array
 * ref until invalidate), so memo skips echarts re-renders on unrelated
 * drawer state changes.
 */
const EvalHistoryChartImpl: React.FC<EvalHistoryChartProps> = ({
  history,
  loading,
  height = 240,
}) => {
  const option = useMemo(() => {
    if (!history || history.length === 0) return null;
    // BE returns DESC; ASC for time-series.
    const asc = [...history].sort((a, b) => {
      const ta = new Date(a.createdAt).getTime();
      const tb = new Date(b.createdAt).getTime();
      return ta - tb;
    });

    const xLabels = asc.map((e) => {
      const d = new Date(e.createdAt);
      if (Number.isNaN(d.getTime())) return e.createdAt;
      // MM-DD HH:mm — short enough to fit ~10 points without rotation.
      const mm = String(d.getMonth() + 1).padStart(2, '0');
      const dd = String(d.getDate()).padStart(2, '0');
      const hh = String(d.getHours()).padStart(2, '0');
      const mi = String(d.getMinutes()).padStart(2, '0');
      return `${mm}-${dd} ${hh}:${mi}`;
    });

    const series = SERIES_SPECS.map((spec) => ({
      name: spec.name,
      type: 'line' as const,
      smooth: true,
      showSymbol: asc.length <= 12,
      symbolSize: spec.emphasis ? 5 : 3,
      lineStyle: spec.emphasis
        ? { width: 2.4, color: spec.color }
        : { width: 1.2, color: spec.color, type: 'dotted' as const, opacity: 0.85 },
      itemStyle: { color: spec.color },
      // emphasize composite by drawing it last (echarts paints in array order).
      z: spec.emphasis ? 10 : 1,
      // EVAL-V2 M4_V2 — `null` data points (e.g. latency when no threshold
      // was configured for that history row) MUST render as a gap, not as
      // 0. Echarts default is `connectNulls: false` which already drops
      // null points, but we set it explicitly so a future default flip
      // can't silently make the line touch the bottom axis and mislead
      // the operator into reading "0% latency score" instead of "not
      // measured this run".
      connectNulls: false,
      data: asc.map((e) => {
        const v = e[spec.key];
        return v == null ? null : Math.round(v * 10) / 10;
      }),
    }));

    return {
      tooltip: {
        trigger: 'axis' as const,
        axisPointer: { type: 'cross' as const },
      },
      legend: {
        data: SERIES_SPECS.map((s) => s.name),
        top: 0,
        textStyle: { fontSize: 11 },
      },
      grid: { left: 36, right: 16, top: 32, bottom: 28 },
      xAxis: {
        type: 'category' as const,
        boundaryGap: false,
        data: xLabels,
        axisLabel: { fontSize: 10, color: '#8a8a93' },
      },
      yAxis: {
        type: 'value' as const,
        min: 0,
        max: 100,
        interval: 20,
        axisLabel: { fontSize: 10, color: '#8a8a93' },
        splitLine: { lineStyle: { color: 'rgba(138,138,147,0.18)' } },
      },
      series,
    };
  }, [history]);

  if (loading) {
    return (
      <div className="sf-empty-state" style={{ minHeight: height }}>
        Loading eval history…
      </div>
    );
  }

  if (!option) {
    return (
      <div className="sf-empty-state" style={{ minHeight: height }}>
        No evaluation history yet — run "Evaluate Now" or wait for the Monday cron.
      </div>
    );
  }

  return <ReactECharts option={option} style={{ height }} notMerge lazyUpdate />;
};

export const EvalHistoryChart = React.memo(EvalHistoryChartImpl);
EvalHistoryChart.displayName = 'EvalHistoryChart';
