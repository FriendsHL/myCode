/**
 * AUTOEVOLVE-AGENT-FLYWHEEL Module D — REST client for evolve run trajectories.
 *
 * Envelope contracts (footgun #6b — outer shape must be locked here):
 *
 *   GET /api/evolve/agents/{agentId}/runs?limit=N
 *     → { items: EvolveRunSummary[] }       ← FE reads r.data.items
 *
 *   GET /api/evolve/runs/{evolveRunId}
 *     → EvolveRunDetail                     ← FE reads r.data directly (NOT enveloped)
 *
 * Field names mirror BE Jackson default camelCase. Keep in lock-step with
 * the BE DTO (java.md known footgun #6 / #6b).
 *
 * BE → FE type mapping:
 *   Java Long / Integer / int  → number
 *   Java Instant               → ISO-8601 string
 *   Java Double / BigDecimal   → number | null (when nullable boxed)
 *   Java boolean               → boolean
 */
import api from './index';

// ─────────────────────────── iteration (one row per loop) ──────────────────

export interface EvolveIteration {
  /** 1-based iteration index within this run. */
  iteration: number;
  /** Which surface type was changed: 'prompt' | 'skill' | 'behavior_rule'. */
  surface: string;
  /** Human-readable description of what changed. */
  changeDesc: string;
  /** Candidate entity id (prompt version / skill draft / rule version). */
  candidateId: string;
  /** Baseline score for this iteration (null when baseline unavailable). */
  baselineScore: number | null;
  /** Candidate score after A/B eval (null when eval not completed). */
  candidateScore: number | null;
  /** Score delta: candidateScore - baselineScore (may be negative). */
  delta: number;
  /** Whether the gate decided to keep this candidate. */
  kept: boolean;
  /** ID of the A/B run entity used for this iteration. */
  abRunId: string | null;
  /** ISO-8601 creation timestamp. */
  createdAt: string;
}

// ─────────────────────────── run summary (list item) ───────────────────────

export interface EvolveRunSummary {
  evolveRunId: string;
  /** Run lifecycle status: 'running' | 'completed' | 'error' | 'cancelled'. */
  status: string;
  /** ISO-8601 creation timestamp. */
  createdAt: string;
  /** ISO-8601 last update timestamp. */
  updatedAt: string;
  /** How many iterations have been recorded so far. */
  iterationCount: number;
  /**
   * Net score delta across all kept iterations (null when no iterations yet or
   * when the score could not be computed).
   */
  finalDelta: number | null;
}

// ─────────────────────────── run detail (trajectory) ───────────────────────

export interface EvolveRunDetail {
  evolveRunId: string;
  agentId: number;
  agentName: string;
  /** Run lifecycle status. */
  status: string;
  /** ISO-8601 creation timestamp. */
  createdAt: string;
  /** ISO-8601 last update timestamp. */
  updatedAt: string;
  /** All recorded iterations, ordered by iteration number ascending. */
  iterations: EvolveIteration[];
}

// ─────────────────────────── envelope type ─────────────────────────────────

/** Envelope returned by GET /api/evolve/agents/{agentId}/runs */
interface EvolveRunListEnvelope {
  items: EvolveRunSummary[];
}

// ─────────────────────────── API wrappers ──────────────────────────────────

/**
 * List evolve runs for an agent.
 * Reads r.data.items (enveloped list).
 *
 * @param agentId  The target agent's numeric ID.
 * @param limit    Maximum number of runs to return (default 20).
 */
export const listEvolveRuns = (agentId: number, limit = 20) =>
  api.get<EvolveRunListEnvelope>(`/evolve/agents/${agentId}/runs`, {
    params: { limit },
  });

/**
 * Fetch the full detail (including iterations) for one evolve run.
 * Reads r.data directly — NOT enveloped.
 *
 * @param evolveRunId  The evolve run's string UUID / ID.
 */
export const getEvolveRun = (evolveRunId: string) =>
  api.get<EvolveRunDetail>(`/evolve/runs/${evolveRunId}`);
