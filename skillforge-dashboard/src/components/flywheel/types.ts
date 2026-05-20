/**
 * FLYWHEEL-VISUAL-STATUS Phase 2 — domain types for the flywheel
 * observability panel.
 *
 * The node graph mirrors PRD N1 (4 node types + dormant) and PRD N3
 * (5-level health encoding). Step IDs follow the topology report flywheel
 * 9-step ordering (E1-E4 → 1 → 2 → 3 → G1 → 4 → 5 → G2 → 6 → 7 → 8 → 9).
 */

/** PRD N1 — node type drives the LEFT border color + icon tint. */
export type NodeType = 'auto' | 'user' | 'hybrid' | 'entry' | 'dormant';

/** PRD N3 — health drives the right-rail DOT color. */
export type Health = 'healthy' | 'warn' | 'stale' | 'dormant' | 'empty';

/** Surface ↔ first-level tab in PRD R1 + N4. */
export type FlywheelSurface = 'skill' | 'prompt' | 'behavior_rule';

/** First-level tab from PRD N7 (agentType × surface). */
export type AgentTypeTab = 'user' | 'system';

/**
 * One step in the swim-lane. The static config (`STEP_CATALOGUE` below)
 * describes WHAT each step is; the live observability data (in-flight, today
 * aggregate, lag, recent error) is provided per-render by the data hook.
 */
export interface StepDescriptor {
  /** Unique step id used as React key + WS event correlation id. */
  id: string;
  /** Display title (legacy English label, kept for tests + fallback). */
  title: string;
  /** Chinese short label rendered in the node header (e.g. "① 标注"). */
  labelCn: string;
  /**
   * Chinese one-liner description shown in the detail Drawer header — what
   * this step actually does in plain words. Avoid jargon; assume the reader
   * is an operator skimming the panel.
   */
  descriptionCn: string;
  /** Sub-line under title, usually the cron expression or owner. */
  subtitle?: string;
  nodeType: NodeType;
  /**
   * Drill-down URL. Pointed at the Insights / SkillList / SessionList pages
   * with URL params consumed by Phase 2 1B routing. `null` for dormant nodes
   * (V87) — the card stays inert (no hover lift / no link).
   */
  drillDown: string | null;
  /** Surfaces this step belongs to. Empty array = surface-agnostic (rare). */
  surfaces: FlywheelSurface[];
  /** Show only for the matching agentType tab (default = both). */
  agentTypes?: AgentTypeTab[];
  /**
   * Logical grouping for the timeline (sub-header above the card). One of:
   * 'entry' (E1-E4) / 'pipeline' (1-5 + G1-G2 + 6) / 'rollout' (7-9).
   */
  group: 'entry' | 'pipeline' | 'rollout';
  /**
   * Cron interval in minutes — drives the lag→health threshold (PRD N3):
   *   lag < 2× cronInterval → healthy
   *   lag 2-3× cronInterval → warn
   *   lag > 3× cronInterval → stale
   * Non-cron steps (entry / user gates) omit this; their health uses just
   * "any activity today?" + "any recent error?".
   */
  cronIntervalMinutes?: number;
}

/** Live observability metrics fed in per-render. */
export interface StepMetrics {
  /** Current count of objects in this step (in-flight count). */
  inFlight: number;
  /** Today's aggregate count (e.g. # dispatched / # extracted / # promoted). */
  todayCount: number;
  /** ISO-8601 of the most-recent activity, or null when never. */
  lastActivityAt: string | null;
  /** Last-24h error count (failed / outcome=error). */
  recentErrorCount: number;
  /** Optional short error label to surface (truncated to ~40 chars). */
  recentErrorLabel?: string | null;
  /** Pending-action count for USER gate nodes (drives [PEND N] chip). */
  pendingActionCount?: number;
  /**
   * Loaded? when false, card shows skeleton loader. Errored? when true, card
   * dims + shows '—' for missing metrics (panel doesn't break).
   */
  loaded: boolean;
  errored?: boolean;
}

/** Activity feed event (PRD §6 24h list). */
export interface ActivityEvent {
  id: string;
  /** ISO-8601 timestamp. */
  at: string;
  /** Step id this event belongs to (for kind / color). */
  stepId: string;
  /** Short label e.g. "OptEvent #42 → proposal_pending". */
  label: string;
  /** Optional meta (e.g. "agent=insights"). */
  meta?: string;
  /** When true, label gets the 'error' tint regardless of stepId. */
  isError?: boolean;
}

