/**
 * OPT-REPORT-V1 Sub-batch 2 (FE) — REST client wrappers for the three
 * OPT-REPORT endpoints (`OptReportController` on the BE side).
 *
 * Contract source of truth: `skillforge-server/src/main/java/com/skillforge/
 * server/optreport/OptReportController.java`. Field names mirror the BE
 * Jackson default camelCase serialization; keep this file in lock-step with
 * the controller's `toSummaryDto` / `toFullDto` helpers per
 * `.claude/rules/java.md` known footgun #6 / #6b (FE-BE Jackson shape grep
 * + roundtrip verification).
 *
 * BE → FE type mapping reminder:
 *   Java Long     → number
 *   Java Instant  → ISO-8601 string
 *   Java nullable → T | null
 *   Java Map<String,Object> with LinkedHashMap → object literal
 *
 * ⚠️ Discrepancy vs Sub-batch 2 brief (resolved by grepping the actual BE
 * Controller per footgun #6b "outer envelope shape" checklist):
 *
 *   - Brief said list endpoint returns a bare array; BE actually returns
 *     `{items: [...], limit: N}` envelope (LinkedHashMap). We type the
 *     envelope so consumers `r.data.items` correctly — not `r.data ?? []`
 *     which would treat the envelope object as `OptReport[]`.
 *   - Brief said the report DTO uses an `id` field; BE actually emits
 *     `reportId`. We use `reportId` so reads aren't silently undefined.
 *   - Brief listed `contentMd` / `summaryJson` on the list DTO; BE only
 *     emits those on the single-report detail endpoint. We split the two
 *     shapes into `OptReportSummary` (list) and `OptReport` (detail) so
 *     consumers can't read `contentMd` off a summary row by accident.
 */
import api from './index';

// ───────────────────────── DTO types ──────────────────────────────────────

/** Lifecycle states emitted by `OptReportEntity.status`. */
export type OptReportStatus = 'pending' | 'running' | 'completed' | 'error';

/**
 * Summary fields returned by `GET /api/flywheel/agents/{agentId}/reports`
 * (one row per item in the envelope `.items` array). Mirrors BE
 * `OptReportController#toSummaryDto`.
 */
export interface OptReportSummary {
  reportId: string;
  agentId: number;
  windowStart: string; // ISO-8601
  windowEnd: string;   // ISO-8601
  status: OptReportStatus;
  createdAt: string;   // ISO-8601
  updatedAt: string;   // ISO-8601
}

/**
 * OPT-REPORT-V1.2 — severity for an issue surfaced by the report-generator
 * agent. Drives the color chip in `IssueCard` and (in V2) the auto-dispatch
 * priority when high-confidence issues feed into the old flywheel curator.
 */
export type OptReportIssueSeverity = 'high' | 'medium' | 'low';

/**
 * OPT-REPORT-V1.2 — `suspectSurface` enum mirrors the old flywheel
 * `attribution_surface` allowlist with two extra fallback values (`other` /
 * `unclear`) that the agent emits when it can't pin the failure to a
 * concrete surface. Those two are NOT convertible to OptEvent (the BE
 * `convertIssueToEvent` endpoint will 400 on them) so the FE disables the
 * button + shows a tooltip.
 */
export type OptReportSuspectSurface =
  | 'skill'
  | 'prompt'
  | 'behavior_rule'
  | 'other'
  | 'unclear';

/**
 * OPT-REPORT-V1.2 — single structured issue row inside the report's
 * `summary.topIssues` array. Produced by the `report-generator` agent's
 * STEP-6 attribution LLM and persisted by the V1.2 BE in
 * `t_opt_report.summary_json` (structured shape replaces the V1.1 free-form
 * markdown-only payload). The BE joins `t_optimization_event` to compute
 * `alreadyConverted` / `convertedEventId` per-row, so the FE doesn't need a
 * second query to know whether the "Convert to OptEvent" button should be
 * disabled.
 */
export interface OptReportIssue {
  id: string;
  title: string;
  severity: OptReportIssueSeverity;
  sessionCount: number;
  exampleSessionIds: string[];
  /**
   * V1.2: 根因 surface — agent 出错时在调哪个 surface。
   */
  suspectSurface: OptReportSuspectSurface;
  /**
   * V1.3+: 修复落点 surface — 修这个 issue 应该改哪个 surface。
   * 可能跟 suspectSurface 不同（例如：agent 调 Bash 反复失败 → suspectSurface=skill
   * 但 fixSurface=behavior_rule，因为修法是在行为层加约束规则，而不是写新 skill）。
   * Optional：旧报告 / LLM 没区分时为 null，下游用 effectiveSurface fallback。
   */
  fixSurface?: OptReportSuspectSurface | null;
  /**
   * V1.3+: BE 算出的 fixSurface || suspectSurface，convert 决策按这个走。
   * FE 仅作展示提示用，不用读 fixSurface 自算。
   */
  effectiveSurface?: OptReportSuspectSurface;
  confidence: number; // 0.0 - 1.0
  suggestion: string;
  expectedImpact?: string;
  alreadyConverted: boolean;
  convertedEventId?: number;
  /**
   * V1.2 r2: BE-side {@code convertible} flag — true iff effectiveSurface ∈
   * {skill, prompt, behavior_rule}. FE uses this to disable the button on
   * other/unclear surfaces (tooltip explains).
   */
  convertible?: boolean;
}

