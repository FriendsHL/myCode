import api from './index';
import type { CommandResult } from '../types/command';

/**
 * P10 — REST client for `/api/commands/execute`.
 *
 * The BE controller signature (see `SlashCommandController`) is
 * `POST /api/commands/execute` with body `{sessionId, command, args, userId}`.
 *
 * The body is the single source of truth for ownership; we deliberately do
 * NOT also pass `userId` as a query parameter (BE r2 / B1 fix accepts the
 * body shape). The historical P12 (`schedules.ts`) pattern uses query
 * params because those endpoints take no JSON body — that's not the case
 * here.
 */

export interface ExecuteCommandRequest {
  sessionId: string;
  /** Exact command name including leading `/`, e.g. `/new`. */
  command: string;
  /** Everything after the command name (may be empty). */
  args: string;
  userId: number;
}

/**
 * Split a raw `commandLine` (e.g. `"/model gpt-4o"`) into `{command, args}`
 * and POST it to the backend. The BE returns a `CommandResult` whose
 * `displayMode` field tells the FE how to render the result (redirect /
 * toast / modal).
 *
 * INV-15: we send the EXACT command name typed (or selected from popup)
 * — never the popup's "fuzzy match" highlight — so `/models` cannot be
 * silently routed to `/model`'s handler.
 */
export const executeCommand = async (
  userId: number,
  sessionId: string,
  commandLine: string,
): Promise<CommandResult> => {
  const trimmed = commandLine.trim();
  const firstSpace = trimmed.indexOf(' ');
  const command = firstSpace === -1 ? trimmed : trimmed.slice(0, firstSpace);
  const args = firstSpace === -1 ? '' : trimmed.slice(firstSpace + 1).trim();

  const payload: ExecuteCommandRequest = { sessionId, command, args, userId };
  const res = await api.post<CommandResult>('/commands/execute', payload);
  return res.data;
};
