/**
 * FLYWHEEL-PER-RUN — `useFlywheelRuns` hook.
 *
 * Wraps `listFlywheelRuns` with react-query so the FlywheelRunsSidebar can
 * poll the BE every 30s without each subscriber managing its own setInterval.
 * Independent from `useFlywheelObservability` (aggregate-mode hook) because
 * per-run mode triggers far fewer parallel reads and refetches on a different
 * cadence; mixing them would force the aggregate hook to re-render on every
 * per-run refresh too.
 *
 * Disabled by default — pass `enabled: true` only when the panel is actually
 * in per-run mode, otherwise the panel will trigger network traffic on
 * every aggregate-mode mount.
 */
import { useQuery } from '@tanstack/react-query';
import {
  listFlywheelRuns,
  type ListFlywheelRunsParams,
} from '../api/flywheel';
import type { FlywheelRunDto } from '../components/flywheel/types';

interface UseFlywheelRunsParams extends ListFlywheelRunsParams {
  /**
   * Gate fetching. Pass `false` while the panel is in aggregate mode so the
   * hook stays inert (saves ~1 request / 30s × ~all users). The caller
   * flips this `true` when the user switches to per-run mode.
   */
  enabled?: boolean;
}

interface UseFlywheelRunsResult {
  runs: FlywheelRunDto[];
  isLoading: boolean;
  isError: boolean;
  errorMsg: string | null;
  /** Manual refresh; bound to react-query's internal refetch. */
  refetch: () => void;
}

const POLL_MS = 30_000;
const STALE_MS = 25_000;

export function useFlywheelRuns(
  params: UseFlywheelRunsParams,
): UseFlywheelRunsResult {
  const { agentType, surface, limit, hideTerminal, enabled = true } = params;

  // BE default: hideTerminal true (24h non-terminal runs). Expose explicit
  // value in the key so toggling on/off forces a refetch.
  const hideTerminalNormalized = hideTerminal ?? true;

  const query = useQuery<FlywheelRunDto[], Error>({
    queryKey: [
      'flywheel-runs',
      agentType ?? null,
      surface ?? null,
      limit ?? 20,
      hideTerminalNormalized,
    ],
    queryFn: () =>
      listFlywheelRuns({
        agentType,
        surface,
        limit,
        hideTerminal: hideTerminalNormalized,
      }).then((r) => r.data ?? []),
    enabled,
    staleTime: STALE_MS,
    refetchInterval: enabled ? POLL_MS : false,
    // Avoid noisy retries on a missing/unreleased BE — render empty list
    // instead of repeatedly hammering the server.
    retry: 0,
  });

  return {
    runs: query.data ?? [],
    isLoading: query.isLoading,
    isError: !!query.error,
    errorMsg: query.error?.message ?? null,
    refetch: () => {
      void query.refetch();
    },
  };
}