/**
 * OPT-REPORT-V1.2 — structured summary shape that supersedes the V1.1 raw
 * JSON string. Rendered as a list of `IssueCard`s below the markdown body.
 * V1.1 reports (and any reports where the agent failed to emit the
 * structured payload) carry `summary === null`, in which case the FE falls
 * back to the legacy `summaryJson` raw-string display.
 */
export interface OptReportSummaryStructured {
  topIssues: OptReportIssue[];
}

/**
 * Full report fields returned by `GET /api/flywheel/reports/{reportId}`.
 * Extends the summary shape with the rendered markdown / structured summary
 * / error / generator session linkage. Mirrors BE
 * `OptReportController#toFullDto`.
 *
 * V1.1 vs V1.2 compat: BE V1.2 introduces the new structured `summary`
 * field but keeps the legacy `summaryJson` raw string field for backward
 * compat (older reports stay readable). Consumers should prefer the
 * structured `summary.topIssues` and only fall back to `summaryJson` parse
 * when `summary` is null. Both fields nullable.
 *
 * `summaryJson` is a raw JSON **string** (BE stores `text` column verbatim);
 * the FE must `JSON.parse` before consuming. The renderer guards against
 * malformed JSON with a try/catch fallback.
 */
export interface OptReport extends OptReportSummary {
  contentMd: string | null;
  summary: OptReportSummaryStructured | null;
  summaryJson: string | null;
  errorReason: string | null;
  generatorSessionId: string | null;
}

/**
 * Envelope returned by the list endpoint. Per footgun #6b, this is wrapped
 * in a `{items, limit}` LinkedHashMap on the BE — do NOT collapse to bare
 * array here; consumers must read `r.data.items`.
 */
export interface ListOptReportsResponse {
  items: OptReportSummary[];
  limit: number;
}

/**
 * 202 ACCEPTED response body from
 * `POST /api/flywheel/agents/{agentId}/generate-report`.
 *
 * Mirrors BE `OptReportController#generateReport`'s `body` LinkedHashMap
 * field order: `reportId, agentId, agentName, windowStart, windowEnd,
 * status, note`. `status` is `"running"` on the happy path (the BE
 * synchronously seeds the row in `pending` and `OptReportService` flips it
 * to `running` before returning).
 */
export interface GenerateOptReportResponse {
  reportId: string;
  agentId: number;
  agentName: string;
  windowStart: string;
  windowEnd: string;
  status: OptReportStatus;
  note: string;
}

// ───────────────────────── API wrappers ───────────────────────────────────

/**
 * `POST /api/flywheel/agents/{agentId}/generate-report?windowDays=14`.
 *
 * Returns 202 immediately with the freshly-created `reportId`. The actual
 * report generation runs asynchronously inside the `report-generator` agent
 * session; the dashboard listens for the `opt_report_completed` WS event
 * (broadcast by `OptReportService#onReportCompleted`) to refresh the list.
 *
 * V1.1: default raised from 7 → 14 to exercise SubAgent fan-out (batchSize=5).
 */
export const generateOptReport = (agentId: number, windowDays: number = 14) =>
  api.post<GenerateOptReportResponse>(
    `/flywheel/agents/${agentId}/generate-report`,
    null,
    { params: { windowDays } },
  );

/**
 * `GET /api/flywheel/agents/{agentId}/reports?limit=20` — list reports for
 * a single agent, newest first. BE clamps `limit` to [1, 100], default 20.
 *
 * Returns an envelope `{items, limit}` (NOT a bare array — see footgun #6b
 * notes at top of file).
 */
export const listOptReports = (agentId: number, limit: number = 20) =>
  api.get<ListOptReportsResponse>(
    `/flywheel/agents/${agentId}/reports`,
    { params: { limit } },
  );

/**
 * `GET /api/flywheel/reports/{reportId}` — single report with full
 * markdown + structured summary. Throws 404 if the reportId is unknown.
 */
export const getOptReport = (reportId: string) =>
  api.get<OptReport>(`/flywheel/reports/${reportId}`);

/**
 * OPT-REPORT-V1.2 — response body from
 * `POST /api/flywheel/reports/{reportId}/issues/{issueId}/convert-to-event`.
 *
 * Mirrors BE `OptReportController#convertIssueToEvent` ↦ creates an
 * `OptimizationEventEntity` row at stage `proposal_pending` for the old
 * flywheel curator, then echoes back the newly-minted event id (or the
 * existing one if `alreadyConverted=true`). `surface` mirrors the issue's
 * `suspectSurface` field so the FE can confirm the dispatch hit the right
 * downstream pipeline.
 */
export interface ConvertIssueResponse {
  eventId: number;
  alreadyConverted: boolean;
  surface: string;
}

/**
 * `POST /api/flywheel/reports/{reportId}/issues/{issueId}/convert-to-event`.
 *
 * Converts a single issue from the report into an OptimizationEvent
 * (stage = `proposal_pending`) so it lands in the existing flywheel
 * curator approval queue. Idempotent on the BE side: a second call with
 * the same `(reportId, issueId)` returns `alreadyConverted=true` and the
 * existing `eventId` rather than creating a duplicate.
 *
 * BE will 400 if `suspectSurface ∈ {other, unclear}` (can't dispatch to a
 * concrete pipeline) — the FE pre-disables the button for those surfaces
 * with a tooltip rather than relying on the error round-trip.
 */
export const convertIssueToEvent = (reportId: string, issueId: string) =>
  api.post<ConvertIssueResponse>(
    `/flywheel/reports/${reportId}/issues/${issueId}/convert-to-event`,
  );
