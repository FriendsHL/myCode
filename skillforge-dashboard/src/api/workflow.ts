/**
 * AUTOEVOLVING V1 Sprint 3 (FE) — REST client wrappers + types for the DSL
 * workflow surface (`WorkflowController`).
 *
 * Field names mirror the BE Jackson default camelCase serialization. Keep this
 * file in lock-step with the BE records / detail Map (per `.claude/rules/java.md`
 * known footgun #6 / #6b — FE-BE contract grep + outer-envelope shape + roundtrip
 * IT verification).
 *
 * BE source of truth:
 *   skillforge-server/.../workflow/dto/WorkflowDtos.java   (records)
 *   skillforge-server/.../workflow/WorkflowController.java  (envelopes + detail Map)
 *
 * Envelope shapes (footgun #6b — verified against WorkflowController):
 *   GET /api/workflows          → { items: WorkflowDto[], total }
 *   GET /api/workflows/runs      → { items: WorkflowRunSummary[], total, limit, offset }
 *   GET /api/workflows/runs/{id} → WorkflowRunDetail (single object, NOT enveloped)
 *
 * BE → FE type mapping (java.md table):
 *   Java String                → string
 *   Java String (nullable)     → string | null
 *   Java Integer/Long/int      → number
 *   Java Integer (nullable)    → number | null
 *   Java Instant (→ toIso)     → string | null  (BE emits null when the Instant is null)
 *   Java List<X>               → X[]
 */
import api from './index';

// ───────────────────────── GET /api/workflows ─────────────────────────

/** A `meta.phases[]` entry — mirrors BE `WorkflowDtos.PhaseDto`. */
export interface WorkflowPhaseDto {
  title: string;
  /** Optional human-readable detail; BE may emit null. */
  detail: string | null;
}

/** A registered workflow definition — mirrors BE `WorkflowDtos.WorkflowSummaryDto`. */
export interface WorkflowDto {
  name: string;
  description: string | null;
  phases: WorkflowPhaseDto[];
}

/** `GET /api/workflows` envelope. */
export interface ListWorkflowsResponse {
  items: WorkflowDto[];
  total: number;
}

// ───────────────────────── GET /api/workflows/runs ─────────────────────

/** One workflow run row — mirrors BE `WorkflowDtos.WorkflowRunSummaryDto`. */
export interface WorkflowRunSummary {
  runId: string;
  /** Workflow name parsed from input_json.workflow_name; null when absent. */
  name: string | null;
  /** Run status: pending / running / completed / error / paused. */
  status: string;
  /** ISO-8601; null when the underlying Instant is null. */
  createdAt: string | null;
  /** ISO-8601; null when the underlying Instant is null. */
  updatedAt: string | null;
}

/** `GET /api/workflows/runs` envelope. */
export interface ListWorkflowRunsResponse {
  items: WorkflowRunSummary[];
  total: number;
  limit: number;
  offset: number;
}

export interface ListWorkflowRunsParams {
  /** Filter by run status (pending / running / completed / error / paused). */
  status?: string;
  /** BE default 20, clamped [1, 100]. */
  limit?: number;
  /** Row offset for pagination; BE default 0. */
  offset?: number;
}

// ───────────────────── GET /api/workflows/runs/{runId} ──────────────────

/**
 * One step row inside a run detail — mirrors BE `WorkflowDtos.WorkflowStepDto`.
 *
 * `phase` is added by be-dev (Sprint 3 Task E): the `phase()` label the step was
 * dispatched under, extracted from `step_input_json.phase`. Null for legacy rows
 * / steps appended without a phase. The DAG groups steps by this field.
 *
 * `stepKind` values: `subagent_dispatch` (an agent() call) or `human_approve`
 * (a humanApprove() gate — run parks at run-level status=paused while this step
 * stays pending). `status` values: pending / completed / error (step-level; there
 * is no step-level "paused" — see WorkflowDag status derivation).
 */
export interface WorkflowStep {
  /** Monotonic dispatch index; nullable boxed Integer on the BE. */
  stepIndex: number | null;
  stepKind: string;
  status: string;
  /** Agent slug for subagent_dispatch steps; null for human_approve / legacy. */
  agentSlug: string | null;
  /** phase() label this step ran under; null for legacy / unphased steps. */
  phase: string | null;
  createdAt: string | null;
  updatedAt: string | null;
}

/**
 * `GET /api/workflows/runs/{runId}` — single object (NOT enveloped). Built as a
 * LinkedHashMap on the BE (WorkflowController.getRun) so field order is stable.
 */
export interface WorkflowRunDetail {
  runId: string;
  name: string | null;
  status: string;
  /** Raw serialized summary JSON string (workflow return value); null when absent. */
  summaryJson: string | null;
  /** Failure reason when status=error; null otherwise. */
  errorReason: string | null;
  createdAt: string | null;
  updatedAt: string | null;
  steps: WorkflowStep[];
}

// ───────────────────────────── wrappers ────────────────────────────────

/** `GET /api/workflows` — list registered workflow definitions. */
export const listWorkflows = () => api.get<ListWorkflowsResponse>('/workflows');

/** `GET /api/workflows/runs` — list workflow runs (newest-first). */
export const listWorkflowRuns = (params?: ListWorkflowRunsParams) =>
  api.get<ListWorkflowRunsResponse>('/workflows/runs', { params });

/** `GET /api/workflows/runs/{runId}` — run detail + steps. */
export const getWorkflowRun = (runId: string) =>
  api.get<WorkflowRunDetail>(`/workflows/runs/${encodeURIComponent(runId)}`);
