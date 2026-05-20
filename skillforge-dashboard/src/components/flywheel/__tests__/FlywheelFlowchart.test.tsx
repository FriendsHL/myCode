/**
 * FLYWHEEL-FLOWCHART — FlywheelFlowchart renders.
 *
 * Five cases (per pipeline.md — lock the visible contract, not the
 * implementation):
 *   1. Panel renders the DAG shell (header + both tab tiers + flowchart
 *      container)
 *   2. Catalogue steps render as React Flow nodes (at least the 4 entry
 *      nodes for the default user/skill surface)
 *   3. Drill-down link inside a node points at the documented operate page
 *      (G1 → OptimizationEvents proposal_pending)
 *   4. `fw-node--running` className is applied when isRunning is true
 *      (in-flight > 0 on an AUTO node) and absent on USER gates even
 *      when they have a pending count
 *   5. `prefers-reduced-motion` query exists in the cascade — verified
 *      indirectly via the `.fw-node--running` rule's presence with an
 *      `outline` fallback (smoke test; deep media-query mocking is out
 *      of scope for vitest+jsdom)
 *
 * Module mocks at API boundary — no real BE; deterministic StepMetrics
 * inputs let us assert on running flags + edge animation classes.
 */
import React from 'react';
import { render, screen, waitFor, within, fireEvent } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi, beforeEach } from 'vitest';

// React Flow uses ResizeObserver + matchMedia (via Background, fitView, etc.)
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
// jsdom doesn't implement DOMMatrix / DOMRect APIs React Flow internals
// occasionally probe — supply minimal stubs so the import doesn't crash.
if (!(globalThis as unknown as { DOMMatrixReadOnly?: unknown }).DOMMatrixReadOnly) {
  class DOMMatrixReadOnly {
    m22 = 1;
    constructor() {}
  }
  (globalThis as unknown as { DOMMatrixReadOnly: typeof DOMMatrixReadOnly }).DOMMatrixReadOnly =
    DOMMatrixReadOnly;
}

// ──────────────────────── module mocks ────────────────────────

// One pattern (step2-cluster will report inFlight=1 → AUTO running pulse).
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

// One attribution event in proposal_pending stage (drives G1 PEND chip).
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
  listSkillDraftsBySource: vi.fn(() => Promise.resolve({ data: [] })),
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
          // 30 min ago vs 60-min cron → healthy lag.
          lastRunAt: new Date(Date.now() - 30 * 60_000).toISOString(),
          // 'running' status → flowchart should pulse this AUTO node.
          lastRunStatus: 'running',
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

import FlywheelFlowchart from '../FlywheelFlowchart';

function renderChart() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, staleTime: Infinity } },
  });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/insights/patterns?tab=flywheel']}>
        <FlywheelFlowchart />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  window.localStorage.clear();
});

