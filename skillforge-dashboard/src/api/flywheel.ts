/**
 * FLYWHEEL-VISUAL-STATUS Phase 2 (FE) — REST client wrappers for the 3 new
 * BE endpoints the FlywheelObservabilityPanel consumes.
 *
 * Field names mirror the BE Jackson default camelCase serialization. Keep
 * this file in lock-step with the BE record / DTO definitions (per
 * `.claude/rules/java.md` known footgun #6 — FE-BE contract grep + roundtrip
 * IT verification).
 *
 *   skillforge-server/.../skill/SkillAbTestController.java          (new global)
 *   skillforge-server/.../canary/CanaryRolloutController.java       (agentId optional)
 *   skillforge-server/.../skill/SkillDraftController.java           (source filter)
 *
 * BE → FE type mapping reminder (java.md table):
 *   Java Long/Integer/int → number
 *   Java Instant          → ISO-8601 string
 *   Java BigDecimal       → number (Jackson default — numeric JSON token)
 *   Java nullable boxed   → T | null
 *
 * **BE-Dev parallel TODO**: when be-dev (task #7) lands its diff, verify:
 *   1. `GET /api/skills/abtest` query params + response shape match below
 *   2. `GET /api/canary/rollouts` accepts no agentId (returns all)
 *   3. `GET /api/skill-drafts?source=...` honours the source filter
 *
 * Until BE-Dev lands, FE callers tolerate empty arrays (panel renders
 * "0 in-flight" health state, not error).
 */
import api from './index';
import type { SkillAbRun } from './index';
import type { CanaryRolloutResponse } from './canary';
import type {
  FlywheelRunDto,
  FlywheelSurface,
} from '../components/flywheel/types';

// ───────────────────────── /api/flywheel/runs (per-run view) ─────────────
//
// FLYWHEEL-PER-RUN — list recent OptimizationEvent runs with stage/error
// metadata for the per-run mode of the flywheel observability panel.

export interface ListFlywheelRunsParams {
  /** Filter by agent type (user / system); absent = both. */
  agentType?: 'user' | 'system';
  /** Filter by surface; absent = all surfaces. */
  surface?: FlywheelSurface;
  /** BE default 20, clamped [1, 100]. */
  limit?: number;
  /**
   * When true (BE default), hide terminal-state runs (promoted / discarded).
   * Pass false to include them.
   */
  hideTerminal?: boolean;
}

/**
 * BE envelope for `/api/flywheel/runs` — Controller returns
 * `{items, limit, hideTerminal}` (LinkedHashMap, stable field order). Both
 * outer shape AND inner items must be locked in this TS interface — FE-BE
 * Jackson contract footgun #6 covers field NAMES inside DTO but NOT the
 * envelope shape; previous r1 review verified the 12 DTO fields but missed
 * that BE returned `{items:[…]}` not bare array, triggering "runs is not
 * iterable" at runtime.
 */
export interface FlywheelRunsResponse {
  items: FlywheelRunDto[];
  limit: number;
  hideTerminal: boolean;
}

/**
 * `GET /api/flywheel/runs` — returns up to `limit` recent OptimizationEvent
 * runs sorted by lastUpdatedAt DESC. See BE FlywheelController for the
 * canonical contract; FE TS interface lives in components/flywheel/types.ts
 * so it can be shared between API wrapper + hook + sidebar component
 * without circular imports.
 */
export const listFlywheelRuns = (params?: ListFlywheelRunsParams) =>
  api.get<FlywheelRunsResponse>('/flywheel/runs', { params });

// ───────────────────────── /api/skills/abtest (global) ─────────────────────

