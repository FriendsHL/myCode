/**
 * FLYWHEEL-VISUAL-STATUS Phase 2 — multi-useQuery aggregation hook.
 *
 * Per (agentType × surface) tab, the panel needs ~6-8 parallel reads:
 *   sessions (E1), skill-drafts by source (E2/E3/G2), prompt versions (E4),
 *   patterns (step 2), attribution events (steps 3/G1/6/G3), skill A/B runs
 *   (step 5), canary rollouts (steps 7-9 — dormant), system-agent monitor
 *   (cron lag for steps 1/2/3).
 *
 * Cron lag (steps 1/2/3) is pulled from `/api/system-agents/monitor` —
 * matching the agent name (`session-annotator` / `attribution-curator` /
 * etc.) to the step. When BE-Dev (task #7) lands its 3 new endpoints, FE
 * here transparently consumes them; until then the wrappers return empty
 * arrays (HTTP 200 / no rows) and metrics render as 0 / empty.
 *
 * The hook stays light: no client-side sorting, no derived useMemo trees
 * deeper than 1; the component reads `metricsByStep` and `events` directly.
 */
import { useEffect, useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { listPatterns } from '../api/insights';
import { listEvents } from '../api/attribution';
import {
  listAbTestRunsGlobal,
  listCanariesGlobal,
  listSkillDraftsBySource,
} from '../api/flywheel';
import { getSessions, extractList } from '../api';
import { getSystemAgentMonitor } from '../api/systemAgents';
import type {
  AgentTypeTab,
  FlywheelSurface,
  StepMetrics,
  ActivityEvent,
} from '../components/flywheel/types';
import { STEP_CATALOGUE } from '../components/flywheel/types';

interface UseFlywheelObservabilityParams {
  agentType: AgentTypeTab;
  surface: FlywheelSurface;
  userId: number;
}

interface UseFlywheelObservabilityResult {
  metricsByStep: Record<string, StepMetrics>;
  events: ActivityEvent[];
  isLoading: boolean;
  isError: boolean;
  /** First error message, if any (for the panel error banner). */
  errorMsg: string | null;
}

const STALE_TIME = 30_000;
const TWENTY_FOUR_HOURS_MS = 24 * 60 * 60 * 1000;
const ACTIVITY_FEED_LIMIT = 20;

export function useFlywheelObservability(
  params: UseFlywheelObservabilityParams,
): UseFlywheelObservabilityResult {
  const { agentType, surface, userId } = params;

  // ts-B1 fix — `Date.now()` is impure; calling it inside `useMemo`
  // breaks react-hooks/purity. Hoist into a state that ticks every 30s.
  // 30s is plenty for "24h ago" / lag calculations and matches the
  // STALE_TIME on react-query reads so the panel doesn't update derived
  // metrics faster than the source data does.
  const [now, setNow] = useState<number>(() => Date.now());
  useEffect(() => {
    const tick = window.setInterval(() => setNow(Date.now()), 30_000);
    return () => window.clearInterval(tick);
  }, []);

  // ── 1. Sessions (E1 entry, only userId-scoped for 'user' tab) ──
  const sessionsQ = useQuery({
    queryKey: ['flywheel', 'sessions', userId, agentType],
    queryFn: () =>
      getSessions(userId, agentType).then((res) =>
        extractList<Record<string, unknown>>(res),
      ),
    staleTime: STALE_TIME,
    enabled: agentType === 'user',
  });

  // ── 2. Patterns (step 2 cluster) ──
  const patternsQ = useQuery({
    queryKey: ['flywheel', 'patterns', surface],
    queryFn: () =>
      listPatterns({ surface: surface as 'skill' | 'prompt' | 'behavior_rule' })
        .then((r) => r.data ?? []),
    staleTime: STALE_TIME,
  });

  // ── 3. Attribution events (steps 3 / G1 / 6 / G3) ──
  const attributionQ = useQuery({
    queryKey: ['flywheel', 'attribution-events', surface],
    queryFn: () =>
      listEvents({ surfaceType: surface, page: 0, size: 200 })
        .then((r) => r.data?.items ?? []),
    staleTime: STALE_TIME,
  });

  // ── 4. Skill drafts (E2 upload / E3 extract / G2 review) ──
  // code-B1 fix — use the new `?source=` endpoint instead of pulling all
  // drafts and filtering client-side. We do 3 parallel reads (upload /
  // extract-from-sessions / null=all-for-G2) so the BE filter does the
  // narrowing and the FE doesn't ship the full draft set just to bucket
  // a few rows. react-query dedupes the 3 queries on the same cache key
  // when they collapse to identical URLs.
  const uploadsQ = useQuery({
    queryKey: ['flywheel', 'skill-drafts', userId, 'upload'],
    queryFn: () =>
      listSkillDraftsBySource({ userId, source: 'upload' }).then(
        (r) => r.data ?? [],
      ),
    staleTime: STALE_TIME,
    enabled: surface === 'skill' && !!userId,
  });
  const extractsQ = useQuery({
    queryKey: ['flywheel', 'skill-drafts', userId, 'extract-from-sessions'],
    queryFn: () =>
      listSkillDraftsBySource({ userId, source: 'extract-from-sessions' }).then(
        (r) => r.data ?? [],
      ),
    staleTime: STALE_TIME,
    enabled: surface === 'skill' && !!userId,
  });
  // G2 review queue + activity-feed source — needs all drafts (no source
  // narrowing) to count `draft`/`evaluating` status across all sources.
  const allDraftsQ = useQuery({
    queryKey: ['flywheel', 'skill-drafts', userId, 'all'],
    queryFn: () =>
      listSkillDraftsBySource({ userId }).then((r) => r.data ?? []),
    staleTime: STALE_TIME,
    enabled: surface === 'skill' && !!userId,
  });

  // ── 5. Global Skill A/B runs (step 5 hybrid) — uses new BE endpoint ──
  // BE returns paged envelope { items, page, pageSize, total, totalPages };
  // we read .items only (max 100 rows = enough for today/24h aggregation).
  const abRunsQ = useQuery({
    queryKey: ['flywheel', 'abtest-global', surface],
    queryFn: () =>
      listAbTestRunsGlobal({ pageSize: 100 })
        .then((r) => r.data?.items ?? []),
    staleTime: STALE_TIME,
    enabled: surface === 'skill' || surface === 'prompt',
    // BE endpoint may 404 on older deployments; render 0 instead of toast.
    retry: 0,
  });

  // ── 6. Canary rollouts (steps 7-9 dormant) — uses agentId-optional path ──
  const canaryQ = useQuery({
    queryKey: ['flywheel', 'canary-global', surface],
    queryFn: () => listCanariesGlobal({}).then((r) => r.data ?? []),
    staleTime: STALE_TIME,
    enabled: false, // V87 disabled — keep wired but don't fetch
    retry: 0,
  });

  // ── 7. System agent monitor (cron lag steps 1/2/3) ──
  const monitorQ = useQuery({
    queryKey: ['flywheel', 'system-agent-monitor'],
    queryFn: () => getSystemAgentMonitor().then((r) => r.data ?? []),
    staleTime: STALE_TIME,
  });

  const metricsByStep = useMemo<Record<string, StepMetrics>>(() => {
    const result: Record<string, StepMetrics> = {};

    // Generic empty initializer for visible steps.
    for (const step of STEP_CATALOGUE) {
      result[step.id] = {
        inFlight: 0,
        todayCount: 0,
        lastActivityAt: null,
        recentErrorCount: 0,
        loaded: false,
      };
    }

    const oneDayAgo = now - TWENTY_FOUR_HOURS_MS;

    // E1 — user chat sessions in last 24h.
    if (agentType === 'user' && sessionsQ.data) {
      const todays = sessionsQ.data.filter((r) => isWithin24h(r, now));
      result['E1-user-chat'] = {
        inFlight: countStatus(sessionsQ.data, 'running'),
        todayCount: todays.length,
        lastActivityAt: latestTimestamp(sessionsQ.data, ['updatedAt', 'createdAt']),
        recentErrorCount: todays.filter(
          (s) => String(s.runtimeStatus ?? '') === 'error',
        ).length,
        loaded: true,
      };
    } else if (agentType === 'user') {
      result['E1-user-chat'] = mergeLoaded(result['E1-user-chat'], sessionsQ);
    }

    // E2 — uploads (BE-filtered via `?source=upload`).
    if (surface === 'skill' && uploadsQ.data) {
      result['E2-upload-skill'] = buildSourceMetrics(uploadsQ.data, now);
    } else if (surface === 'skill') {
      result['E2-upload-skill'] = mergeLoaded(result['E2-upload-skill'], uploadsQ);
    }
    // E3 — extracts (BE-filtered via `?source=extract-from-sessions`).
    if (surface === 'skill' && extractsQ.data) {
      result['E3-extract-skill'] = buildSourceMetrics(extractsQ.data, now);
    } else if (surface === 'skill') {
      result['E3-extract-skill'] = mergeLoaded(result['E3-extract-skill'], extractsQ);
    }
    // G2 — review queue across all sources.
    if (surface === 'skill' && allDraftsQ.data) {
      const drafts = allDraftsQ.data;
      const reviewable = drafts.filter(
        (d) => d.status === 'draft' || d.status === 'evaluating',
      );
      result['G2-review-draft'] = {
        inFlight: reviewable.length,
        todayCount: drafts.filter(
          (d) => d.reviewedAt && new Date(d.reviewedAt).getTime() >= oneDayAgo,
        ).length,
        lastActivityAt: latestTimestamp(
          drafts as unknown as Record<string, unknown>[],
          ['reviewedAt', 'createdAt'] as const,
        ),
        recentErrorCount: 0,
        pendingActionCount: reviewable.length,
        loaded: true,
      };
    } else if (surface === 'skill') {
      result['G2-review-draft'] = mergeLoaded(result['G2-review-draft'], allDraftsQ);
    }

    // E4 — direct write prompt (lightweight signal — placeholder, BE
    // doesn't expose a global cross-agent prompt-versions feed yet; render
    // 'empty' state instead of a fake count).
    result['E4-write-prompt'] = {
      inFlight: 0,
      todayCount: 0,
      lastActivityAt: null,
      recentErrorCount: 0,
      loaded: true,
    };

    // Step 1 / 2 / 3 — annotator / pattern / attribution-curator lag from
    // system-agent monitor. Match by name.
    if (monitorQ.data) {
      const monitorByName = new Map(monitorQ.data.map((r) => [r.name, r]));
      const annotator = monitorByName.get('session-annotator');
      if (annotator) {
        result['step1-annotate'] = {
          inFlight: 0,
          todayCount: annotator.sevenDayOutputCount, // 7d, not 24h — best signal we have
          lastActivityAt: annotator.lastRunAt,
          recentErrorCount: annotator.lastRunStatus === 'failure' ? 1 : 0,
          loaded: true,
        };
      } else {
        result['step1-annotate'] = { ...result['step1-annotate'], loaded: true };
      }
      const curator = monitorByName.get('attribution-curator');
      if (curator) {
        result['step3-attribute'] = {
          inFlight: 0,
          todayCount: curator.sevenDayOutputCount,
          lastActivityAt: curator.lastRunAt,
          recentErrorCount: curator.lastRunStatus === 'failure' ? 1 : 0,
          loaded: true,
        };
      } else {
        result['step3-attribute'] = { ...result['step3-attribute'], loaded: true };
      }
    } else {
      result['step1-annotate'] = mergeLoaded(result['step1-annotate'], monitorQ);
      result['step3-attribute'] = mergeLoaded(result['step3-attribute'], monitorQ);
    }

    // Step 2 — cluster pattern: in-flight = total patterns in surface;
    // today = patterns first-seen in last 24h.
    if (patternsQ.data) {
      const ps = patternsQ.data;
      const todays = ps.filter((p) =>
        new Date(p.firstSeenAt).getTime() >= oneDayAgo,
      );
      result['step2-cluster'] = {
        inFlight: ps.length,
        todayCount: todays.length,
        lastActivityAt: latestTimestamp(ps as unknown as Record<string, unknown>[], [
          'lastSeenAt',
          'firstSeenAt',
        ]),
        recentErrorCount: 0,
        loaded: true,
      };
    } else {
      result['step2-cluster'] = mergeLoaded(result['step2-cluster'], patternsQ);
    }

    // Step 3 — override `inFlight` with proposal_pending count (curator
    // monitor already gave us lag; in-flight is "events queued for the
    // operator's eyes").
    if (attributionQ.data) {
      const evs = attributionQ.data;
      const todayEvs = evs.filter(
        (e) => new Date(e.createdAt).getTime() >= oneDayAgo,
      );
      // step3 — refine in-flight from event stage counts.
      result['step3-attribute'] = {
        ...result['step3-attribute'],
        inFlight: evs.filter((e) => e.stage === 'dispatch_initiated').length,
        recentErrorCount:
          (result['step3-attribute']?.recentErrorCount ?? 0) +
          evs.filter((e) => e.stage === 'candidate_failed' && new Date(e.updatedAt).getTime() >= oneDayAgo).length,
        loaded: true,
      };
      // G1 — operator approval queue (proposal_pending).
      const g1Pending = evs.filter((e) => e.stage === 'proposal_pending');
      result['G1-approve-event'] = {
        inFlight: g1Pending.length,
        todayCount: todayEvs.filter((e) => e.stage === 'proposal_pending').length,
        lastActivityAt: latestTimestamp(evs as unknown as Record<string, unknown>[], [
          'updatedAt',
          'createdAt',
        ]),
        recentErrorCount: evs.filter(
          (e) =>
            e.stage === 'proposal_rejected' &&
            new Date(e.updatedAt).getTime() >= oneDayAgo,
        ).length,
        pendingActionCount: g1Pending.length,
        loaded: true,
      };
      // Step 4 — candidate_generating / candidate_ready in-flight.
      result['step4-candidate'] = {
        inFlight: evs.filter((e) =>
          e.stage === 'candidate_generating' || e.stage === 'candidate_ready',
        ).length,
        todayCount: todayEvs.filter((e) =>
          e.stage === 'candidate_ready' || e.stage === 'candidate_created',
        ).length,
        lastActivityAt: latestTimestamp(
          evs.filter((e) => e.stage.startsWith('candidate_')) as unknown as Record<string, unknown>[],
          ['updatedAt'],
        ),
        recentErrorCount: evs.filter(
          (e) => e.stage === 'candidate_failed' && new Date(e.updatedAt).getTime() >= oneDayAgo,
        ).length,
        loaded: true,
      };
      // Step 6 — gate (ab_passed pending publish).
      const g6 = evs.filter((e) => e.stage === 'ab_passed');
      result['step6-gate'] = {
        inFlight: g6.length,
        todayCount: todayEvs.filter((e) => e.stage === 'ab_passed').length,
        lastActivityAt: latestTimestamp(g6 as unknown as Record<string, unknown>[], ['updatedAt']),
        recentErrorCount: 0,
        loaded: true,
      };
      // G3 — operator promote/discard decision.
      result['G3-promote-decision'] = {
        inFlight: g6.length,
        todayCount: todayEvs.filter((e) => e.stage === 'promoted').length,
        lastActivityAt: latestTimestamp(
          evs.filter((e) => e.stage === 'promoted') as unknown as Record<string, unknown>[],
          ['updatedAt'],
        ),
        recentErrorCount: evs.filter(
          (e) => e.stage === 'rolled_back' && new Date(e.updatedAt).getTime() >= oneDayAgo,
        ).length,
        pendingActionCount: g6.length,
        loaded: true,
      };
    } else {
      result['G1-approve-event'] = mergeLoaded(result['G1-approve-event'], attributionQ);
      result['step4-candidate'] = mergeLoaded(result['step4-candidate'], attributionQ);
      result['step6-gate'] = mergeLoaded(result['step6-gate'], attributionQ);
      result['G3-promote-decision'] = mergeLoaded(result['G3-promote-decision'], attributionQ);
    }

    // Step 5 — A/B runs (RUNNING + last 24h completed).
    if (abRunsQ.data) {
      const runs = abRunsQ.data;
      const todays = runs.filter(
        (r) => r.startedAt && new Date(r.startedAt).getTime() >= oneDayAgo,
      );
      result['step5-abtest'] = {
        inFlight: runs.filter((r) => r.status === 'RUNNING' || r.status === 'PENDING').length,
        todayCount: todays.length,
        lastActivityAt: latestTimestamp(
          runs as unknown as Record<string, unknown>[],
          ['completedAt', 'startedAt'],
        ),
        recentErrorCount: todays.filter((r) => r.status === 'FAILED').length,
        loaded: true,
      };
    } else {
      result['step5-abtest'] = mergeLoaded(result['step5-abtest'], abRunsQ);
    }

    // Step 7 / 8 / 9 — dormant per V87. Render "disabled" chip; metrics
    // remain 0 (loaded: true).
    for (const id of ['step7-canary', 'step8-metrics', 'step9-decide']) {
      result[id] = {
        inFlight: 0,
        todayCount: 0,
        lastActivityAt: null,
        recentErrorCount: 0,
        loaded: true,
      };
    }
    void canaryQ; // intentionally unused (V87); keeps import wired for future re-enable

    return result;
  }, [
    agentType,
    surface,
    now,
    sessionsQ.data,
    sessionsQ.isLoading,
    uploadsQ.data,
    uploadsQ.isLoading,
    extractsQ.data,
    extractsQ.isLoading,
    allDraftsQ.data,
    allDraftsQ.isLoading,
    patternsQ.data,
    patternsQ.isLoading,
    attributionQ.data,
    attributionQ.isLoading,
    abRunsQ.data,
    abRunsQ.isLoading,
    monitorQ.data,
    monitorQ.isLoading,
    canaryQ,
  ]);

  // ── Activity feed (top 20 most-recent events across all queried sources) ──
  const events = useMemo<ActivityEvent[]>(() => {
    const out: ActivityEvent[] = [];
    const oneDayAgo = now - TWENTY_FOUR_HOURS_MS;

    if (attributionQ.data) {
      for (const e of attributionQ.data) {
        const ts = new Date(e.updatedAt).getTime();
        if (ts >= oneDayAgo) {
          out.push({
            id: `attr-${e.id}`,
            at: e.updatedAt,
            stepId: mapAttributionStageToStep(e.stage),
            label: `OptEvent #${e.id} → ${e.stage}`,
            meta: `agent=${e.agentId}`,
            isError:
              e.stage === 'candidate_failed' ||
              e.stage === 'ab_failed' ||
              e.stage === 'rolled_back' ||
              e.stage === 'proposal_rejected',
          });
        }
      }
    }
    if (allDraftsQ.data && surface === 'skill') {
      for (const d of allDraftsQ.data) {
        const t = d.reviewedAt ?? d.createdAt;
        if (!t) continue;
        const ts = new Date(t).getTime();
        if (ts >= oneDayAgo) {
          out.push({
            id: `draft-${d.id}`,
            at: t,
            stepId: d.status === 'draft' ? 'G2-review-draft' : 'step4-candidate',
            label: `Draft "${d.name}" → ${d.status ?? 'draft'}`,
            meta: d.source ? `source=${d.source}` : undefined,
          });
        }
      }
    }
    if (abRunsQ.data) {
      for (const r of abRunsQ.data) {
        const t = r.completedAt ?? r.startedAt;
        if (!t) continue;
        const ts = new Date(t).getTime();
        if (ts >= oneDayAgo) {
          out.push({
            id: `ab-${r.id}`,
            at: t,
            stepId: 'step5-abtest',
            label: `A/B run #${r.id} → ${r.status}${r.promoted ? ' (promoted)' : ''}`,
            meta: `Δ${r.deltaPassRate != null ? Math.round(r.deltaPassRate * 100) + 'pp' : '?'}`,
            isError: r.status === 'FAILED',
          });
        }
      }
    }

    out.sort((a, b) => new Date(b.at).getTime() - new Date(a.at).getTime());
    return out.slice(0, ACTIVITY_FEED_LIMIT);
  }, [attributionQ.data, allDraftsQ.data, surface, abRunsQ.data, now]);

  const isLoading =
    sessionsQ.isLoading ||
    patternsQ.isLoading ||
    attributionQ.isLoading ||
    uploadsQ.isLoading ||
    extractsQ.isLoading ||
    allDraftsQ.isLoading ||
    abRunsQ.isLoading ||
    monitorQ.isLoading;

  const errorMsg =
    firstErrMsg(sessionsQ.error) ??
    firstErrMsg(patternsQ.error) ??
    firstErrMsg(attributionQ.error) ??
    firstErrMsg(uploadsQ.error) ??
    firstErrMsg(extractsQ.error) ??
    firstErrMsg(allDraftsQ.error) ??
    firstErrMsg(abRunsQ.error) ??
    firstErrMsg(monitorQ.error);

  return {
    metricsByStep,
    events,
    isLoading,
    isError: !!errorMsg,
    errorMsg,
  };
}

// ───────────────────────── helpers ─────────────────────────

function isWithin24h(row: Record<string, unknown>, now: number): boolean {
  const t = (row.createdAt as string | undefined) ?? (row.updatedAt as string | undefined);
  if (!t) return false;
  return now - new Date(t).getTime() <= TWENTY_FOUR_HOURS_MS;
}

function countStatus(rows: Record<string, unknown>[], status: string): number {
  return rows.filter((r) => String(r.runtimeStatus ?? r.status ?? '') === status).length;
}

function latestTimestamp(
  rows: Record<string, unknown>[],
  fields: readonly string[],
): string | null {
  let latest: number | null = null;
  let latestIso: string | null = null;
  for (const r of rows) {
    for (const f of fields) {
      const v = r[f];
      if (typeof v === 'string') {
        const t = new Date(v).getTime();
        if (!Number.isNaN(t) && (latest == null || t > latest)) {
          latest = t;
          latestIso = v;
        }
      }
    }
  }
  return latestIso;
}

function buildSourceMetrics(
  drafts: { createdAt: string; status?: string }[],
  now: number,
): StepMetrics {
  const oneDayAgo = now - TWENTY_FOUR_HOURS_MS;
  const todays = drafts.filter(
    (d) => d.createdAt && new Date(d.createdAt).getTime() >= oneDayAgo,
  );
  return {
    inFlight: drafts.filter((d) => d.status === 'draft' || d.status === 'evaluating').length,
    todayCount: todays.length,
    lastActivityAt: latestTimestamp(drafts as unknown as Record<string, unknown>[], ['createdAt']),
    recentErrorCount: drafts.filter(
      (d) =>
        d.status === 'rejected' &&
        d.createdAt &&
        new Date(d.createdAt).getTime() >= oneDayAgo,
    ).length,
    loaded: true,
  };
}

function mergeLoaded(
  prev: StepMetrics,
  q: { isLoading: boolean; error: unknown },
): StepMetrics {
  if (q.error) return { ...prev, loaded: true, errored: true };
  return { ...prev, loaded: !q.isLoading };
}

function firstErrMsg(err: unknown): string | null {
  if (!err) return null;
  if (err instanceof Error) return err.message;
  return 'request failed';
}

function mapAttributionStageToStep(stage: string): string {
  if (stage === 'dispatch_initiated' || stage === 'proposal_pending') return 'step3-attribute';
  if (stage === 'proposal_approved' || stage === 'proposal_rejected') return 'G1-approve-event';
  if (stage.startsWith('candidate_')) return 'step4-candidate';
  if (stage === 'ab_running' || stage === 'ab_passed' || stage === 'ab_failed') return 'step5-abtest';
  if (stage === 'promoted' || stage === 'rolled_back') return 'G3-promote-decision';
  return 'step3-attribute';
}
