import api from './index';
import type {
  ScheduledTask,
  ScheduledTaskRun,
  CreateScheduledTaskRequest,
  UpdateScheduledTaskRequest,
} from '../types/schedule';

/**
 * P12 — REST client for `/api/schedules`. The BE enforces ownership via
 * `@RequestParam Long userId` on every endpoint (creator_user_id ==
 * currentUser.id), so every call must pass `userId`. Pattern mirrors
 * `getMemories(userId, ...)` / `getSessionSpans(sessionId, userId, ...)`
 * in `./index`.
 */

export interface ListScheduledTasksParams {
  /** When true, BE filters to `enabled = true` rows. Defaults to false. */
  enabledOnly?: boolean;
}

export interface ListRunsParams {
  /** Page size, default 20, max 100 (BE-clamped). */
  limit?: number;
  offset?: number;
}

/**
 * Response of `POST /api/schedules/{id}/trigger`. The BE enqueues the run
 * asynchronously and returns 202 Accepted with a status marker — there is
 * no run row yet at this point, so callers must poll `listScheduleRuns` to
 * see the actual `ScheduledTaskRun`.
 */
export interface TriggerResponse {
  taskId: number;
  /** Always `'trigger_requested'` today; reserved for future BE values. */
  status: string;
}

export const listSchedules = (userId: number, params?: ListScheduledTasksParams) =>
  api.get<ScheduledTask[]>('/schedules', { params: { ...params, userId } });

export const getSchedule = (id: number, userId: number) =>
  api.get<ScheduledTask>(`/schedules/${id}`, { params: { userId } });

export const createSchedule = (data: CreateScheduledTaskRequest, userId: number) =>
  api.post<ScheduledTask>('/schedules', data, { params: { userId } });

export const updateSchedule = (
  id: number,
  data: UpdateScheduledTaskRequest,
  userId: number,
) => api.put<ScheduledTask>(`/schedules/${id}`, data, { params: { userId } });

export const deleteSchedule = (id: number, userId: number) =>
  api.delete(`/schedules/${id}`, { params: { userId } });

/**
 * Manual fire-now. BE intentionally bypasses the `enabled` gate (INV-10) so
 * operators can force a run while debugging a paused schedule. Returns 202
 * Accepted with `{taskId, status: 'trigger_requested'}` — the run row is
 * created asynchronously by the executor and visible via {@link listScheduleRuns}.
 */
export const triggerSchedule = (id: number, userId: number) =>
  api.post<TriggerResponse>(`/schedules/${id}/trigger`, null, { params: { userId } });

export const listScheduleRuns = (id: number, userId: number, params?: ListRunsParams) =>
  api.get<ScheduledTaskRun[]>(`/schedules/${id}/runs`, {
    params: { ...params, userId },
  });
