import React from 'react';
import { Tag, Tooltip, Button, Space, Spin, Typography } from 'antd';
import type { BehaviorRuleAbRun } from '../../api/behaviorRule';

const { Text } = Typography;

/**
 * Dual-criteria color thresholds — mirror BE constants
 * {@code BehaviorRuleAbEvalService.TARGET_DELTA_THRESHOLD_PP}=10 and
 * {@code REGRESSION_DELTA_FLOOR_PP}=-3. Kept in sync manually (tech-design §3.2);
 * BE is single source of truth for the dual-criteria gate decision (FE only
 * uses these for color buckets, never for the promote gate itself —
 * {@code run.dualCriteriaSatisfied} drives Promote enable/disable).
 */
export const TARGET_DELTA_GREEN_FLOOR_PP = 10.0;
export const REGRESSION_DELTA_FLOOR_PP = -3.0;

/**
 * Map a percentage-point delta to an AntD Tag color. Three buckets per the
 * brief:
 *   ≥ +10pp → green  ("strong improvement")
 *   0..10pp → gold   ("marginal — does not satisfy +10pp target threshold")
 *   < 0pp   → red    ("regression — would fail dual-criteria target gate")
 *   null    → default
 *
 * <p>Exported so the unit test can hit the boundary values directly without
 * rendering the whole row.
 */
export function deltaTagColor(deltaPp: number | null | undefined): string {
  if (deltaPp === null || deltaPp === undefined || Number.isNaN(deltaPp)) {
    return 'default';
  }
  if (deltaPp >= TARGET_DELTA_GREEN_FLOOR_PP) return 'green';
  if (deltaPp >= 0) return 'gold';
  return 'red';
}

/** Format pass-rate (BE returns 0..100 percent number) as percent string,
 *  falling back to em-dash. **Do NOT** multiply by 100 — BE
 *  baseline_pass_rate / candidate_pass_rate are already percent values
 *  (e.g. 81.6326530612). HOT-FIX commit cc7286b follow-up. */
function fmtPct(v: number | null | undefined): string {
  if (v === null || v === undefined || !Number.isFinite(v)) return '—';
  return `${v.toFixed(1)}%`;
}

/** Format delta-pp value as signed percent-point string. */
function fmtDeltaPp(v: number | null | undefined): string {
  if (v === null || v === undefined || !Number.isFinite(v)) return '—';
  const sign = v >= 0 ? '+' : '';
  return `${sign}${v.toFixed(1)}pp`;
}

/**
 * Build the disabled-Promote tooltip explaining which side of the dual-criteria
 * gate failed. Exported for the unit test (asserts label content per the brief
 * "disabled tooltip 文案"). Mirrors INV-5 phrasing:
 *   target_delta < +10pp AND non-null → "target subset improvement below +10pp"
 *   regression_delta < -3pp           → "regression subset slipped below -3pp"
 *   both null                         → "A/B not yet completed"
 */
export function dualCriteriaFailureReason(run: BehaviorRuleAbRun): string {
  if (run.status !== 'COMPLETED') {
    return 'A/B has not completed yet — Promote unlocks after the run finishes.';
  }
  const targetFails =
    run.targetDeltaPp !== null && run.targetDeltaPp < TARGET_DELTA_GREEN_FLOOR_PP;
  const regressionFails =
    run.regressionDeltaPp === null ||
    run.regressionDeltaPp < REGRESSION_DELTA_FLOOR_PP;
  const parts: string[] = [];
  if (targetFails) {
    parts.push(
      `target subset delta ${fmtDeltaPp(run.targetDeltaPp)} < +${TARGET_DELTA_GREEN_FLOOR_PP}pp`,
    );
  }
  if (regressionFails) {
    parts.push(
      `regression subset delta ${fmtDeltaPp(run.regressionDeltaPp)} < ${REGRESSION_DELTA_FLOOR_PP}pp`,
    );
  }
  if (parts.length === 0) {
    // Shouldn't happen if dualCriteriaSatisfied === false but be defensive.
    return 'Dual-criteria not satisfied (no specific subset failure detected).';
  }
  return `Dual-criteria not satisfied: ${parts.join('; ')}.`;
}

export interface BehaviorRuleAbBadgeProps {
  /** The latest ab_run for the candidate version (null when query is still
   *  loading OR when no ab_run exists yet for the version — caller passes
   *  `loading` separately to distinguish). */
  run: BehaviorRuleAbRun | null;
  /** True when the latest-ab-run query is in-flight. Renders a Spin. */
  loading: boolean;
  /** Wired to {@code behaviorRuleApi.promote(versionId)}. Caller handles
   *  invalidate + toast on success/error. Caller is responsible for ensuring
   *  the button is only clicked when {@code run.dualCriteriaSatisfied === true}
   *  (we still gate the button at the render layer for defense in depth). */
  onPromote: () => void;
  /** Wired to {@code behaviorRuleApi.runAb(versionId)}. Used by both
   *  no-run-yet and FAILED states. */
  onRetry: () => void;
  /** Wired to the parent's drawer open handler. Renders the per-scenario
   *  detail drawer with target/regression breakdown. */
  onOpenDetail: () => void;
  /** Disables both action buttons while the parent mutation is in flight. */
  busy?: boolean;
}