/**
 * Static catalogue of all step descriptors. Each row maps to a PRD step;
 * `agentTypes` / `surfaces` declares scope so the hook can filter per active
 * tab without code branching.
 *
 * Drill-down URLs use the 1B URL routing landed in this phase (Insights
 * `?tab=optimization&stage=…`, SkillList `?skillId=&panel=…`, etc.). A few
 * existing routes (insights/patterns) already work; new behavior is
 * introduced by SkillList / SkillDrafts useSearchParams adapters.
 */
export const STEP_CATALOGUE: StepDescriptor[] = [
  // ── ENTRY ────────────────────────────────────────────────────
  {
    id: 'E1-user-chat',
    title: 'E1 · user chat session',
    labelCn: 'E1 · 用户聊天',
    descriptionCn: '用户跟 agent 真实对话产生的 session 是飞轮所有信号的源头。今日 24h 计数。',
    subtitle: 'today (24h)',
    nodeType: 'entry',
    group: 'entry',
    drillDown: '/sessions',
    surfaces: ['skill', 'prompt', 'behavior_rule'],
    agentTypes: ['user'],
  },
  {
    id: 'E2-upload-skill',
    title: 'E2 · upload skill zip',
    labelCn: 'E2 · 上传 skill',
    descriptionCn: '操作员通过 dashboard 上传 skill 压缩包，直接入飞轮 candidate 阶段。',
    subtitle: 'today (24h)',
    nodeType: 'entry',
    group: 'entry',
    drillDown: '/skills?panel=drafts&source=upload',
    surfaces: ['skill'],
  },
  {
    id: 'E3-extract-skill',
    title: 'E3 · extract from session',
    labelCn: 'E3 · 从 session 抽 skill',
    descriptionCn: '从已有 session 抽取候选 skill draft（LLM 分析对话提炼通用步骤）。',
    subtitle: 'today (24h)',
    nodeType: 'entry',
    group: 'entry',
    drillDown: '/skills?panel=drafts&source=extract-from-sessions',
    surfaces: ['skill'],
  },
  {
    id: 'E4-write-prompt',
    title: 'E4 · direct write prompt',
    labelCn: 'E4 · 直接写 prompt',
    descriptionCn: '操作员在 agent 配置页手写 prompt 改动，直接入飞轮 candidate 阶段。',
    subtitle: 'today (24h)',
    nodeType: 'entry',
    group: 'entry',
    drillDown: '/agents',
    surfaces: ['prompt'],
  },

  // ── PIPELINE (AUTO + HYBRID + USER GATES) ────────────────────
  {
    id: 'step1-annotate',
    title: '① annotate · session-annotator',
    labelCn: '① 标注',
    descriptionCn: 'session-annotator 每小时 cron 给生产 session 打 outcome 标签（success / failure / 等），为后续聚类做准备。',
    subtitle: 'hourly cron',
    nodeType: 'auto',
    group: 'pipeline',
    drillDown: '/agents',
    surfaces: ['skill', 'prompt', 'behavior_rule'],
    cronIntervalMinutes: 60,
  },
  {
    id: 'step2-cluster',
    title: '② cluster · pattern detection',
    labelCn: '② 聚类',
    descriptionCn: '把同类标注聚成 pattern，把反复出现的 failure 模式浮上来。',
    subtitle: 'hourly cron',
    nodeType: 'auto',
    group: 'pipeline',
    drillDown: '/insights/patterns?tab=patterns',
    surfaces: ['skill', 'prompt', 'behavior_rule'],
    cronIntervalMinutes: 60,
  },
  {
    id: 'step3-attribute',
    title: '③ attribute · attribution-curator',
    labelCn: '③ 归因',
    descriptionCn: 'attribution-curator 把每个 pattern 归因到具体 surface (skill / prompt / behavior_rule)，输出 OptimizationEvent 等待审批。',
    subtitle: 'hourly cron',
    nodeType: 'auto',
    group: 'pipeline',
    drillDown: '/insights/patterns?tab=optimization',
    surfaces: ['skill', 'prompt', 'behavior_rule'],
    cronIntervalMinutes: 60,
  },
  {
    id: 'G1-approve-event',
    title: 'G1 · approve OptEvent',
    labelCn: 'G1 · 审 OptEvent',
    descriptionCn: '操作员人工审归因结果：approve（进 candidate 生成）/ reject / retry。等待人工不计入"运行中"。',
    subtitle: 'operator review',
    nodeType: 'user',
    group: 'pipeline',
    drillDown: '/insights/patterns?tab=optimization&stage=proposal_pending',
    surfaces: ['skill', 'prompt', 'behavior_rule'],
  },
  {
    id: 'step4-candidate',
    title: '④ candidate · auto-generate',
    labelCn: '④ 生成候选',
    descriptionCn: 'approve 后自动生成候选 skill draft / prompt version，进 G2 / G3 等审。',
    subtitle: 'on approve',
    nodeType: 'auto',
    group: 'pipeline',
    drillDown: '/skills',
    surfaces: ['skill', 'prompt'],
  },
  {
    id: 'G2-review-draft',
    title: 'G2 · review SkillDraft / trigger eval',
    labelCn: 'G2 · 审 SkillDraft / 触发评测',
    descriptionCn: '操作员审 SkillDraft，决定是否触发 A/B 评测、改 prompt 或丢弃。等待人工不计入"运行中"。',
    subtitle: 'operator review',
    nodeType: 'user',
    group: 'pipeline',
    drillDown: '/skills?panel=drafts&status=draft',
    surfaces: ['skill'],
  },
  {
    id: 'step5-abtest',
    title: '⑤ A/B test · baseline vs candidate',
    labelCn: '⑤ A/B 评测',
    descriptionCn: 'baseline vs candidate 跑对照评测打分，输出 promote / discard 建议（可 auto 或手动 trigger）。',
    subtitle: 'on eval-trigger / auto',
    nodeType: 'hybrid',
    group: 'pipeline',
    drillDown: '/skills',
    surfaces: ['skill', 'prompt'],
  },
  {
    id: 'step6-gate',
    title: '⑥ gate · threshold + cooldown',
    labelCn: '⑥ 阀门',
    descriptionCn: '按阈值（如 pass_rate 提升 ≥X%）+ cooldown 规则判 A/B 是否通过 gate。',
    subtitle: 'on A/B complete',
    nodeType: 'auto',
    group: 'pipeline',
    drillDown: '/insights/patterns?tab=optimization&stage=ab_passed',
    surfaces: ['skill', 'prompt'],
  },
  {
    id: 'G3-promote-decision',
    title: 'G3 · promote / discard decision',
    labelCn: 'G3 · 上线 / 丢弃',
    descriptionCn: '操作员审 A/B 通过的候选，决定真上线还是丢弃（人工最后一道闸）。等待人工不计入"运行中"。',
    subtitle: 'operator review',
    nodeType: 'user',
    group: 'pipeline',
    drillDown: '/insights/patterns?tab=optimization&stage=ab_passed',
    surfaces: ['skill', 'prompt'],
  },

  // ── ROLLOUT (V87 DORMANT) ────────────────────────────────────
  {
    id: 'step7-canary',
    title: '⑦ canary · gradual rollout',
    labelCn: '⑦ 灰度发布',
    descriptionCn: 'V87 暂停 — 渐进式灰度发布到 prod（先 5%/20%/50% 流量分批）。重启 canary cron 后恢复。',
    subtitle: 'V87 disabled',
    nodeType: 'dormant',
    group: 'rollout',
    drillDown: null,
    surfaces: ['skill', 'prompt'],
  },
  {
    id: 'step8-metrics',
    title: '⑧ metrics collect',
    labelCn: '⑧ 指标回流',
    descriptionCn: 'V87 暂停 — 回收线上 metric 数据（成功率 / latency / cost）用于灰度判定。',
    subtitle: 'V87 disabled',
    nodeType: 'dormant',
    group: 'rollout',
    drillDown: null,
    surfaces: ['skill', 'prompt'],
  },
  {
    id: 'step9-decide',
    title: '⑨ final decision',
    labelCn: '⑨ 终判',
    descriptionCn: 'V87 暂停 — 按线上指标终判全量 promote 还是 rollback。',
    subtitle: 'V87 disabled',
    nodeType: 'dormant',
    group: 'rollout',
    drillDown: null,
    surfaces: ['skill', 'prompt'],
  },
];

