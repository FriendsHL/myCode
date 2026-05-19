/**
 * FLYWHEEL-VISUAL-STATUS Phase 2 — FlywheelObservabilityPanel renders.
 *
 * Three thin cases (per pipeline.md test scope — lock the contract, not the
 * implementation):
 *   1. Panel renders with header, tier-1 (agentType) and tier-2 (surface) tabs
 *   2. StepCard drill-down link points at the documented operate page
 *      (e.g. `/insights/patterns?tab=optimization&stage=proposal_pending`)
 *   3. Health dot encoding (PRD N3) — stale lag → red dot for cron step
 *
 * All BE-touching API helpers are mocked at module boundary so the panel
 * renders with deterministic StepMetrics without spinning up a real server.
 */
import React from 'react';
import { render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi, beforeEach } from 'vitest';

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

// ──────────────────────── module mocks ────────────────────────

vi.mock('../../../api/insights', () => ({
  listPatterns: vi.fn(() =>
    Promise.resolve({
      data: [
        {
          id: 1,
          signature: 'sig',
          outcome: 'failure',
          suspectSurface: 'skill',
          memberCount: 3,
          firstSeenAt: new Date(Date.now() - 60_000).toISOString(),
          lastSeenAt: new Date(Date.now() - 30_000).toISOString(),
          topFailingTool: null,
          agentId: null,
          suggestedSurface: null,
        },
      ],
    }),
  ),
}));

vi.mock('../../../api/attribution', () => ({
  listEvents: vi.fn(() =>
    Promise.resolve({
      data: {
        items: [
          {
            id: 42,
            patternId: 7,
            agentId: 3,
            surfaceType: 'skill',
            changeType: null,
            description: null,
            expectedImpact: null,
            confidence: null,
            risk: null,
            stage: 'proposal_pending',
            candidateSkillId: null,
            candidatePromptVersionId: null,
            abRunId: null,
            canaryId: null,
            attributionSessionId: null,
            cooldownUntil: null,
            createdAt: new Date(Date.now() - 5 * 60_000).toISOString(),
            updatedAt: new Date(Date.now() - 5 * 60_000).toISOString(),
          },
        ],
        page: 0,
        size: 200,
        total: 1,
      },
    }),
  ),
}));

vi.mock('../../../api/flywheel', () => ({
  listAbTestRunsGlobal: vi.fn(() => Promise.resolve({ data: { items: [] } })),
  listCanariesGlobal: vi.fn(() => Promise.resolve({ data: [] })),
}));

vi.mock('../../../api/systemAgents', () => ({
  getSystemAgentMonitor: vi.fn(() =>
    Promise.resolve({
      data: [
        {
          agentId: 1,
          name: 'session-annotator',
          description: null,
          cronExpression: '0 0 * * * *',
          // Make lag exactly 4 hours = stale relative to a 60-min cron
          // (lag > 3× cronInterval → 'stale' per PRD N3).
          lastRunAt: new Date(Date.now() - 4 * 60 * 60_000).toISOString(),
          lastRunStatus: 'success',
          sevenDayTriggerCount: 168,
          sevenDayOutputCount: 24,
          outputEntityType: 'annotations',
        },
      ],
    }),
  ),
}));

vi.mock('../../../api', async () => {
  const actual = await vi.importActual<Record<string, unknown>>('../../../api');
  return {
    ...actual,
    getSkillDrafts: vi.fn(() => Promise.resolve({ data: [] })),
    getSessions: vi.fn(() => Promise.resolve({ data: [] })),
    extractList: <T,>(res: { data: T[] | { items?: T[] } }): T[] => {
      if (Array.isArray(res.data)) return res.data;
      const items = (res.data as { items?: T[] }).items;
      return Array.isArray(items) ? items : [];
    },
  };
});

vi.mock('../../../contexts/AuthContext', () => ({
  useAuth: () => ({ token: null, userId: 1, login: vi.fn(), logout: vi.fn() }),
}));

// ──────────────────────── render helper ────────────────────────

import FlywheelObservabilityPanel from '../FlywheelObservabilityPanel';

function renderPanel() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, staleTime: Infinity } },
  });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/insights/patterns?tab=flywheel']}>
        <FlywheelObservabilityPanel />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  // Reset localStorage so the tab default is 'user'/'skill' on each test.
  window.localStorage.clear();
});

describe('FlywheelObservabilityPanel', () => {
  it('renders the panel shell with both tab tiers (a11y tablist semantics)', async () => {
    renderPanel();
    expect(await screen.findByText(/Flywheel observability/i)).toBeInTheDocument();
    const agentTabs = screen.getByTestId('agent-type-tabs');
    const surfaceTabs = screen.getByTestId('surface-tabs');
    // code-WARN-3 a11y — both tabs expose ARIA tablist semantics.
    expect(agentTabs).toHaveAttribute('role', 'tablist');
    expect(surfaceTabs).toHaveAttribute('role', 'tablist');
    // Default surface tab `skill` is active (role=tab now, not button).
    const skillTab = screen.getByRole('tab', { name: /^skill$/ });
    expect(skillTab).toHaveClass('on');
    expect(skillTab).toHaveAttribute('aria-selected', 'true');
  });

  it('renders drill-down link to the OptimizationEvents stage page for G1', async () => {
    renderPanel();
    const card = await screen.findByTestId('step-G1-approve-event');
    expect(card.tagName).toBe('A');
    expect(card.getAttribute('href')).toBe(
      '/insights/patterns?tab=optimization&stage=proposal_pending',
    );
  });

  it('marks the annotator cron step as stale when lag > 3× the cron interval', async () => {
    renderPanel();
    const card = await screen.findByTestId('step-step1-annotate');
    // Health dot inside the card carries data-health attr the test
    // asserts (PRD N3 encoding). Fixture above sets lastRunAt to 4h ago,
    // cron interval is 60min — that's > 3× → 'stale'.
    await waitFor(() => {
      const dot = card.querySelector('.fw-health-dot');
      expect(dot).not.toBeNull();
      expect(dot?.getAttribute('data-health')).toBe('stale');
    });
  });
});
