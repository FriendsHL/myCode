import React from 'react';
import { Tooltip } from 'antd';
import type { EvalHistoryEntry } from '../../api';
import { visualForScore } from './evalScore';

interface SparklineCellProps {
  /**
   * History rows ordered any way — this component re-sorts ASC by
   * `createdAt` internally so the line reads left (oldest) → right (newest).
   * BE returns DESC so don't trust caller order.
   */
  history: EvalHistoryEntry[] | undefined;
  width?: number;
  height?: number;
}

/**
 * Inline SVG sparkline of recent composite scores. Inline SVG (not echarts)
 * because rendering N echarts instances per table row is heavy and the
 * sparkline only needs a polyline + the most-recent endpoint dot.
 *
 * Stroke colour follows the latest score's bucket (good/warn/bad) so the
 * Trend column reads as "is this skill currently healthy" at a glance.
 *
 * `React.memo` per frontend.md §"组件规范" — pure display component
 * rendered N times in the table body; default referential equality on
 * `history` is enough since `SkillTable` reads from a stable `Map`.
 */
const SparklineCellImpl: React.FC<SparklineCellProps> = ({
  history,
  width = 80,
  height = 22,
}) => {
  if (!history || history.length === 0) {
    return (
      <span style={{ color: 'var(--fg-4, #8a8a93)', fontSize: 11 }}>—</span>
    );
  }

  // Sort ASC by createdAt so oldest is on the left.
  const asc = [...history].sort((a, b) => {
    const ta = new Date(a.createdAt).getTime();
    const tb = new Date(b.createdAt).getTime();
    return ta - tb;
  });

  // Single point — render as a centered dot using the same colour bucket.
  if (asc.length === 1) {
    const v = visualForScore(asc[0].compositeScore);
    return (
      <Tooltip title={`Score ${Math.round(asc[0].compositeScore)} (single sample)`}>
        <svg width={width} height={height} aria-label="trend-sparkline">
          <circle cx={width / 2} cy={height / 2} r={2.5} fill={v.stroke} />
        </svg>
      </Tooltip>
    );
  }

  const xs = asc.map((_, i) => i);
  const ys = asc.map((e) => e.compositeScore);
  // Domain: clamp y to [0, 100] so an early-volatile skill stays comparable
  // to a steady one; without clamping, autoscaling makes a flat 50→55 line
  // look just as dramatic as a 10→90 swing.
  const yMin = 0;
  const yMax = 100;
  const xMin = 0;
  const xMax = xs.length - 1;

  const pad = 2;
  const innerW = width - pad * 2;
  const innerH = height - pad * 2;
  const sx = (x: number) => pad + ((x - xMin) / Math.max(1, xMax - xMin)) * innerW;
  const sy = (y: number) =>
    pad + (1 - (y - yMin) / (yMax - yMin)) * innerH;

  const points = asc.map((_, i) => `${sx(xs[i])},${sy(ys[i])}`).join(' ');
  const lastVisual = visualForScore(asc[asc.length - 1].compositeScore);

  const tooltipText = `Recent ${asc.length} scores: ${ys.map((s) => Math.round(s)).join(' → ')}`;

  return (
    <Tooltip title={tooltipText}>
      <svg width={width} height={height} aria-label="trend-sparkline">
        <polyline
          points={points}
          fill="none"
          stroke={lastVisual.stroke}
          strokeWidth={1.5}
          strokeLinecap="round"
          strokeLinejoin="round"
        />
        {/* End-point dot — emphasises "latest" reading. */}
        <circle
          cx={sx(xs[xs.length - 1])}
          cy={sy(ys[ys.length - 1])}
          r={2}
          fill={lastVisual.stroke}
        />
      </svg>
    </Tooltip>
  );
};

export const SparklineCell = React.memo(SparklineCellImpl);
SparklineCell.displayName = 'SparklineCell';
