import api from './index';

/**
 * MULTI-SURFACE-FLYWHEEL V4 Phase 1.4 — REST client for behavior_rule version
 * snapshots persisted in {@code t_behavior_rule_version} (V82 migration).
 *
 * <p>Mirrors the BE {@code BehaviorRuleVersionEntity} field-by-field per the
 * java.md footgun #6 contract — names match Jackson default camelCase so a
 * grep on either side stays grep-able.
 *
 * <p>Endpoint surface assumed by Phase 1.4 FE work (BE-2 owns wiring these
 * up):
 *
 * <ul>
 *   <li>{@code GET /api/behavior-rules/versions?agentId=&status=} —
 *       list versions, optionally filtered by status. Returns {@code []} when
 *       no versions exist for the agent (fall back to the startup
 *       BehaviorRuleRegistry baseline, surfaced as a banner in the panel).</li>
 *   <li>{@code GET /api/behavior-rules/versions/{id}} — version detail.</li>
 * </ul>
 *
 * <p>If BE has not yet exposed these endpoints, the queries below will return
 * a 404 / network error which the panel converts to an empty-state banner so
 * the page degrades gracefully rather than crashing.
 *
 * <p>BE → FE type mapping (per java.md table):
 * <pre>
 *   Java String           → string
 *   Java int              → number
 *   Java Instant          → ISO-8601 string
 *   Java nullable boxed   → T | null
 * </pre>
 */

/** Mirrors {@code BehaviorRuleVersionEntity.STATUS_*} constants exactly. */
export type BehaviorRuleVersionStatus =
  | 'candidate'
  | 'active'
  | 'retired'
  | 'rejected';

/** Mirrors {@code BehaviorRuleVersionEntity.SOURCE_*} constants exactly. */
export type BehaviorRuleVersionSource =
  | 'manual'
  | 'attribution'
  | 'auto_improve';

/**
 * Wire shape of {@code BehaviorRuleVersionEntity}. Field names mirror the JPA
 * getters exactly (Jackson default camelCase) — see footgun #6.
 *
 * <p>Note: {@code agentId} is a VARCHAR(36) on the BE entity (UUID-style),
 * whereas {@code AgentDto.id} is a number. Callers stringify the agent id
 * before passing it through this client.
 */
export interface BehaviorRuleVersionResponse {
  id: string;
  agentId: string;
  versionNumber: number;
  status: BehaviorRuleVersionStatus;
  /** Raw JSON of the {@code BehaviorRuleConfig} blob (builtinRuleIds + customRules). */
  rulesJson: string;
  source: BehaviorRuleVersionSource;
  improvementRationale: string | null;
  sourceEventId: number | null;
  baselineVersionId: string | null;
  /** ISO-8601 (BE Instant). */
  createdAt: string;
  /** ISO-8601 or null when the version has not been promoted yet. */
  promotedAt: string | null;
}

export interface ListBehaviorRuleVersionsParams {
  /** Stringified agent id (BE column is VARCHAR(36)). */
  agentId: string;
  /** Optional status filter; omit to list all statuses for the agent. */
  status?: BehaviorRuleVersionStatus;
}

/**
 * `GET /api/behavior-rules/versions?agentId=&status=` — list versions for an
 * agent. BE expected to order by {@code versionNumber DESC} (mirrors the
 * {@code findByAgentIdOrderByVersionNumberDesc} repository method).
 */
export const listBehaviorRuleVersions = (params: ListBehaviorRuleVersionsParams) =>
  api.get<BehaviorRuleVersionResponse[]>('/behavior-rules/versions', { params });

/**
 * `GET /api/behavior-rules/versions/{id}` — single version detail. Used when
 * the panel needs to surface {@code rulesJson} + rationale for a candidate
 * the operator is inspecting.
 */
export const getBehaviorRuleVersion = (id: string) =>
  api.get<BehaviorRuleVersionResponse>(`/behavior-rules/versions/${id}`);
