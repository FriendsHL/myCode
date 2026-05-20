/**
 * FLYWHEEL-VISUAL-STATUS Phase 2 (1B URL routing) — Insights.tsx must read
 * `?tab=…` URL param on mount and write changes back when the operator
 * clicks a tab. Two cases:
 *   1. `?tab=optimization` mount → OptimizationEvents panel rendered
 *   2. Clicking the Flywheel tab → URL updates to `?tab=flywheel`
 */
import React from 'react';
import { act, render, screen, fireEvent } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Routes, Route, useLocation } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

class ResizeObserverPolyfill {
  observe() {}
  unobserve() {}
  disconnect() {}
}
(globalThis as unknown as { ResizeObserver: typeof ResizeObserverPolyfill }).ResizeObserver =
  ResizeObserverPolyfill;
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

// Heavy sub-page mocks — only assert which one Insights mounts; we don't
// exercise their internals. The mocks expose a known test id so we can
// assert "this tab was actually rendered".
//
// ts-B3 regression — the OptimizationEvents mock also surfaces the
// `initialStageFilter` / `initialAgentIdFilter` props on data-attrs so
// the regression test below can assert Insights passed `?stage=` /
// `?agentId=` URL params through (pre-fix, they were silently dropped
// and the flywheel G1/G3 drill-down landed on an unfiltered table —
// PRD R3 真消费 violation).
vi.mock('../OptimizationEvents', () => ({
  default: (props: {
    initialStageFilter?: string;
    initialAgentIdFilter?: number;
  }) => (
    <div
      data-testid="opt-events-mock"
      data-initial-stage={props.initialStageFilter ?? ''}
      data-initial-agent-id={
        props.initialAgentIdFilter !== undefined
          ? String(props.initialAgentIdFilter)
          : ''
      }
    >
      Optimization mock
    </div>
  ),
}));
vi.mock('../BehaviorRuleEvolution', () => ({
  default: () => <div data-testid="behavior-rule-evo-mock">BR mock</div>,
}));
vi.mock('../DynamicSim', () => ({
  default: () => <div data-testid="dynsim-mock">Dyn mock</div>,
}));
vi.mock('../FlywheelObservability', () => ({
  default: () => <div data-testid="flywheel-mock">Flywheel mock</div>,
}));

// The default patterns tab needs listPatterns to not blow up.
vi.mock('../../api/insights', () => ({
  listPatterns: vi.fn(() => Promise.resolve({ data: [] })),
  listPatternMembers: vi.fn(() => Promise.resolve({ data: [] })),
}));

// PatternList and PatternDetailDrawer mocked so the patterns-tab branch
// doesn't pull a deep render tree in.
vi.mock('../../components/insights/PatternList', () => ({
  default: () => <div data-testid="pattern-list-mock" />,
}));
vi.mock('../../components/insights/PatternDetailDrawer', () => ({
  default: () => <div />,
}));

import Insights from '../Insights';

function LocationProbe() {
  const loc = useLocation();
  return (
    <div data-testid="location-probe">{`${loc.pathname}${loc.search}`}</div>
  );
}

function renderWithRoute(initialPath: string) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, staleTime: Infinity } },
  });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[initialPath]}>
        <Routes>
          <Route
            path="/insights/patterns"
            element={
              <>
                <Insights />
                <LocationProbe />
              </>
            }
          />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  window.localStorage.clear();
});

describe('Insights — URL-driven tab routing (1B)', () => {
  it('hydrates the OptimizationEvents tab from `?tab=optimization`', async () => {
    renderWithRoute('/insights/patterns?tab=optimization');
    expect(await screen.findByTestId('opt-events-mock')).toBeInTheDocument();
  });

  it('passes `?stage=` / `?agentId=` URL params into OptimizationEvents (ts-B3)', async () => {
    renderWithRoute(
      '/insights/patterns?tab=optimization&stage=proposal_pending&agentId=42',
    );
    const mock = await screen.findByTestId('opt-events-mock');
    expect(mock.getAttribute('data-initial-stage')).toBe('proposal_pending');
    expect(mock.getAttribute('data-initial-agent-id')).toBe('42');
  });

  it('rejects unknown ?stage= values (allowlist gate)', async () => {
    renderWithRoute(
      '/insights/patterns?tab=optimization&stage=not_a_real_stage',
    );
    const mock = await screen.findByTestId('opt-events-mock');
    // Unknown stage falls through to undefined so the BE-side filter
    // isn't poisoned with a junk literal that would yield 0 rows.
    expect(mock.getAttribute('data-initial-stage')).toBe('');
  });

  it('clicking the Flywheel tab writes back ?tab=flywheel to the URL', async () => {
    renderWithRoute('/insights/patterns');
    // Default tab is `patterns` (Pattern List visible).
    expect(screen.getByTestId('pattern-list-mock')).toBeInTheDocument();
    const flywheelTabBtn = screen.getByRole('button', { name: /optimization loop/i });
    await act(async () => {
      fireEvent.click(flywheelTabBtn);
    });
    expect(screen.getByTestId('flywheel-mock')).toBeInTheDocument();
    const loc = screen.getByTestId('location-probe').textContent ?? '';
    expect(loc).toContain('tab=flywheel');
  });
});
