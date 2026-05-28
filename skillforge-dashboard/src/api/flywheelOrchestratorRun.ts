/**
 * OPT-LOOP-FRAMEWORK Sprint 4 (FR-5/FR-6) — REST wrappers for the dashboard
 * `/flywheel-runs` page.
 *
 * Contract source of truth (java.md footgun #6 + #6b grep targets):
 *   - `skillforge-server/src/main/java/com/skillforge/server/flywheel/run/
 *     FlywheelOrchestratorRunController.java`  — outer envelope shape
 *   - `FlywheelOrchestratorRunDto.java`        — inner run row shape
 *   - `FlywheelOrchestratorStepDto.java`       — inner step row shape
 *
 * Outer envelope shape (Plan §2 D1 + §4 R5 / 538b828 footgun precedent):
 *   - GET `/api/flywheel/orchestrator-runs`         →
 *       `{ items: FlywheelOrchestratorRunDto[], total, limit, offset }`
 *   - GET `/api/flywheel/orchestrator-runs/{id}`    →
 *       `{ run: FlywheelOrchestratorRunDto, steps: FlywheelOrchestratorStepDto[] }`
 *
 * DO NOT type either endpoint as a bare array — the BE wraps in a
 * `LinkedHashMap` envelope and consumers must `r.data.items` /
 * `r.data.run` + `r.data.steps`. Vitest mocks must mirror this envelope
 * shape (no echo-chamber `{ data: [...] }` forms).
 *
 * BE → FE type mapping reminder:
 *   - Java `String`             → `string`
 *   - Java `String` nullable    → `string | null`
 *   - Java `Long` / `Integer`   → `number`
 *   - Java `Integer` nullable   → `number | null`
 *   - Java `Instant` (ISO-8601) → `string`
 */
import api from './index';

// ───────────────────────── DTO types ──────────────────────────────────────

/**
 * Mirrors BE `FlywheelOrchestratorRunDto` (13 fields, declaration order).
 *
 * Nullability follows the BE `@Nullable`-by-default convention: only `runId`
 * / `loopKind` / `triggerSource` / `status` / `createdAt` / `updatedAt` are
 * guaranteed non-null; everything else can be null (e.g. an `attribution`
 * run has no `generatorSessionId`; a `pending` run has no `errorReason`;
 * window bounds are absent for ad-hoc runs).
 */
export interface FlywheelOrchestratorRunDto {
  runId: string;
  loopKind: string;
  triggerSource: string;
  agentId: number | null;
  status: string;
  errorReason: string | null;
  generatorSessionId: string | null;
  inputJson: string | null;
  summaryJson: string | null;
  windowStart: string | null;
  windowEnd: string | null;
  createdAt: string;
  updatedAt: string;
}

/**
 * Mirrors BE `FlywheelOrchestratorStepDto` (11 fields, declaration order).
 */
export interface FlywheelOrchestratorStepDto {
  stepRunId: string;
  runId: string;
  stepKind: string;
  status: string;
  subAgentSessionId: string | null;
  stepInputJson: string | null;
  stepOutputJson: string | null;
  stepOutputCount: number | null;
  errorReason: string | null;
  createdAt: string;
  updatedAt: string;
}

/**
 * List endpoint envelope. Mirrors the BE `LinkedHashMap` field order
 * (items → total → limit → offset) so the JSON shape stays stable for the
 * roundtrip IT that asserts on the raw JSON string.
 */
export interface ListFlywheelOrchestratorRunsResponse {
  items: FlywheelOrchestratorRunDto[];
  total: number;
  limit: number;
  offset: number;
}

/**
 * Detail endpoint envelope.
 */
export interface FlywheelOrchestratorRunDetailResponse {
  run: FlywheelOrchestratorRunDto;
  steps: FlywheelOrchestratorStepDto[];
}

export interface ListFlywheelOrchestratorRunsParams {
  loopKind?: string | null;
  agentId?: number | null;
  status?: string | null;
  limit?: number;
  offset?: number;
}

// ───────────────────────── API wrappers ───────────────────────────────────

/**
 * Build the axios `params` object, stripping null / empty entries so axios
 * doesn't send `?loopKind=null` / `?agentId=`. BE treats empty strings as
 * "no filter" but trimming up-front keeps the query string clean.
 */
function buildListParams(p: ListFlywheelOrchestratorRunsParams): Record<string, string | number> {
  const out: Record<string, string | number> = {};
  if (p.loopKind != null && p.loopKind !== '') out.loopKind = p.loopKind;
  if (p.agentId != null) out.agentId = p.agentId;
  if (p.status != null && p.status !== '') out.status = p.status;
  if (p.limit != null) out.limit = p.limit;
  if (p.offset != null) out.offset = p.offset;
  return out;
}

/**
 * `GET /api/flywheel/orchestrator-runs?loopKind=&agentId=&status=&limit=&offset=`
 *
 * BE clamps `limit` to [1, 100] (default 20); `offset` to >= 0 (default 0).
 * Returns an envelope (NOT a bare array — see footgun #6b notes at top of file).
 */
export const listFlywheelOrchestratorRuns = (params: ListFlywheelOrchestratorRunsParams = {}) =>
  api.get<ListFlywheelOrchestratorRunsResponse>('/flywheel/orchestrator-runs', {
    params: buildListParams(params),
  });

/**
 * `GET /api/flywheel/orchestrator-runs/{runId}` — 404 if id unknown,
 * 400 if id blank.
 */
export const getFlywheelOrchestratorRun = (runId: string) =>
  api.get<FlywheelOrchestratorRunDetailResponse>(
    `/flywheel/orchestrator-runs/${encodeURIComponent(runId)}`,
  );
