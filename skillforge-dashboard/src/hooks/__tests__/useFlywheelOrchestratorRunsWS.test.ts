/**
 * OPT-LOOP-FRAMEWORK Sprint 4 — `useFlywheelOrchestratorRunsWS` hook tests.
 *
 * Covers Plan §5 vitest matrix cases 6-10 (including W3 race):
 *   6. flywheel_run_status_changed → setQueryData updates matching row
 *   7. flywheel_step_state_changed → setQueryData updates matching step
 *   8. 5 events within 200ms collapse to 1 setQueryData (debounce verify)
 *   9. W3 race — WS event + polling refetch arrive together, final state
 *      is consistent (last-write-wins; both converge)
 *  10. cleanup on unmount unsubscribes WS handler (frontend.md footgun #2)
 *
 * We stub `WebSocket` globally so messages can be injected synchronously,
 * and use `vi.useFakeTimers` for the debounce window.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import {
  useFlywheelOrchestratorRunsWS,
  WS_DEBOUNCE_MS,
} from '../useFlywheelOrchestratorRunsWS';
import {
  flywheelOrchestratorRunsQueryKey,
} from '../useFlywheelOrchestratorRuns';
import { flywheelOrchestratorRunDetailQueryKey } from '../useFlywheelOrchestratorRunDetail';
import type {
  FlywheelOrchestratorRunDetailResponse,
  FlywheelOrchestratorRunDto,
  FlywheelOrchestratorStepDto,
  ListFlywheelOrchestratorRunsResponse,
} from '../../api/flywheelOrchestratorRun';

// ───────────────────────── WS stub ────────────────────────────────────────
interface FakeWsHandle {
  url: string;
  listeners: Map<string, Array<(ev: { data: string }) => void>>;
  closed: boolean;
}

const wsInstances: FakeWsHandle[] = [];

class FakeWebSocket {
  url: string;
  // listeners
  private listeners = new Map<string, Array<(ev: { data: string }) => void>>();
  closed = false;
  constructor(url: string) {
    this.url = url;
    wsInstances.push({
      url,
      listeners: this.listeners,
      closed: false,
    } as FakeWsHandle);
    // Mutate the last pushed handle so external refs see the same listeners /
    // closed flag — wsInstances index === arr.length-1 at construction time.
    const handle = wsInstances[wsInstances.length - 1];
    Object.defineProperty(handle, 'closed', {
      get: () => this.closed,
      configurable: true,
    });
  }
  addEventListener(type: string, fn: (ev: { data: string }) => void) {
    const arr = this.listeners.get(type) ?? [];
    arr.push(fn);
    this.listeners.set(type, arr);
  }
  removeEventListener(type: string, fn: (ev: { data: string }) => void) {
    const arr = this.listeners.get(type);
    if (!arr) return;
    this.listeners.set(
      type,
      arr.filter((f) => f !== fn),
    );
  }
  close() {
    this.closed = true;
  }
  /** Manually inject a message — bypasses the actual network. */
  emit(payload: unknown) {
    const arr = this.listeners.get('message') ?? [];
    for (const fn of arr) fn({ data: JSON.stringify(payload) });
  }
}

beforeEach(() => {
  wsInstances.length = 0;
  (globalThis as unknown as { WebSocket: typeof FakeWebSocket }).WebSocket =
    FakeWebSocket;
  vi.useFakeTimers({ shouldAdvanceTime: false });
});

afterEach(() => {
  vi.useRealTimers();
});

// ───────────────────────── Helpers ────────────────────────────────────────
function makeRun(over: Partial<FlywheelOrchestratorRunDto> = {}): FlywheelOrchestratorRunDto {
  return {
    runId: 'r1',
    loopKind: 'opt_report',
    triggerSource: 'manual',
    agentId: 1,
    status: 'pending',
    errorReason: null,
    generatorSessionId: null,
    inputJson: null,
    summaryJson: null,
    windowStart: null,
    windowEnd: null,
    createdAt: '2026-05-28T00:00:00Z',
    updatedAt: '2026-05-28T00:00:00Z',
    ...over,
  };
}

