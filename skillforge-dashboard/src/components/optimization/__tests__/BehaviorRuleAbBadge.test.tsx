/**
 * BEHAVIOR-RULE-AB-EVAL V1 — unit coverage for the dual-criteria color/tooltip
 * boundaries on BehaviorRuleAbBadge. Targets §6 test plan row
 * "FE Unit / BehaviorRuleAbBadge.test.tsx — dual-criteria Tag 颜色边界 (delta -1
 * vs 5 vs 10 vs 15) / disabled tooltip 文案".
 */
import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import {
  BehaviorRuleAbBadge,
  deltaTagColor,
  dualCriteriaFailureReason,
  TARGET_DELTA_GREEN_FLOOR_PP,
  REGRESSION_DELTA_FLOOR_PP,
} from '../BehaviorRuleAbBadge';
import type { BehaviorRuleAbRun } from '../../../api/behaviorRule';

// AntD Tooltip relies on matchMedia + ResizeObserver in jsdom — polyfill them
// per the existing AttachmentThumbnail.test.tsx pattern.
if (!window.matchMedia) {
  window.matchMedia = (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => false,
  });
}
class ResizeObserverPolyfill {
  observe() {}
  unobserve() {}
  disconnect() {}
}
(globalThis as unknown as { ResizeObserver: typeof ResizeObserverPolyfill }).ResizeObserver =
  ResizeObserverPolyfill;

function makeRun(overrides: Partial<BehaviorRuleAbRun>): BehaviorRuleAbRun {
  return {
    id: 'ab-test-id',
    agentId: '1',
    candidateVersionId: 'v-1',
    status: 'COMPLETED',
    abRunKind: 'with_vs_without',
    baselinePassRate: 0.40,
    candidatePassRate: 0.55,
    deltaPassRate: 0.15,
    targetDeltaPp: 15,
    regressionDeltaPp: -1,
    targetCount: 5,
    regressionCount: 44,
    datasetVersionId: 'ds-v1',
    promoted: false,
    failureReason: null,
    startedAt: '2026-05-24T00:00:00Z',
    completedAt: '2026-05-24T00:05:00Z',
    dualCriteriaSatisfied: true,
    ...overrides,
  };
}

describe('deltaTagColor — dual-criteria boundary buckets', () => {
  // The brief calls out delta values -1, 5, 10, 15. Match exactly + cover
  // the null and NaN edge cases.
  it('returns "red" for delta < 0 (e.g. -1pp regression)', () => {
    expect(deltaTagColor(-1)).toBe('red');
    expect(deltaTagColor(-0.5)).toBe('red');
    expect(deltaTagColor(-99)).toBe('red');
  });

  it('returns "gold" for 0 ≤ delta < +10pp (e.g. +5pp marginal)', () => {
    expect(deltaTagColor(0)).toBe('gold');
    expect(deltaTagColor(5)).toBe('gold');
    expect(deltaTagColor(9.99)).toBe('gold');
  });

  it('returns "green" at the +10pp inclusive boundary (target threshold)', () => {
    expect(deltaTagColor(TARGET_DELTA_GREEN_FLOOR_PP)).toBe('green');
    expect(deltaTagColor(10)).toBe('green');
  });

  it('returns "green" for delta > +10pp (e.g. +15pp strong)', () => {
    expect(deltaTagColor(15)).toBe('green');
    expect(deltaTagColor(99)).toBe('green');
  });

  it('returns "default" for null / undefined / NaN', () => {
    expect(deltaTagColor(null)).toBe('default');
    expect(deltaTagColor(undefined)).toBe('default');
    expect(deltaTagColor(Number.NaN)).toBe('default');
  });

  // r2-FE-1 regression: drawer used a truthy `?` guard on deltaPassRate which
  // mapped 0 → null → "default" Tag → operators read "no data" instead of
  // "+0.0pp". Lock in the contract that 0 is a valid non-null bucket.
  it('treats 0 as a real "gold" bucket (NOT null) — r2-FE-1 regression guard', () => {
    expect(deltaTagColor(0)).toBe('gold');
    expect(deltaTagColor(0)).not.toBe('default');
    // Verify the same with the explicit `!= null` pattern callers now use:
    const zeroLikeNumeric: number | null = 0;
    expect(
      deltaTagColor(zeroLikeNumeric != null ? zeroLikeNumeric * 100 : null),
    ).toBe('gold');
  });
});

