/**
 * OPT-LOOP-FRAMEWORK Sprint 4 — WS push hook for the `/flywheel-runs`
 * page (Plan §2 D4 + §8 R2).
 *
 * Subscribes to the user-level WS feed (`/ws/users/{userId}`) and patches
 * the react-query cache when `flywheel_run_status_changed` /
 * `flywheel_step_state_changed` events arrive. We use `setQueryData`
 * (local patch) — NOT `invalidateQueries` — so a 5-step fan-out's 5
 * status events don't trigger 5 list refetches.
 *
 * Why debounce (200ms): when an orchestrator fans out 5+ SubAgents the BE
 * fires `flywheel_step_state_changed` for each one in a tight burst. We
 * stash incoming events on a ref and flush them on a 200ms debounce so
 * the cache mutation happens once per burst.
 *
 * Why local patch + polling co-exist (Plan §2 D5 / §8 R2 race): WS is
 * unreliable in production (proxy reconnects, async pool saturation), so
 * `useFlywheelOrchestratorRuns` keeps polling at 5s when any row is
 * non-terminal. The race "WS event arrives + polling fetch lands together"
 * is benign — `setQueryData` writes the WS-derived shape and the next
 * fetch overwrites with the canonical server shape; both converge.
 *
 * Frontend.md footgun #2 — `useEffect` cleanup MUST close the WS socket
 * AND cancel the pending debounced flush, otherwise unmounted components
 * still patch the cache when a delayed event arrives.
 */
import { useEffect, useRef } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { useDebouncedCallback } from './useDebouncedCallback';
import {
  flywheelOrchestratorRunDetailQueryKey,
} from './useFlywheelOrchestratorRunDetail';
import type {
  FlywheelOrchestratorRunDetailResponse,
  FlywheelOrchestratorRunDto,
  FlywheelOrchestratorStepDto,
  ListFlywheelOrchestratorRunsResponse,
} from '../api/flywheelOrchestratorRun';

/** Debounce window for batching bursts of step / run events. */
export const WS_DEBOUNCE_MS = 200;

/** Top-level WS event shapes the hook reacts to. */
interface RunStatusChangedEvent {
  type: 'flywheel_run_status_changed';
  runId: string;
  loopKind?: string;
  agentId?: number | null;
  oldStatus?: string;
  newStatus: string;
  timestamp?: string;
  errorReason?: string | null;
}

interface StepStateChangedEvent {
  type: 'flywheel_step_state_changed';
  stepRunId: string;
  runId: string;
  oldStatus?: string;
  newStatus: string;
  errorReason?: string | null;
}

type BufferedEvent = RunStatusChangedEvent | StepStateChangedEvent;

interface UseFlywheelOrchestratorRunsWSParams {
  /** User id used to build the WS URL. `null` while auth is loading. */
  userId: number | string | null;
  /** Currently selected run id (for detail-query patches). `null` if none. */
  selectedRunId: string | null;
}

/**
 * Apply a buffered list of WS events to whatever flywheel-orchestrator-runs
 * list queries are currently in the react-query cache. Each list query is
 * keyed by `['flywheel-orchestrator-runs', filterParams]` so we use the
 * partial prefix match via `queryKey: ['flywheel-orchestrator-runs']` to
 * find them all (any filter combination).
 */
function patchListCache(
  events: BufferedEvent[],
  setQueriesData: (
    filter: { queryKey: readonly unknown[] },
    updater: (prev: ListFlywheelOrchestratorRunsResponse | undefined) =>
      | ListFlywheelOrchestratorRunsResponse
      | undefined,
  ) => void,
) {
  // Collect the latest newStatus per runId in the batch (last-write-wins
  // within a burst).
  const latestRunStatus = new Map<string, RunStatusChangedEvent>();
  for (const ev of events) {
    if (ev.type === 'flywheel_run_status_changed') {
      latestRunStatus.set(ev.runId, ev);
    }
  }
  if (latestRunStatus.size === 0) return;

  setQueriesData(
    { queryKey: ['flywheel-orchestrator-runs'] },
    (prev) => {
      if (!prev) return prev;
      let mutated = false;
      const nextItems = prev.items.map((row): FlywheelOrchestratorRunDto => {
        const ev = latestRunStatus.get(row.runId);
        if (!ev) return row;
        mutated = true;
        return {
          ...row,
          status: ev.newStatus,
          errorReason: ev.errorReason ?? row.errorReason,
          // Best-effort `updatedAt` bump so the UI's "updated X ago" stays
          // honest until the next polling tick brings the canonical value.
          updatedAt: ev.timestamp ?? row.updatedAt,
        };
      });
      return mutated ? { ...prev, items: nextItems } : prev;
    },
  );
}

/**
 * Patch the currently-selected detail query (if any) when matching run /
 * step events arrive. We only touch one detail query at a time because
 * only one is enabled (selectedRunId !== null).
 */