function makeStep(over: Partial<FlywheelOrchestratorStepDto> = {}): FlywheelOrchestratorStepDto {
  return {
    stepRunId: 's1',
    runId: 'r1',
    stepKind: 'subagent_dispatch',
    status: 'pending',
    subAgentSessionId: null,
    stepInputJson: null,
    stepOutputJson: null,
    stepOutputCount: null,
    errorReason: null,
    createdAt: '2026-05-28T00:00:00Z',
    updatedAt: '2026-05-28T00:00:00Z',
    ...over,
  };
}

function listEnv(items: FlywheelOrchestratorRunDto[]): ListFlywheelOrchestratorRunsResponse {
  return { items, total: items.length, limit: 50, offset: 0 };
}

function detailEnv(
  run: FlywheelOrchestratorRunDto,
  steps: FlywheelOrchestratorStepDto[],
): FlywheelOrchestratorRunDetailResponse {
  return { run, steps };
}

function makeClient(): QueryClient {
  return new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
}

function makeWrapper(client: QueryClient) {
  return ({ children }: { children: React.ReactNode }) =>
    React.createElement(QueryClientProvider, { client }, children);
}

function lastWs(): FakeWsHandle {
  return wsInstances[wsInstances.length - 1];
}

function emitTo(ws: FakeWsHandle, payload: unknown) {
  const arr = ws.listeners.get('message') ?? [];
  for (const fn of arr) fn({ data: JSON.stringify(payload) });
}