/**
 * BEHAVIOR-RULE-AB-EVAL V1 — Action cell renderer for behavior_rule rows in
 * the optimization-events timeline (PRD UC-1/UC-2/UC-3). Layered so the same
 * component drives:
 *   - PENDING / RUNNING        → small Spin + label
 *   - COMPLETED + criteria OK  → baseline/candidate/delta Tag + Promote button
 *   - COMPLETED + criteria fail→ same Tag + disabled Promote w/ tooltip
 *   - FAILED                   → red Tag + Retry button + failureReason tip
 *   - SUPERSEDED               → muted "superseded by newer run"
 *   - run === null && !loading → Retry button (auto-trigger may not have fired)
 *
 * <p>Pure presentational — parent owns query + mutation lifecycle. Behavior
 * gates (dualCriteriaSatisfied) come from BE, not recomputed here.
 */
export const BehaviorRuleAbBadge: React.FC<BehaviorRuleAbBadgeProps> = ({
  run,
  loading,
  onPromote,
  onRetry,
  onOpenDetail,
  busy,
}) => {
  if (loading) {
    return (
      <Space size="small">
        <Spin size="small" />
        <Text type="secondary" style={{ fontSize: 12 }}>
          loading…
        </Text>
      </Space>
    );
  }

  // No ab_run yet — auto-trigger may not have fired (race) or it failed
  // silently before the row hit candidate_ready. Surface Retry as escape hatch.
  if (!run) {
    return (
      <Tooltip title="No A/B run found for this candidate. Click to start one.">
        <Button size="small" onClick={onRetry} disabled={busy}>
          Retry A/B
        </Button>
      </Tooltip>
    );
  }

  if (run.status === 'PENDING' || run.status === 'RUNNING') {
    return (
      <Space size="small" onClick={onOpenDetail} style={{ cursor: 'pointer' }}>
        <Spin size="small" />
        <Text type="secondary" style={{ fontSize: 12 }}>
          running A/B…
        </Text>
      </Space>
    );
  }

  if (run.status === 'FAILED') {
    return (
      <Space size="small">
        <Tooltip title={run.failureReason ?? 'A/B run failed (no detail provided).'}>
          <Tag color="red">FAILED</Tag>
        </Tooltip>
        <Button size="small" onClick={onRetry} disabled={busy}>
          Retry A/B
        </Button>
      </Space>
    );
  }

  if (run.status === 'SUPERSEDED') {
    return (
      <Tooltip title="A newer A/B run replaced this one.">
        <Tag color="default">superseded</Tag>
      </Tooltip>
    );
  }

  // COMPLETED path.
  const baseline = fmtPct(run.baselinePassRate);
  const candidate = fmtPct(run.candidatePassRate);
  // Use targetDeltaPp for color in the primary case; fall back to
  // regressionDeltaPp when in fallback mode (targetDeltaPp == null per INV-4).
  const colorDelta = run.targetDeltaPp ?? run.regressionDeltaPp;
  const colorTag = deltaTagColor(colorDelta);

  const tagTooltip =
    run.targetDeltaPp === null
      ? `regression-only mode (target subset empty). regression delta = ${fmtDeltaPp(run.regressionDeltaPp)}.`
      : `target delta = ${fmtDeltaPp(run.targetDeltaPp)} | regression delta = ${fmtDeltaPp(run.regressionDeltaPp)}.`;

  const dualOk = run.dualCriteriaSatisfied === true;

  return (
    <Space size="small" wrap onClick={(e) => e.stopPropagation()}>
      <Tooltip title={tagTooltip}>
        <Tag
          color={colorTag}
          onClick={onOpenDetail}
          style={{ cursor: 'pointer', fontFamily: 'var(--font-mono, monospace)', fontSize: 11 }}
        >
          {baseline} → {candidate} ({fmtDeltaPp(colorDelta)})
        </Tag>
      </Tooltip>
      {run.promoted ? (
        <Tag color="blue">promoted</Tag>
      ) : dualOk ? (
        <Button
          type="primary"
          size="small"
          onClick={onPromote}
          disabled={busy}
        >
          Promote v1
        </Button>
      ) : (
        <Tooltip title={dualCriteriaFailureReason(run)}>
          {/* Wrap disabled button in span so Tooltip's hover trigger still fires
              (AntD Button with disabled=true swallows pointer events otherwise). */}
          <span>
            <Button
              type="primary"
              size="small"
              disabled
              // Explicit no-op to prevent event bubbling on the wrapper span.
              onClick={(e) => e.stopPropagation()}
            >
              Promote v1
            </Button>
          </span>
        </Tooltip>
      )}
    </Space>
  );
};

export default BehaviorRuleAbBadge;