/** PRD N3 — compute health from metrics + step config. */
export function computeHealth(step: StepDescriptor, m: StepMetrics): Health {
  if (step.nodeType === 'dormant') return 'dormant';
  if (!m.lastActivityAt && m.todayCount === 0 && m.inFlight === 0) {
    return 'empty';
  }
  const now = Date.now();
  const last = m.lastActivityAt ? new Date(m.lastActivityAt).getTime() : 0;
  const lagMin = last === 0 ? Number.POSITIVE_INFINITY : (now - last) / 60000;
  const cron = step.cronIntervalMinutes;
  // Step has a cron interval → strict threshold ladder.
  if (cron != null) {
    if (lagMin > 3 * cron || m.recentErrorCount >= 3) return 'stale';
    if (lagMin > 2 * cron || m.recentErrorCount > 0) return 'warn';
    return 'healthy';
  }
  // Non-cron (entry / user gate) → looser rule.
  if (m.recentErrorCount >= 3) return 'stale';
  if (m.recentErrorCount > 0) return 'warn';
  return 'healthy';
}

// ─────────────────────── FLYWHEEL-PER-RUN ───────────────────────
//
// Per-run mode overlays a specific OptimizationEvent's journey onto the
// existing DAG. The toggle UI lives in FlywheelFlowchart; the maps below
// drive node highlight + context-step graying.