describe('dualCriteriaFailureReason — tooltip text per failed subset', () => {
  it('explains target-only failure when regression is fine', () => {
    const run = makeRun({
      targetDeltaPp: 5, // below +10pp threshold
      regressionDeltaPp: 0, // above -3 floor
      dualCriteriaSatisfied: false,
    });
    const msg = dualCriteriaFailureReason(run);
    expect(msg).toContain('target subset delta');
    expect(msg).toContain('+5.0pp');
    expect(msg).toContain(`< +${TARGET_DELTA_GREEN_FLOOR_PP}pp`);
    expect(msg).not.toContain('regression subset delta');
  });

  it('explains regression-only failure when target is fine', () => {
    const run = makeRun({
      targetDeltaPp: 20, // above threshold
      regressionDeltaPp: -5, // below -3 floor
      dualCriteriaSatisfied: false,
    });
    const msg = dualCriteriaFailureReason(run);
    expect(msg).toContain('regression subset delta');
    expect(msg).toContain('-5.0pp');
    expect(msg).toContain(`< ${REGRESSION_DELTA_FLOOR_PP}pp`);
    expect(msg).not.toContain('target subset delta');
  });

  it('lists both subsets when both fail', () => {
    const run = makeRun({
      targetDeltaPp: 3,
      regressionDeltaPp: -4,
      dualCriteriaSatisfied: false,
    });
    const msg = dualCriteriaFailureReason(run);
    expect(msg).toContain('target subset delta');
    expect(msg).toContain('regression subset delta');
  });

  it('hides specific subset and gives running-message when run is not COMPLETED', () => {
    const run = makeRun({ status: 'RUNNING', dualCriteriaSatisfied: null });
    const msg = dualCriteriaFailureReason(run);
    expect(msg).toContain('A/B has not completed yet');
    expect(msg).not.toContain('target subset');
  });

  it('handles fallback mode (target null, regression slipped)', () => {
    const run = makeRun({
      targetDeltaPp: null, // INV-4 fallback
      regressionDeltaPp: -10,
      dualCriteriaSatisfied: false,
    });
    const msg = dualCriteriaFailureReason(run);
    expect(msg).not.toContain('target subset delta');
    expect(msg).toContain('regression subset delta');
    expect(msg).toContain('-10.0pp');
  });
});

describe('BehaviorRuleAbBadge — render branches', () => {
  const noopHandlers = {
    onPromote: vi.fn(),
    onRetry: vi.fn(),
    onOpenDetail: vi.fn(),
  };

  it('renders Spin while loading', () => {
    const { container } = render(
      <BehaviorRuleAbBadge run={null} loading={true} {...noopHandlers} />,
    );
    expect(container.querySelector('.ant-spin')).toBeTruthy();
  });

  it('renders Retry button when no run exists yet (null + not loading)', () => {
    render(<BehaviorRuleAbBadge run={null} loading={false} {...noopHandlers} />);
    expect(screen.getByText('Retry A/B')).toBeInTheDocument();
  });

  it('renders RUNNING spinner branch', () => {
    const run = makeRun({ status: 'RUNNING', dualCriteriaSatisfied: null });
    render(<BehaviorRuleAbBadge run={run} loading={false} {...noopHandlers} />);
    expect(screen.getByText('running A/B…')).toBeInTheDocument();
  });

  it('renders Promote button enabled when dualCriteriaSatisfied is true', () => {
    const run = makeRun({ dualCriteriaSatisfied: true, promoted: false });
    render(<BehaviorRuleAbBadge run={run} loading={false} {...noopHandlers} />);
    const btn = screen.getByRole('button', { name: /Promote v1/i });
    expect(btn).toBeInTheDocument();
    expect(btn).not.toBeDisabled();
  });

  it('renders Promote button DISABLED when dualCriteriaSatisfied is false', () => {
    const run = makeRun({
      targetDeltaPp: 3,
      regressionDeltaPp: -5,
      dualCriteriaSatisfied: false,
    });
    render(<BehaviorRuleAbBadge run={run} loading={false} {...noopHandlers} />);
    const btn = screen.getByRole('button', { name: /Promote v1/i });
    expect(btn).toBeDisabled();
  });

  it('renders "promoted" Tag when run.promoted is true (no Promote button)', () => {
    const run = makeRun({ promoted: true, dualCriteriaSatisfied: true });
    render(<BehaviorRuleAbBadge run={run} loading={false} {...noopHandlers} />);
    expect(screen.getByText('promoted')).toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: /Promote v1/i }),
    ).not.toBeInTheDocument();
  });

  it('renders FAILED Tag + Retry button when status=FAILED', () => {
    const run = makeRun({
      status: 'FAILED',
      failureReason: 'eval pipeline crashed',
      dualCriteriaSatisfied: null,
    });
    render(<BehaviorRuleAbBadge run={run} loading={false} {...noopHandlers} />);
    expect(screen.getByText('FAILED')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Retry A\/B/i })).toBeInTheDocument();
  });

  it('renders SUPERSEDED Tag (no buttons)', () => {
    const run = makeRun({ status: 'SUPERSEDED', dualCriteriaSatisfied: null });
    render(<BehaviorRuleAbBadge run={run} loading={false} {...noopHandlers} />);
    expect(screen.getByText('superseded')).toBeInTheDocument();
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });
});
