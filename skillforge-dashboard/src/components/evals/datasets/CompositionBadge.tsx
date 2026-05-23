import { Tag, Tooltip } from 'antd';
import type { CompositionStats } from '../../../api/evalDataset';
import { pickBaselineDisplay, formatBaselinePassRate } from '../../../api/evalDataset';

interface CompositionBadgeProps {
  stats?: CompositionStats | null;
  /** Optional actual rate that overrides the stats.expected when present. */
  actualBaselinePassRate?: number | null;
  /** Compact (table-cell) vs full (drawer header) presentation. */
  variant?: 'compact' | 'full';
}

/**
 * EVAL-DATASET-LAYER V1 — composition health badge.
 *
 * Renders a baseline pass-rate chip using the priority rule from
 * tech-design §1.3:
 *   actual (post A/B run write-back) > expected (heuristic) > "—"
 *
 * Color semantics:
 *   ≥30% → green   (healthy baseline, A/B run can resolve real delta)
 *   ≥10% → amber   (mediocre; warning surface)
 *    <10% → red    (likely "0% baseline → no delta signal" trap)
 *   none → grey   (no data; let operator run a baseline once)
 */
function CompositionBadge({ stats, actualBaselinePassRate, variant = 'compact' }: CompositionBadgeProps) {
  const expected = stats?.expected_baseline_pass_rate;
  const picked = pickBaselineDisplay(actualBaselinePassRate, expected);

  if (picked.kind === 'none') {
    return (
      <Tooltip title="No baseline rate yet — run A/B once to capture actual.">
        <Tag color="default">—</Tag>
      </Tooltip>
    );
  }

  const pct = (picked.value ?? 0) * 100;
  const color = pct >= 30 ? 'green' : pct >= 10 ? 'gold' : 'red';

  if (picked.kind === 'expected' && variant === 'compact') {
    return (
      <Tooltip
        title={`Estimated baseline pass rate (heuristic, may be ±30% off). Run an A/B to write back the actual value.`}
      >
        <Tag color={color} style={{ fontVariantNumeric: 'tabular-nums' }}>
          {formatBaselinePassRate(picked.value)} est
        </Tag>
      </Tooltip>
    );
  }

  if (picked.kind === 'actual' && variant === 'compact') {
    return (
      <Tooltip title="Actual baseline pass rate (last A/B run write-back).">
        <Tag color={color} style={{ fontVariantNumeric: 'tabular-nums' }}>
          {formatBaselinePassRate(picked.value)} actual
        </Tag>
      </Tooltip>
    );
  }

  // full variant — also show source breakdown chips
  const total = stats?.total ?? 0;
  return (
    <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6, alignItems: 'center' }}>
      <Tag color={color} style={{ fontVariantNumeric: 'tabular-nums' }}>
        {picked.kind === 'actual' ? 'Baseline ' : 'Baseline ~'}
        {formatBaselinePassRate(picked.value)}
        {picked.kind === 'expected' ? ' (est)' : ''}
      </Tag>
      {total > 0 && (
        <>
          <Tag>Total: {total}</Tag>
          {!!stats?.benchmark && <Tag color="blue">benchmark {stats.benchmark}</Tag>}
          {!!stats?.session_derived && <Tag color="purple">session-derived {stats.session_derived}</Tag>}
          {!!stats?.manual && <Tag color="cyan">manual {stats.manual}</Tag>}
        </>
      )}
    </div>
  );
}

export default CompositionBadge;
