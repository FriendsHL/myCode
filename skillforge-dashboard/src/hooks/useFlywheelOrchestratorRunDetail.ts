/**
 * OPT-LOOP-FRAMEWORK Sprint 4 — detail query hook for the `/flywheel-runs`
 * page right pane.
 *
 * Polling cadence mirrors `useFlywheelOrchestratorRuns`: 5s while the
 * selected run is non-terminal, off when settled. WS push provides
 * between-tick liveness via `useFlywheelOrchestratorRunsWS`.
 *
 * Outer envelope: `{ run, steps }` LinkedHashMap on the BE — `r.data?.run`
 * + `r.data?.steps ?? []`, never `r.data ?? {}` (538b828 footgun).
 */
import { useQuery, type QueryKey } from '@tanstack/react-query';
import {
  getFlywheelOrchestratorRun,
  type FlywheelOrchestratorRunDetailResponse,
} from '../api/flywheelOrchestratorRun';
import { isNonTerminal } from '../components/flywheelOrchestratorRun/statusColor';
import { POLL_INTERVAL_MS } from './useFlywheelOrchestratorRuns';

/**
 * Stable detail query key so the WS hook can patch the cached envelope
 * directly via `queryClient.setQueryData(...)`.
 */
export function flywheelOrchestratorRunDetailQueryKey(runId: string | null): QueryKey {
  return ['flywheel-orchestrator-run-detail', runId];
}

export function useFlywheelOrchestratorRunDetail(runId: string | null) {
  return useQuery<FlywheelOrchestratorRunDetailResponse, Error>({
    queryKey: flywheelOrchestratorRunDetailQueryKey(runId),
    queryFn: () => getFlywheelOrchestratorRun(runId as string).then((r) => r.data),
    enabled: runId !== null,
    staleTime: 0,
    refetchInterval: (q) => {
      const env = q.state.data as FlywheelOrchestratorRunDetailResponse | undefined;
      if (!env) return false;
      return isNonTerminal(env.run.status) ? POLL_INTERVAL_MS : false;
    },
    retry: 0,
  });
}
