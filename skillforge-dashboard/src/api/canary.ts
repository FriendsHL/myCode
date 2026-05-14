import api from './index';

/**
 * SKILL-CANARY-ROLLOUT V2 Phase 1.5 — REST client for `/api/canary/rollouts`.
 *
 * Field names mirror the BE Jackson default camelCase serialization. Keep
 * this file in lock-step with the BE record definitions (per
 * `.claude/rules/java.md` footgun #6 — FE-BE contract):
 *
 *   skillforge-server/.../canary/CanaryRolloutController.java
 *   skillforge-server/.../canary/CanaryRolloutResponse.java
 *   skillforge-server/.../canary/MetricSnapshotResponse.java
 *
 * BE → FE type mapping reminder (java.md table):
 *   Java Long/Integer/int → number
 *   Java Instant          → ISO-8601 string
 *   Java BigDecimal       → number (Jackson default — numeric JSON token)
 *   Java nullable boxed   → T | null
 */

/** Lifecycle stage from BE `t_canary_rollout.rollout_stage`. */
export type RolloutStage =
  | 'disabled'
  | 'canary'
  | 'production'
  | 'rolled_back';

/** Terminal decision recorded on publish / rollback. Null = still ongoing. */
export type RolloutDecision = 'promoted' | 'rolled_back' | null;

export interface CanaryRolloutResponse {
  id: number;
  surfaceType: string;
  agentId: number;
  baselineSkillName: string;
  candidateSkillName: string;
  rolloutStage: RolloutStage;
  rolloutPercentage: number;
  /** ISO-8601 (BE Instant). */
  startedAt: string;
  /** ISO-8601 or null when no publish/rollback has happened yet. */
  lastDecisionAt: string | null;
  decision: RolloutDecision;
  createdAt: string;
  updatedAt: string;
}

/**
 * Hourly metric snapshot. Decimal fields serialize as JSON numbers so the
 * TS type is `number | null` (per java.md type-mapping). Nullable when the
 * dimension was never measured in that bucket (tech-design §6.1).
 */
export interface MetricSnapshotResponse {
  id: number;
  canaryId: number;
  /** Hour-aligned bucket boundary (ISO-8601). */
  bucketAt: string;
  controlSampleSize: number;
  controlSuccessCount: number;
  controlFailureCount: number;
  controlAvgQuality: number | null;
  controlAvgEfficiency: number | null;
  controlAvgLatency: number | null;
  controlAvgCost: number | null;
  candidateSampleSize: number;
  candidateSuccessCount: number;
  candidateFailureCount: number;
  candidateAvgQuality: number | null;
  candidateAvgEfficiency: number | null;
  candidateAvgLatency: number | null;
  candidateAvgCost: number | null;
  /** candidate_fail_rate / control_fail_rate; null when control sample is 0. */
  failRateRatio: number | null;
  createdAt: string;
}

// ───────────────────────── request DTOs ─────────────────────────

export interface StartCanaryRequest {
  agentId: number;
  /** BE-side defaults to `'skill'` if omitted, but we always send it explicit. */
  surfaceType: string;
  baselineSkillName: string;
  candidateSkillName: string;
  /** 0-100 inclusive; BE validates. */
  percentage: number;
}

export interface StepUpRequest {
  percentage: number;
}

export interface RollbackRequest {
  /** Free-form reason logged on the entity; defaults to 'manual' on BE. */
  reason?: string;
}

export interface ListCanariesParams {
  agentId: number;
  surfaceType?: string;
  stage?: RolloutStage;
}

// ───────────────────────── endpoints ─────────────────────────

/** `POST /api/canary/rollouts` — 201 Created. 409 if active canary already exists. */
export const startCanary = (req: StartCanaryRequest) =>
  api.post<CanaryRolloutResponse>('/canary/rollouts', req);

/** `PATCH /api/canary/rollouts/{id}/step-up` — raise rollout %. */
export const stepUp = (canaryId: number, req: StepUpRequest) =>
  api.patch<CanaryRolloutResponse>(`/canary/rollouts/${canaryId}/step-up`, req);

/** `POST /api/canary/rollouts/{id}/publish` — promote to production (100%). */
export const publish = (canaryId: number) =>
  api.post<CanaryRolloutResponse>(`/canary/rollouts/${canaryId}/publish`);

/** `POST /api/canary/rollouts/{id}/rollback` — manual rollback (0% + rolled_back stage). */
export const rollback = (canaryId: number, req?: RollbackRequest) =>
  api.post<CanaryRolloutResponse>(`/canary/rollouts/${canaryId}/rollback`, req ?? {});

/** `GET /api/canary/rollouts/{id}` — detail. */
export const getCanary = (canaryId: number) =>
  api.get<CanaryRolloutResponse>(`/canary/rollouts/${canaryId}`);

/**
 * `GET /api/canary/rollouts/{id}/metrics?limit=`. BE-clamped to [1, 168]
 * (7 days × 24h), default 24. BE returns rows ordered by `bucket_at DESC`.
 */
export const getCanaryMetrics = (canaryId: number, params?: { limit?: number }) =>
  api.get<MetricSnapshotResponse[]>(`/canary/rollouts/${canaryId}/metrics`, {
    params,
  });

/**
 * `GET /api/canary/rollouts?agentId=&surfaceType=&stage=`. `agentId` is
 * required by BE; `surfaceType` and `stage` are optional filters.
 */
export const listCanaries = (params: ListCanariesParams) =>
  api.get<CanaryRolloutResponse[]>('/canary/rollouts', { params });
