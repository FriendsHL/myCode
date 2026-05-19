import { describe, it, expect } from 'vitest';
import { normalizeMessages } from '../useChatMessages';
import type { RawMessage } from '../../types/messages';

describe('normalizeMessages — createdAt → timestamp passthrough', () => {
  it('passes createdAt to ChatMessage.timestamp on a user message', () => {
    const raw: RawMessage[] = [
      {
        role: 'user',
        content: 'hello world',
        createdAt: '2026-05-19T14:23:45.123Z',
      },
    ];

    const out = normalizeMessages(raw);

    expect(out).toHaveLength(1);
    expect(out[0].role).toBe('user');
    expect(out[0].content).toBe('hello world');
    expect(out[0].timestamp).toBe('2026-05-19T14:23:45.123Z');
  });

  it('passes createdAt to ChatMessage.timestamp on an assistant message', () => {
    const raw: RawMessage[] = [
      {
        role: 'assistant',
        content: 'reply text',
        createdAt: '2026-05-19T14:24:00.000Z',
      },
    ];

    const out = normalizeMessages(raw);

    expect(out).toHaveLength(1);
    expect(out[0].role).toBe('assistant');
    expect(out[0].content).toBe('reply text');
    expect(out[0].timestamp).toBe('2026-05-19T14:24:00.000Z');
  });

  it('leaves timestamp undefined when createdAt is missing (legacy rows)', () => {
    const raw: RawMessage[] = [
      {
        role: 'user',
        content: 'no timestamp here',
      },
      {
        role: 'assistant',
        content: 'also no timestamp',
      },
    ];

    const out = normalizeMessages(raw);

    expect(out).toHaveLength(2);
    expect(out[0].timestamp).toBeUndefined();
    expect(out[1].timestamp).toBeUndefined();
  });

  it('leaves timestamp undefined when createdAt is not a string', () => {
    // Defensive: BE Jackson should always emit string, but if a future
    // backwards-compat path emits number / null, we don't want to crash or
    // pass garbage through.
    const raw: RawMessage[] = [
      {
        role: 'user',
        content: 'odd shape',
        createdAt: 12345 as unknown as string,
      },
    ];

    const out = normalizeMessages(raw);

    expect(out).toHaveLength(1);
    expect(out[0].timestamp).toBeUndefined();
  });

  it('passes createdAt on an ask_user control message', () => {
    const raw: RawMessage[] = [
      {
        role: 'assistant',
        content: 'pick one',
        messageType: 'ask_user',
        controlId: 'ask-123',
        createdAt: '2026-05-19T15:00:00.000Z',
      },
    ];

    const out = normalizeMessages(raw);

    expect(out).toHaveLength(1);
    expect(out[0].messageType).toBe('ask_user');
    expect(out[0].timestamp).toBe('2026-05-19T15:00:00.000Z');
  });
});