/**
 * Lifecycle status of a skill A/B run, mirroring `t_skill_ab_run.status`.
 *
 * code-WARN-5 fix — BE javadoc on `SkillController.listAbTestsGlobal`
 * Phase 2 documents 5 values; `SKIPPED` rows surface when the BE rejected
 * a candidate run before it executed (cooldown / no scenarios). The
 * legacy `SkillAbRun.status` union in `api/index.ts` stays narrower
 * because the per-skill endpoint doesn't emit SKIPPED today — keep the
 * two aliases independent so legacy callers don't get a forward-compat
 * variant they can't handle.
 */
export type SkillAbRunStatus =
  | 'PENDING'
  | 'RUNNING'
  | 'COMPLETED'
  | 'FAILED'
  | 'SKIPPED';

export interface ListAbTestRunsParams {
  /** BE-side filter; absent = no agent narrowing. */
  agentId?: string | number;
  status?: SkillAbRunStatus;
  /** Reserved for future migration; BE rejects non-`skill` values today. */
  surfaceType?: string;
  /** 1-based page; BE default 1. */
  page?: number;
  /** Page size; BE clamps [1, 100], default 20. */
  pageSize?: number;
}

/**
 * BE-Dev paged response envelope (mirrors `listDraftsPaged`):
 *   { items: [...], page, pageSize, total, totalPages }
 *
 * FE consumes the `items` array; pagination metadata is exposed for future
 * use but FlywheelObservability today does not paginate (read "in-flight
 * count" + "24h today" from page 1 only).
 */
export interface PagedAbTestRunsResponse {
  items: SkillAbRun[];
  page: number;
  pageSize: number;
  total: number;
  totalPages: number;
}

/**
 * `GET /api/skills/abtest` — paginated global list of A/B runs across all
 * skills / agents (BE-Dev added this in task #7).
 *
 * Returns the paged envelope; FlywheelObservability reads `.items` to get
 * the run array (max 100 per page). Sorting is `started_at DESC` (BE-side).
 */
export const listAbTestRunsGlobal = (params?: ListAbTestRunsParams) =>
  api.get<PagedAbTestRunsResponse>('/skills/abtest', { params });

// ───────────────────── /api/canary/rollouts (agentId optional) ─────────────

/**
 * `GET /api/canary/rollouts` — agentId now optional (BE-Dev task #7 change).
 * When agentId is absent, BE returns all rollouts across agents. Note this
 * differs from {@link import('./canary').listCanaries} which requires
 * agentId — we keep that wrapper untouched for callers (CanaryPanel) that
 * legitimately need per-agent scoping, and add this separate wrapper for
 * the global / cross-agent flywheel observability view.
 */
export interface ListCanariesGlobalParams {
  agentId?: number;
  surfaceType?: string;
  stage?: 'disabled' | 'canary' | 'production' | 'rolled_back';
}

export const listCanariesGlobal = (params?: ListCanariesGlobalParams) =>
  api.get<CanaryRolloutResponse[]>('/canary/rollouts', { params });

// ───────────────────── /api/skill-drafts?source= (filter) ─────────────────

/**
 * Known `source` enum values (from tech-design D8 + currently-seen rows).
 * Free-form on the BE so an unknown value is tolerated (FE bucket = "other").
 */
export type SkillDraftSource =
  | 'upload'
  | 'marketplace'
  | 'natural-language'
  | 'extract-from-sessions'
  | 'attribution'
  | 'manual'
  | 'skill-creator-eval';

export interface ListSkillDraftsBySourceParams {
  userId: number;
  /** Single literal source value, or comma-separated list (BE intent). */
  source?: string;
  status?: string;
}

/**
 * `GET /api/skill-drafts?userId=&source=&status=` — extends the existing
 * `getSkillDrafts(userId)` wrapper with the new `source` filter BE-Dev is
 * adding. We export a separate function so existing callers (SkillList /
 * SkillDrafts pages) don't accidentally start sending the new param via
 * a shared signature change.
 */
import type { SkillDraft } from './index';

export const listSkillDraftsBySource = (params: ListSkillDraftsBySourceParams) =>
  api.get<SkillDraft[]>('/skill-drafts', { params });