describe('useFlywheelOrchestratorRunsWS', () => {
  it('flywheel_run_status_changed updates matching run in the list cache', async () => {
    // Plan §5 case 6
    const client = makeClient();
    const listKey = flywheelOrchestratorRunsQueryKey({});
    client.setQueryData(
      listKey,
      listEnv([makeRun({ runId: 'r1', status: 'pending' })]),
    );

    renderHook(
      () => useFlywheelOrchestratorRunsWS({ userId: 7, selectedRunId: null }),
      { wrapper: makeWrapper(client) },
    );

    expect(wsInstances.length).toBeGreaterThan(0);
    emitTo(lastWs(), {
      type: 'flywheel_run_status_changed',
      runId: 'r1',
      newStatus: 'running',
      timestamp: '2026-05-28T00:01:00Z',
    });

    // Advance debounce window
    await act(async () => {
      vi.advanceTimersByTime(WS_DEBOUNCE_MS + 10);
    });

    const updated = client.getQueryData<ListFlywheelOrchestratorRunsResponse>(listKey);
    expect(updated?.items[0].status).toBe('running');
    expect(updated?.items[0].updatedAt).toBe('2026-05-28T00:01:00Z');
  });

  it('flywheel_step_state_changed updates matching step in detail cache', async () => {
    // Plan §5 case 7
    const client = makeClient();
    const detailKey = flywheelOrchestratorRunDetailQueryKey('r1');
    client.setQueryData(
      detailKey,
      detailEnv(makeRun({ runId: 'r1', status: 'running' }), [
        makeStep({ stepRunId: 's1', status: 'pending' }),
        makeStep({ stepRunId: 's2', status: 'pending' }),
      ]),
    );

    renderHook(
      () => useFlywheelOrchestratorRunsWS({ userId: 7, selectedRunId: 'r1' }),
      { wrapper: makeWrapper(client) },
    );

    emitTo(lastWs(), {
      type: 'flywheel_step_state_changed',
      stepRunId: 's2',
      runId: 'r1',
      newStatus: 'completed',
    });

    await act(async () => {
      vi.advanceTimersByTime(WS_DEBOUNCE_MS + 10);
    });

    const updated = client.getQueryData<FlywheelOrchestratorRunDetailResponse>(detailKey);
    expect(updated?.steps[0].status).toBe('pending'); // untouched
    expect(updated?.steps[1].status).toBe('completed'); // updated
  });

  it('debounce: 5 events within 200ms collapse to one cache mutation', async () => {
    // Plan §5 case 8
    const client = makeClient();
    const listKey = flywheelOrchestratorRunsQueryKey({});
    client.setQueryData(
      listKey,
      listEnv([
        makeRun({ runId: 'r1', status: 'pending' }),
        makeRun({ runId: 'r2', status: 'pending' }),
        makeRun({ runId: 'r3', status: 'pending' }),
      ]),
    );

    // Spy on setQueriesData to count cache mutations.
    const spy = vi.spyOn(client, 'setQueriesData');

    renderHook(
      () => useFlywheelOrchestratorRunsWS({ userId: 7, selectedRunId: null }),
      { wrapper: makeWrapper(client) },
    );

    const ws = lastWs();
    // 5 rapid events well inside the 200ms window
    for (let i = 0; i < 5; i++) {
      emitTo(ws, {
        type: 'flywheel_run_status_changed',
        runId: `r${(i % 3) + 1}`,
        newStatus: 'running',
      });
      await act(async () => {
        vi.advanceTimersByTime(20); // 5 × 20ms = 100ms, all within 200ms
      });
    }

    expect(spy).not.toHaveBeenCalled(); // not flushed yet

    // Advance past the debounce
    await act(async () => {
      vi.advanceTimersByTime(WS_DEBOUNCE_MS + 10);
    });
    expect(spy).toHaveBeenCalledTimes(1);

    spy.mockRestore();
  });

  it('W3 race — WS event + polling refetch arriving together converge to consistent state', async () => {
    // Plan §5 case 9 / Plan §8 R2: simulate a polling refetch writing
    // canonical server state, then a WS event arriving just before/after.
    // Final state should be deterministic (last-write-wins) and either be
    // the WS-derived shape OR the server-derived shape — never a mix that
    // loses fields.
    const client = makeClient();
    const listKey = flywheelOrchestratorRunsQueryKey({});

    // Initial state from a "previous polling tick"
    client.setQueryData(
      listKey,
      listEnv([makeRun({ runId: 'r1', status: 'pending' })]),
    );

    renderHook(
      () => useFlywheelOrchestratorRunsWS({ userId: 7, selectedRunId: null }),
      { wrapper: makeWrapper(client) },
    );

    // (1) Polling refetch lands — server says still 'pending' (its DB read
    //     beat the BE state transition by a few ms).
    client.setQueryData(
      listKey,
      listEnv([makeRun({ runId: 'r1', status: 'pending', updatedAt: '2026-05-28T00:00:30Z' })]),
    );

    // (2) WS event arrives ~simultaneously with newer status.
    emitTo(lastWs(), {
      type: 'flywheel_run_status_changed',
      runId: 'r1',
      newStatus: 'running',
      timestamp: '2026-05-28T00:01:00Z',
    });
    await act(async () => {
      vi.advanceTimersByTime(WS_DEBOUNCE_MS + 10);
    });

    let final = client.getQueryData<ListFlywheelOrchestratorRunsResponse>(listKey);
    expect(final?.items[0].status).toBe('running');
    expect(final?.items[0].updatedAt).toBe('2026-05-28T00:01:00Z');

    // (3) Reverse race: a later polling tick brings the canonical server
    //     shape, which is what eventually persists.
    client.setQueryData(
      listKey,
      listEnv([
        makeRun({
          runId: 'r1',
          status: 'completed',
          updatedAt: '2026-05-28T00:02:00Z',
        }),
      ]),
    );
    final = client.getQueryData<ListFlywheelOrchestratorRunsResponse>(listKey);
    expect(final?.items[0].status).toBe('completed');
  });

  it('cleanup on unmount unsubscribes message handler and closes WS (footgun #2)', async () => {
    // Plan §5 case 10
    const client = makeClient();
    const listKey = flywheelOrchestratorRunsQueryKey({});
    client.setQueryData(
      listKey,
      listEnv([makeRun({ runId: 'r1', status: 'pending' })]),
    );

    const { unmount } = renderHook(
      () => useFlywheelOrchestratorRunsWS({ userId: 7, selectedRunId: null }),
      { wrapper: makeWrapper(client) },
    );

    const ws = lastWs();
    expect(ws.closed).toBe(false);
    expect((ws.listeners.get('message') ?? []).length).toBe(1);

    unmount();

    expect(ws.closed).toBe(true);
    expect((ws.listeners.get('message') ?? []).length).toBe(0);

    // After unmount, late events MUST NOT mutate the cache (the handler
    // was removed and pending events were dropped).
    emitTo(ws, {
      type: 'flywheel_run_status_changed',
      runId: 'r1',
      newStatus: 'completed',
    });
    await act(async () => {
      vi.advanceTimersByTime(WS_DEBOUNCE_MS + 10);
    });
    const after = client.getQueryData<ListFlywheelOrchestratorRunsResponse>(listKey);
    expect(after?.items[0].status).toBe('pending');
  });
});