function patchDetailCache(
  events: BufferedEvent[],
  selectedRunId: string | null,
  setQueryData: (
    queryKey: readonly unknown[],
    updater: (prev: FlywheelOrchestratorRunDetailResponse | undefined) =>
      | FlywheelOrchestratorRunDetailResponse
      | undefined,
  ) => void,
) {
  if (selectedRunId === null) return;

  // Filter events to the selected run, keep ordering, last-write-wins per id.
  const runEvent: RunStatusChangedEvent | null = (() => {
    let latest: RunStatusChangedEvent | null = null;
    for (const ev of events) {
      if (ev.type === 'flywheel_run_status_changed' && ev.runId === selectedRunId) {
        latest = ev;
      }
    }
    return latest;
  })();
  const stepEvents = new Map<string, StepStateChangedEvent>();
  for (const ev of events) {
    if (ev.type === 'flywheel_step_state_changed' && ev.runId === selectedRunId) {
      stepEvents.set(ev.stepRunId, ev);
    }
  }

  if (runEvent === null && stepEvents.size === 0) return;

  setQueryData(
    flywheelOrchestratorRunDetailQueryKey(selectedRunId),
    (prev) => {
      if (!prev) return prev;
      let nextRun: FlywheelOrchestratorRunDto = prev.run;
      if (runEvent) {
        nextRun = {
          ...prev.run,
          status: runEvent.newStatus,
          errorReason: runEvent.errorReason ?? prev.run.errorReason,
          updatedAt: runEvent.timestamp ?? prev.run.updatedAt,
        };
      }
      const nextSteps: FlywheelOrchestratorStepDto[] = stepEvents.size === 0
        ? prev.steps
        : prev.steps.map((s) => {
            const ev = stepEvents.get(s.stepRunId);
            if (!ev) return s;
            return {
              ...s,
              status: ev.newStatus,
              errorReason: ev.errorReason ?? s.errorReason,
            };
          });
      return { run: nextRun, steps: nextSteps };
    },
  );
}

/**
 * Subscribe to the user WS feed and patch the orchestrator-runs caches on
 * matching events.
 *
 * Returns nothing — call it for its side effects. The caller still owns the
 * react-query queries; this hook just feeds incremental updates.
 */
export function useFlywheelOrchestratorRunsWS({
  userId,
  selectedRunId,
}: UseFlywheelOrchestratorRunsWSParams): void {
  const queryClient = useQueryClient();
  const bufferRef = useRef<BufferedEvent[]>([]);
  // Track the latest selectedRunId in a ref so the debounced flush always
  // sees the current selection (selectedRunId can change between when the
  // event arrives and when the debounce fires).
  const selectedRunIdRef = useRef<string | null>(selectedRunId);
  useEffect(() => {
    selectedRunIdRef.current = selectedRunId;
  }, [selectedRunId]);

  const [flushDebounced, flushNow] = useDebouncedCallback(() => {
    const events = bufferRef.current;
    if (events.length === 0) return;
    bufferRef.current = [];
    patchListCache(events, (filter, updater) =>
      queryClient.setQueriesData(filter, updater),
    );
    patchDetailCache(events, selectedRunIdRef.current, (key, updater) =>
      queryClient.setQueryData(key, updater),
    );
  }, WS_DEBOUNCE_MS);

  useEffect(() => {
    if (userId == null) return;
    const proto = window.location.protocol === 'https:' ? 'wss' : 'ws';
    const token = localStorage.getItem('sf_token') ?? '';
    const ws = new WebSocket(
      `${proto}://${window.location.host}/ws/users/${userId}?token=${encodeURIComponent(token)}`,
    );

    const handleMessage = (ev: MessageEvent<string>) => {
      let parsed: unknown;
      try {
        parsed = JSON.parse(ev.data);
      } catch {
        return;
      }
      if (!parsed || typeof parsed !== 'object') return;
      const m = parsed as { type?: unknown };
      if (m.type === 'flywheel_run_status_changed') {
        bufferRef.current.push(parsed as RunStatusChangedEvent);
        flushDebounced();
      } else if (m.type === 'flywheel_step_state_changed') {
        bufferRef.current.push(parsed as StepStateChangedEvent);
        flushDebounced();
      }
    };

    ws.addEventListener('message', handleMessage);

    return () => {
      // frontend.md footgun #2: close WS + flush pending so an in-flight
      // debounce can't trigger setState on an unmounted consumer.
      ws.removeEventListener('message', handleMessage);
      try {
        ws.close();
      } catch {
        /* ignore */
      }
      // Drop any pending events from this WS connection — the new WS (if
      // any, on userId change) will receive fresh events.
      bufferRef.current = [];
      flushNow();
    };
    // We intentionally do not depend on `flushDebounced` / `flushNow` —
    // they're stable across renders (useCallback with no deps).
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [userId]);
}