/** Top-level toggle between aggregate metric view and per-run journey view. */
export type FlywheelMode = 'aggregate' | 'perRun';

/**
 * Maps BE `OptimizationEvent.stage` value → DAG `step.id` for per-run
 * highlighting.
 *
 * MVP coverage: 11 of 16 known BE stages (canonical list lives in
 * `OptimizationEventEntity.java:80-98`). The following 5 stages are
 * **intentionally unmapped**:
 *
 *   - `proposal_approved` — transient transition value; the BE flips it
 *     to `candidate_generating` within the same write so the FE almost
 *     never observes it in the runs response.
 *   - `candidate_created` — transient; flips to `candidate_ready` or
 *     `candidate_failed` immediately after generation completes.
 *   - `canary_started` — post-MVP rollout stage; V87 canary cron is
 *     disabled (see step7/step8/step9 dormant nodes), so no run currently
 *     transitions into this stage.
 *   - `rolled_back` — post-MVP rollout terminal; same V87 disabled gating.
 *   - `verified` — post-MVP rollout terminal; same V87 disabled gating.
 *
 * For unmapped stages, lookup returns `undefined` and `FlywheelNode.tsx`
 * falls through to the `pending-for-run` decoration (no node marked
 * "current"). This is intentional vs misleading: operator sees no node
 * marked current, which is accurate for a transient/disabled stage,
 * instead of an arbitrary highlight that wouldn't actually reflect the
 * run's location.
 *
 * To add coverage: add entry here + extend `RUN_STAGE_ORDER` if the new
 * stage falls in the main pipeline + verify `FlywheelNode.tsx` renders OK
 * via `STAGE_TO_STEP['<new-stage>']` test case in `types.test.ts`.
 *
 * Notes on existing entries:
 * - `proposal_pending` / `proposal_rejected` both localize on G1 (operator
 *   gate); errors locate on the auto step that failed so the operator can
 *   see WHERE it broke.
 * - Terminal stages (`promoted` / `discarded`) sit on G3 so the run's
 *   final state remains visible.
 * - `dispatch_initiated` is a transient mapping — per-run view treats the
 *   OptEvent as already created beyond step3 (step3-attribute is in the
 *   context set below), so the `dispatch_initiated → step3` entry is a
 *   fallback only.
 */
export const STAGE_TO_STEP: Record<string, string> = {
  dispatch_initiated: 'step3-attribute',
  proposal_pending: 'G1-approve-event',
  proposal_rejected: 'G1-approve-event',
  candidate_generating: 'step4-candidate',
  candidate_ready: 'step4-candidate',
  candidate_failed: 'step4-candidate',
  ab_running: 'step5-abtest',
  ab_passed: 'G3-promote-decision',
  ab_failed: 'step5-abtest',
  promoted: 'G3-promote-decision',
  discarded: 'G3-promote-decision',
};

/**
 * Steps that happen BEFORE a specific OptimizationEvent exists. In per-run
 * mode they're rendered with the `fw-node--context` class (gray, less
 * prominent) so the operator visually understands "these steps fed the
 * aggregate pipeline that generated this run; they aren't part of this run's
 * journey".
 *
 * step3-attribute is included because by the time the OptEvent exists the
 * curator has already finished its work for this run; the dispatch_initiated
 * stage that maps to step3 is a transient pre-pending state operators rarely
 * see.
 */
export const PRE_OPTEVENT_CONTEXT_STEPS: ReadonlySet<string> = new Set([
  'E1-user-chat',
  'E2-upload-skill',
  'E3-extract-skill',
  'E4-write-prompt',
  'step1-annotate',
  'step2-cluster',
  'step3-attribute',
]);

