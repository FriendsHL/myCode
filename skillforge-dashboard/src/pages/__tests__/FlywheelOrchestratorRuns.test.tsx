/**
 * OPT-LOOP-FRAMEWORK Sprint 4 — `/flywheel-runs` page integration tests (RTL).
 *
 * Covers Plan §5 vitest matrix cases 11-13:
 *  11. select run → detail + step timeline render
 *  12. error run → red Alert with errorReason
 *  13. empty list → Empty placeholder
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';

// jsdom: ResizeObserver / matchMedia / WebSocket polyfills required by Ant.
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
class FakeWs {
  addEventListener() {}
  removeEventListener() {}
  close() {}
}
(globalThis as unknown as { WebSocket: typeof FakeWs }).WebSocket = FakeWs;

const listMock = vi.fn();
const detailMock = vi.fn();
vi.mock('../../api/flywheelOrchestratorRun', () => ({
  listFlywheelOrchestratorRuns: (...args: unknown[]) => listMock(...(args as [])),
  getFlywheelOrchestratorRun: (...args: unknown[]) => detailMock(...(args as [])),
}));

vi.mock('../../contexts/AuthContext', () => ({
  useAuth: () => ({ token: null, userId: 1, login: vi.fn(), logout: vi.fn() }),
}));

import FlywheelOrchestratorRuns from '../FlywheelOrchestratorRuns';

function renderPage(initialUrl = '/flywheel-runs') {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, staleTime: Infinity } },
  });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[initialUrl]}>
        <FlywheelOrchestratorRuns />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

const baseRun = {
  loopKind: 'opt_report',
  triggerSource: 'manual',
  agentId: 1,
  errorReason: null,
  generatorSessionId: null,
  inputJson: null,
  summaryJson: null,
  windowStart: null,
  windowEnd: null,
  createdAt: '2026-05-28T00:00:00Z',
  updatedAt: '2026-05-28T00:01:00Z',
};

describe('FlywheelOrchestratorRunsPage', () => {
  beforeEach(() => {
    listMock.mockReset();
    detailMock.mockReset();
  });

  it('selecting a run shows detail metadata + step timeline', async () => {
    // Plan §5 case 11
    listMock.mockResolvedValue({
      data: {
        items: [{ ...baseRun, runId: 'run-1', status: 'completed' }],
        total: 1,
        limit: 20,
        offset: 0,
      },
    });
    // Detail includes a non-null generatorSessionId so the W1 link is exercised.
    detailMock.mockResolvedValue({
      data: {
        run: {
          ...baseRun,
          runId: 'run-1',
          status: 'completed',
          generatorSessionId: 'gen-sess-aaaaaaaa',
        },
        steps: [
          {
            stepRunId: 'step-1',
            runId: 'run-1',
            stepKind: 'subagent_dispatch',
            status: 'completed',
            subAgentSessionId: 'sess-aaaaaaaa',
            stepInputJson: null,
            stepOutputJson: null,
            stepOutputCount: 5,
            errorReason: null,
            createdAt: '2026-05-28T00:00:10Z',
            updatedAt: '2026-05-28T00:00:30Z',
          },
        ],
      },
    });

    renderPage();
    await waitFor(() => expect(screen.getByTestId('flywheel-orch-list')).toBeInTheDocument());

    // Click first row
    const row = await screen.findByTestId('flywheel-orch-row-run-1');
    fireEvent.click(row);

    // Detail pane should populate
    await waitFor(() => expect(screen.getByTestId('flywheel-orch-detail')).toBeInTheDocument());
    // Step timeline should render
    await waitFor(() => expect(screen.getByTestId('flywheel-orch-timeline')).toBeInTheDocument());
    expect(screen.getByTestId('flywheel-orch-step-step-1')).toBeInTheDocument();
    // W1 (plan-review): generatorSessionId is rendered as a clickable link
    // pointing at /sessions/{id}.
    const genLink = screen.getByTestId('flywheel-orch-detail-generator-session-link');
    expect(genLink.getAttribute('href')).toBe('/sessions/gen-sess-aaaaaaaa');
  });

  it('error run renders red error Alert with errorReason', async () => {
    // Plan §5 case 12
    listMock.mockResolvedValue({
      data: {
        items: [{ ...baseRun, runId: 'run-err', status: 'error', errorReason: 'boom: 503' }],
        total: 1,
        limit: 20,
        offset: 0,
      },
    });
    detailMock.mockResolvedValue({
      data: {
        run: { ...baseRun, runId: 'run-err', status: 'error', errorReason: 'boom: 503' },
        steps: [],
      },
    });

    renderPage('/flywheel-runs?runId=run-err');

    await waitFor(() =>
      expect(screen.getByTestId('flywheel-orch-detail-error-alert')).toBeInTheDocument(),
    );
    expect(screen.getByText(/boom: 503/)).toBeInTheDocument();
  });

  it('empty list renders the Empty placeholder', async () => {
    // Plan §5 case 13
    listMock.mockResolvedValue({
      data: { items: [], total: 0, limit: 20, offset: 0 },
    });
    renderPage();
    await waitFor(() =>
      expect(screen.getByTestId('flywheel-orch-list-empty')).toBeInTheDocument(),
    );
  });

  it('renders responsive grid container with the .flywheel-runs-grid class (W-FE-4)', async () => {
    // W-FE-4 mandatory: verify the CSS class hook is in the DOM so the
    // FlywheelOrchestratorRuns.css media queries can target it. jsdom can't
    // exercise media queries themselves; this assertion locks down the
    // class-name contract so the CSS rules find their hook in production.
    listMock.mockResolvedValue({
      data: { items: [], total: 0, limit: 20, offset: 0 },
    });
    const { container } = renderPage();
    await waitFor(() =>
      expect(screen.getByTestId('flywheel-orch-list-empty')).toBeInTheDocument(),
    );
    const grid = container.querySelector('.flywheel-runs-grid');
    expect(grid).not.toBeNull();
    // All three panes carry the class hook (CSS targets the children via it).
    expect(container.querySelector('.flywheel-runs-pane--filters')).not.toBeNull();
    expect(container.querySelector('.flywheel-runs-pane--detail')).not.toBeNull();
    expect(container.querySelector('.flywheel-runs-pane--timeline')).not.toBeNull();
  });
});
