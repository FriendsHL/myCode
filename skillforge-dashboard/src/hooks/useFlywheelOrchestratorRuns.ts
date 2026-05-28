/**
 * OPT-LOOP-FRAMEWORK Sprint 4 — list query hook for the `/flywheel-runs`
 * page (Plan §2 D5 — polling 5s while any row is in motion, WS push
 * supplies between-tick freshness via `useFlywheelOrchestratorRunsWS`).
 *
 * Outer envelope contract (Plan §2 D1 / §4 R5):
 *   - BE returns `{ items, total, limit, offset }` LinkedHashMap envelope.
 *   - Consumer reads `r.data?.items ?? []` — NEVER `r.data ?? []` which
 *     would treat the envelope object as an array (538b828 footgun).
 *
 * Query key shape: `['flywheel-orchestrator-runs', filterKeyTuple]` so any
 * filter param mutation triggers a fresh fetch (and so the WS handler can
 * `setQueryData` against the same key).
 */
import { useQuery, type QueryKey } from '@tanstack/react-query';
import {
  listFlywheelOrchestratorRuns,
  type FlywheelOrchestratorRunDto,
  type ListFlywheelOrchestratorRunsParams,
  type ListFlywheelOrchestratorRunsResponse,
} from '../api/flywheelOrchestratorRun';
import { isNonTerminal } from '../components/flywheelOrchestratorRun/statusColor';

export const POLL_INTERVAL_MS = 5_000;

/**
 * Stable query key for the list. Exported so the WS hook can target the
 * exact same key when running setQueryData updates. Filter values are
 * normalized to `null` so identical-effect inputs (`undefined`, empty
 * string, missing) collapse to the same key.
 */
export function flywheelOrchestratorRunsQueryKey(
  params: ListFlywheelOrchestratorRunsParams,
): QueryKey {
  return [
    'flywheel-orchestrator-runs',
    {
      loopKind: params.loopKind || null,
      agentId: params.agentId ?? null,
      status: params.status || null,
      limit: params.limit ?? null,
      offset: params.offset ?? null,
    },
  ];
}

/**
 * List query with adaptive polling: 5s while any visible run is
 * non-terminal, off when all rows are terminal (saves cost on settled
 * pages — same logic OptReports.tsx uses).
 */
export function useFlywheelOrchestratorRuns(
  params: ListFlywheelOrchestratorRunsParams = {},
) {
  return useQuery<ListFlywheelOrchestratorRunsResponse, Error>({
    queryKey: flywheelOrchestratorRunsQueryKey(params),
    queryFn: () => listFlywheelOrchestratorRuns(params).then((r) => r.data),
    staleTime: 0,
    refetchInterval: (q) => {
      const env = q.state.data as ListFlywheelOrchestratorRunsResponse | undefined;
      const items: FlywheelOrchestratorRunDto[] = env?.items ?? [];
      const hasActive = items.some((r) => isNonTerminal(r.status));
      return hasActive ? POLL_INTERVAL_MS : false;
    },
    retry: 0,
  });
}
