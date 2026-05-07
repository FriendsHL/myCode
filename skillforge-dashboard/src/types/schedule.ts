/**
 * P12 Scheduled Tasks — FE types.
 *
 * Mirrors backend `t_scheduled_task` / `t_scheduled_task_run` rows surfaced
 * via `/api/schedules`. See requirements/active/P12-scheduled-tasks/prd.md
 * + tech-design.md for canonical semantics.
 */

/** Cron periodic vs. one-shot at-trigger. The two are mutually exclusive on the row. */
export type ScheduleTriggerKind = 'cron' | 'one-shot';

/**
 * Whether each fire opens a fresh session (`new`) or reuses the same session
 * across fires (`reuse`). When `reuse`, the BE persists the first session id
 * to `reusedSessionId` after the first trigger.
 */
export type ScheduleSessionMode = 'new' | 'reuse';

/**
 * Concurrency policy for overlapping fires. MVP locks this to
 * `skip-if-running` (DB CHECK constraint); further values reserved for V2.
 */
export type ScheduleConcurrencyPolicy = 'skip-if-running';

/**
 * Run-level lifecycle state of the parent task. `completed` only applies to
 * one-shot tasks that have finished; cron tasks bounce idle/running.
 */
export type ScheduledTaskStatus = 'idle' | 'running' | 'completed' | 'error';

/**
 * Per-fire lifecycle of a `t_scheduled_task_run` row.
 *
 * - `running`   : in-flight (skip-if-running uses this)
 * - `success`   : agent reached terminal `idle` cleanly
 * - `failure`   : agent ended `error`
 * - `skipped`   : an earlier run was still running when this fire-time elapsed
 * - `timeout`   : exceeded executor timeout
 * - `paused`    : agent ended `waiting_user` (ask_user) — needs human input
 */
export type ScheduledTaskRunStatus =
  | 'running'
  | 'success'
  | 'failure'
  | 'skipped'
  | 'timeout'
  | 'paused';

/**
 * Discriminated union for `channel_target` JSONB column. `null` = don't push.
 *
 * The discriminator (`channelType`) is open-ended so the BE can add new
 * platforms (telegram, webchat, ...) without breaking the FE; unknown
 * values fall through to {@link UnknownChannelTarget} so we still render
 * something meaningful in the editor.
 */
export type ChannelTarget =
  | FeishuChannelTarget
  | TelegramChannelTarget
  | UnknownChannelTarget;

export interface FeishuChannelTarget {
  channelType: 'feishu';
  channelId: string;
}

export interface TelegramChannelTarget {
  channelType: 'telegram';
  channelId: string;
}

/**
 * Catch-all so an unrecognised future `channel_type` (e.g. `webchat`) still
 * round-trips through the editor. The form keeps the raw fields and just
 * shows a generic id input.
 */
export interface UnknownChannelTarget {
  channelType: string;
  channelId: string;
}

/**
 * One scheduled-task row as returned by `GET /api/schedules` and `GET /api/schedules/{id}`.
 *
 * Field naming follows BE camelCase (Spring Jackson default). All fields that
 * are nullable in the schema are typed as `| null` to match the JSON shape.
 */
export interface ScheduledTask {
  id: number;
  name: string;
  creatorUserId: number;
  agentId: number;
  /** Set when this task is cron-periodic; mutually exclusive with `oneShotAt`. */
  cronExpr: string | null;
  /** ISO-8601 instant; set when this task is one-shot. */
  oneShotAt: string | null;
  /** IANA timezone id (e.g. `Asia/Shanghai`); applies to cron evaluation. */
  timezone: string;
  promptTemplate: string;
  sessionMode: ScheduleSessionMode;
  /** Populated by the BE after the first fire when `sessionMode === 'reuse'`. */
  reusedSessionId: string | null;
  channelTarget: ChannelTarget | null;
  enabled: boolean;
  concurrencyPolicy: ScheduleConcurrencyPolicy;
  /** ISO-8601 instant; `null` once the task is disabled / completed. */
  nextFireAt: string | null;
  lastFireAt: string | null;
  status: ScheduledTaskStatus;
  createdAt: string;
  updatedAt: string;
}

/** One row in the run history of a task. */
export interface ScheduledTaskRun {
  id: number;
  taskId: number;
  triggeredAt: string;
  finishedAt: string | null;
  status: ScheduledTaskRunStatus;
  errorMessage: string | null;
  triggeredSessionId: string | null;
  manual: boolean;
}

/**
 * Payload for `POST /api/schedules`. Mirrors the BE `CreateScheduledTaskRequest`.
 *
 * Exactly one of `cronExpr` / `oneShotAt` must be set — the BE rejects requests
 * that have both or neither (DB CHECK + service validation).
 */
export interface CreateScheduledTaskRequest {
  name: string;
  agentId: number;
  cronExpr?: string | null;
  oneShotAt?: string | null;
  timezone?: string;
  promptTemplate: string;
  sessionMode?: ScheduleSessionMode;
  channelTarget?: ChannelTarget | null;
  enabled?: boolean;
}

/**
 * Payload for `PUT /api/schedules/{id}`. All fields optional for patch
 * semantics. To switch trigger kind (cron ↔ one-shot), submit BOTH the new
 * field set AND the old field as `null` — the BE interprets explicit `null`
 * as "clear this column" while undefined / missing keys are left untouched.
 */
export interface UpdateScheduledTaskRequest {
  name?: string;
  agentId?: number;
  cronExpr?: string | null;
  oneShotAt?: string | null;
  timezone?: string;
  promptTemplate?: string;
  sessionMode?: ScheduleSessionMode;
  channelTarget?: ChannelTarget | null;
  enabled?: boolean;
}