/**
 * Order of run-journey steps used to derive "completed" decoration in per-run
 * mode. A step is considered "completed for this run" iff its index in this
 * array is strictly less than the index of the active run's currentStep.
 *
 * G2-review-draft / step6-gate aren't directly addressed by STAGE_TO_STEP
 * today (no stage value lands on them) but they're real intermediates in the
 * pipeline so they participate in the completion order so that, e.g., when
 * currentStage='ab_running' (→ step5-abtest) we know G2 is already done.
 */
export const RUN_STAGE_ORDER: readonly string[] = [
  'step3-attribute',
  'G1-approve-event',
  'step4-candidate',
  'G2-review-draft',
  'step5-abtest',
  'step6-gate',
  'G3-promote-decision',
];

/**
 * Stages whose semantic is "this run errored at this point". Used to swap
 * the per-run highlight from green (running) to red (errored) on the
 * matching step. Terminal-success stages (`promoted`) and operator-discard
 * (`discarded`) are NOT errors.
 */
export const ERROR_STAGES: ReadonlySet<string> = new Set([
  'proposal_rejected',
  'candidate_failed',
  'ab_failed',
]);

/**
 * Wire-compatible DTO matching the BE `/api/flywheel/runs` endpoint
 * (FlywheelController.list). Field names use camelCase (Jackson default).
 * Per `.claude/rules/java.md` footgun #6 — keep this in lock-step with the
 * BE record (FlywheelRunDto); roundtrip IT on the BE side asserts the JSON
 * shape matches.
 */
export interface FlywheelRunDto {
  /** OptimizationEvent.id — uniquely identifies a run. */
  optEventId: number;
  agentId: number | null;
  agentName: string | null;
  surface: FlywheelSurface | string;
  patternId: number | null;
  /** Truncated pattern signature suitable for one-line display. */
  patternSignature: string | null;
  /** Current stage value from OptimizationEvent.stage. */
  currentStage: string;
  /**
   * Short error label when currentStage indicates failure
   * (`proposal_rejected` / `candidate_failed` / `ab_failed`), else null.
   */
  errorLabel: string | null;
  /** ISO-8601 of OptimizationEvent.createdAt. */
  startedAt: string;
  /** ISO-8601 of OptimizationEvent.updatedAt (last stage transition). */
  lastUpdatedAt: string;
  /** Linked SkillDraft.uuid when candidate was generated, else null. */
  candidateSkillDraftUuid: string | null;
  /** Linked SkillAbRun.id when an A/B run was created, else null. */
  abRunId: number | null;
  /**
   * Full `t_optimization_event.description` text. Populated by attribution-curator
   * (or operator on reject) with the narrative reason for terminal/transient
   * states — e.g. for `proposal_rejected` it carries the full rejection
   * rationale; for `candidate_failed` it carries the LLM/generation error tail.
   * Surfaced in the per-run Drawer as "原因详情" so operators don't need to
   * psql `t_optimization_event` to learn why a run stopped. Null when the
   * column wasn't populated (mostly aggregate / pending stages).
   */
  description: string | null;
}

/**
 * FLYWHEEL-PER-RUN — sub-classify ERROR_STAGES so the per-run UI can distinguish
 * "operator rejected" (a normal business outcome — amber) from "system / A/B
 * failed" (a true failure needing inspection — red). Used by StepCard chip
 * text + amber color variant and by FlywheelNode pulse hue. Adding new BE
 * stages: classify here as REJECTED (operator decision) vs FAILED (system /
 * eval outcome), or leave out for the generic "current" path.
 */
export const REJECTED_STAGES: ReadonlySet<string> = new Set(['proposal_rejected']);
export const FAILED_STAGES: ReadonlySet<string> = new Set([
  'candidate_failed',
  'ab_failed',
]);

/** PRD §6 — format lag as "47m ago" / "2h ago". */
export function formatLag(lastActivityAt: string | null): string {
  if (!lastActivityAt) return '—';
  const last = new Date(lastActivityAt).getTime();
  if (Number.isNaN(last)) return '—';
  const diffMs = Date.now() - last;
  if (diffMs < 0) return 'just now';
  const min = Math.floor(diffMs / 60000);
  if (min < 1) return 'just now';
  if (min < 60) return `${min}m ago`;
  const hr = Math.floor(min / 60);
  if (hr < 24) return `${hr}h ago`;
  const days = Math.floor(hr / 24);
  return `${days}d ago`;
}
