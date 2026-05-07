/**
 * P10 — `executeCommand` API client unit tests.
 *
 * Verifies the request body / params shape the BE expects:
 *   POST /api/commands/execute  body { sessionId, command, args, userId }
 *   query: { userId } (P12 ownership pattern)
 *
 * INV-15 is enforced here at the split boundary: `/model gpt-4` produces
 * `command="/model"` not `"/models"`, even though both start with `/m`.
 *
 * UI-level dispatch (displayMode → callback) is covered in
 * `src/components/chat/__tests__/ChatInput.test.tsx`.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../index', () => {
  const post = vi.fn();
  return {
    default: { post, get: vi.fn(), put: vi.fn(), delete: vi.fn() },
    extractList: <T,>(res: { data: T[] | { data: T[] } | unknown }): T[] =>
      Array.isArray(res.data) ? (res.data as T[]) : [],
  };
});

import api from '../index';
import { executeCommand } from '../commands';
import type { CommandResult } from '../../types/command';

const mockedPost = (api as unknown as { post: ReturnType<typeof vi.fn> }).post;

describe('executeCommand — request shape', () => {
  beforeEach(() => {
    mockedPost.mockReset();
  });

  it('splits "/new" into command="/new" args=""', async () => {
    const result: CommandResult = {
      success: true,
      message: 'created',
      displayMode: 'redirect',
      newSessionId: 'sess-2',
    };
    mockedPost.mockResolvedValueOnce({ data: result });

    const out = await executeCommand(7, 'sess-1', '/new');

    // P10 r2 (W1/W2 cleanup): userId is in the BODY only — no query param
    // duplication. BE r2 / B1 reads ownership from the body record.
    expect(mockedPost).toHaveBeenCalledWith('/commands/execute', {
      sessionId: 'sess-1',
      command: '/new',
      args: '',
      userId: 7,
    });
    expect(out).toEqual(result);
  });

  it('splits "/model gpt-4o" into command="/model" args="gpt-4o" (INV-15 routes to /model handler)', async () => {
    mockedPost.mockResolvedValueOnce({
      data: {
        success: true,
        message: 'switched to gpt-4o',
        displayMode: 'toast',
        modelId: 'gpt-4o',
      } satisfies CommandResult,
    });

    await executeCommand(1, 'sess-1', '/model gpt-4o');

    const [, body] = mockedPost.mock.calls[0];
    expect(body).toEqual({
      sessionId: 'sess-1',
      command: '/model',
      args: 'gpt-4o',
      userId: 1,
    });
  });

  it('splits "/models" into command="/models" args="" (NOT /model)', async () => {
    mockedPost.mockResolvedValueOnce({
      data: {
        success: true,
        message: 'list',
        displayMode: 'modal',
        markdownBody: '# Models',
      } satisfies CommandResult,
    });

    await executeCommand(1, 'sess-1', '/models');

    const [, body] = mockedPost.mock.calls[0];
    expect(body.command).toBe('/models');
    expect(body.args).toBe('');
  });

  it('preserves multi-word args after the first space', async () => {
    mockedPost.mockResolvedValueOnce({
      data: {
        success: true,
        message: 'ok',
        displayMode: 'toast',
      } satisfies CommandResult,
    });

    await executeCommand(1, 'sess-1', '/new   my agent name  ');

    const [, body] = mockedPost.mock.calls[0];
    expect(body.command).toBe('/new');
    expect(body.args).toBe('my agent name');
  });

  it('sends userId ONLY in the body (no query param duplication)', async () => {
    mockedPost.mockResolvedValueOnce({
      data: { success: true, message: '', displayMode: 'toast' } satisfies CommandResult,
    });

    await executeCommand(42, 'sess-x', '/help');

    const callArgs = mockedPost.mock.calls[0];
    const [url, body] = callArgs;
    expect(url).toBe('/commands/execute');
    expect(body.userId).toBe(42);
    // Third arg should be undefined — no `{ params: { userId } }` config.
    expect(callArgs[2]).toBeUndefined();
  });

  it('returns the raw CommandResult from BE without modification (each displayMode)', async () => {
    const cases: CommandResult[] = [
      { success: true, message: 'redirect', displayMode: 'redirect', newSessionId: 's-1' },
      { success: true, message: 'switched', displayMode: 'toast', modelId: 'gpt-4o' },
      { success: true, message: '/help', displayMode: 'modal', markdownBody: '# Help' },
      { success: false, message: '', displayMode: 'toast', error: 'unknown' },
    ];
    for (const c of cases) {
      mockedPost.mockResolvedValueOnce({ data: c });
      const out = await executeCommand(1, 's-1', '/x');
      expect(out).toEqual(c);
    }
  });
});