describe('FlywheelFlowchart', () => {
  it('renders the panel shell with both tab tiers + flowchart container', async () => {
    renderChart();
    expect(await screen.findByText(/Flywheel observability/i)).toBeInTheDocument();
    expect(screen.getByTestId('agent-type-tabs')).toHaveAttribute(
      'role',
      'tablist',
    );
    expect(screen.getByTestId('surface-tabs')).toHaveAttribute(
      'role',
      'tablist',
    );
    expect(screen.getByTestId('flywheel-flowchart')).toBeInTheDocument();
    // Default active surface = `skill`.
    const skillTab = screen.getByRole('tab', { name: /^skill$/ });
    expect(skillTab).toHaveClass('on');
    expect(skillTab).toHaveAttribute('aria-selected', 'true');
  });

  it('renders catalogue steps as React Flow nodes for user/skill surface', async () => {
    renderChart();
    // Wait for the data hook to populate the DAG.
    await waitFor(() => {
      expect(screen.getByTestId('fw-node-E1-user-chat')).toBeInTheDocument();
    });
    // E1-E4 entries + the pipeline steps should all show up — sample a few.
    expect(screen.getByTestId('fw-node-E2-upload-skill')).toBeInTheDocument();
    expect(screen.getByTestId('fw-node-step1-annotate')).toBeInTheDocument();
    expect(screen.getByTestId('fw-node-G1-approve-event')).toBeInTheDocument();
    expect(screen.getByTestId('fw-node-step5-abtest')).toBeInTheDocument();
    expect(screen.getByTestId('fw-node-step9-decide')).toBeInTheDocument();
  });

  it('click G1 card → detail Drawer opens with Chinese label + drill-down link footer', async () => {
    renderChart();
    const node = await screen.findByTestId('fw-node-G1-approve-event');
    const card = within(node).getByTestId('step-G1-approve-event');
    // Card is now a <button> (was <a> before drawer landed) — verify the
    // click target is keyboard-friendly + emits onSelect to open drawer.
    expect(card.tagName).toBe('BUTTON');
    fireEvent.click(card);
    // Drawer mounts with Chinese label visible.
    const drawer = await screen.findByTestId('fw-drawer');
    expect(drawer.getAttribute('data-step-id')).toBe('G1-approve-event');
    expect(within(drawer).getByText('G1 · 审 OptEvent')).toBeInTheDocument();
    // Drill-down link is on the Drawer footer, pointing at the operate page.
    const drill = within(drawer).getByTestId('fw-drawer-drill-link');
    expect(drill.tagName).toBe('A');
    expect(drill.getAttribute('href')).toBe(
      '/insights/patterns?tab=optimization&stage=proposal_pending',
    );
  });

  it('Chinese labels render on the cards (E1 / ① 标注 / G1)', async () => {
    renderChart();
    await waitFor(() => {
      expect(screen.getByTestId('fw-node-E1-user-chat')).toBeInTheDocument();
    });
    // Sample one of each node type — confirm labelCn replaced English title.
    expect(screen.getByText('E1 · 用户聊天')).toBeInTheDocument();
    expect(screen.getByText('① 标注')).toBeInTheDocument();
    expect(screen.getByText('G1 · 审 OptEvent')).toBeInTheDocument();
    // Old English titles should NOT appear in the rendered cards.
    expect(screen.queryByText('E1 · user chat session')).not.toBeInTheDocument();
    expect(screen.queryByText('① annotate · session-annotator')).not.toBeInTheDocument();
  });

  it('Drawer Esc-close + backdrop-close', async () => {
    renderChart();
    const node = await screen.findByTestId('fw-node-step1-annotate');
    fireEvent.click(within(node).getByTestId('step-step1-annotate'));
    expect(await screen.findByTestId('fw-drawer')).toBeInTheDocument();
    // Esc closes.
    fireEvent.keyDown(window, { key: 'Escape' });
    await waitFor(() => {
      expect(screen.queryByTestId('fw-drawer')).not.toBeInTheDocument();
    });
    // Re-open + backdrop click closes.
    fireEvent.click(within(node).getByTestId('step-step1-annotate'));
    const drawer2 = await screen.findByTestId('fw-drawer');
    expect(drawer2).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('fw-drawer-backdrop'));
    await waitFor(() => {
      expect(screen.queryByTestId('fw-drawer')).not.toBeInTheDocument();
    });
  });

  it('applies fw-node--running on AUTO nodes with in-flight or running cron, but NOT on USER gates', async () => {
    renderChart();
    // F2 — symmetric assertions: BOTH AUTO running nodes check
    // data-running AND className, both wrapped in waitFor so the async
    // hook tick doesn't race the assertion.
    // step1-annotate: lastRunStatus='running' from monitor mock → pulse on.
    const annotator = await screen.findByTestId('fw-node-step1-annotate');
    await waitFor(() => {
      expect(annotator.getAttribute('data-running')).toBe('true');
      expect(annotator.className).toContain('fw-node--running');
    });
    // step2-cluster: inFlight=1 (one pattern) → AUTO node should also pulse.
    const cluster = await screen.findByTestId('fw-node-step2-cluster');
    await waitFor(() => {
      expect(cluster.getAttribute('data-running')).toBe('true');
      expect(cluster.className).toContain('fw-node--running');
    });
    // G1: USER gate with pending count, but USER nodes never pulse.
    const g1 = screen.getByTestId('fw-node-G1-approve-event');
    expect(g1.getAttribute('data-running')).toBe('false');
    expect(g1.className).not.toContain('fw-node--running');
    // E1: ENTRY nodes also never pulse.
    const e1 = screen.getByTestId('fw-node-E1-user-chat');
    expect(e1.getAttribute('data-running')).toBe('false');
    expect(e1.className).not.toContain('fw-node--running');
  });

  // F3 — was an `expect(true).toBe(true)` no-op (jsdom can't actually flip
  // matchMedia at runtime, so we can't trigger the prefers-reduced-motion
  // cascade in unit tests). Marked as `it.todo` so CI surfaces the gap as
  // an unimplemented test instead of a silent pass; verification belongs
  // in a Playwright/browser e2e where the OS-level reduce-motion can be
  // emulated via `page.emulateMedia({ reducedMotion: 'reduce' })`.
  it.todo(
    'TODO: reduced-motion CSS rule applied — needs Playwright/browser harness to verify, jsdom matchMedia limitation',
  );
});
