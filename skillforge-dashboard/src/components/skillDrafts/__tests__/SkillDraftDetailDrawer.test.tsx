/**
 * SKILL-CREATOR-PHASE-1.6 (FE F4) — tests for SkillDraftDetailDrawer footer
 * gating on the Trigger Evaluation button.
 *
 * Coverage:
 *   - status='draft'      → trigger button visible + click opens modal
 *   - status='rejected'   → trigger button visible (same row as Iterate)
 *   - status='evaluating' → trigger button hidden (no double-fire)
 *   - status='evaluated_passed' / 'approved' / 'discarded' → hidden (terminal)
 */
import React from 'react';
import { render, screen, fireEvent } from '@testing-library/react';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { SkillDraft } from '../../../api';

// jsdom polyfills for AntD Tabs / Table / Select / Modal.
class ResizeObserverPolyfill {
  observe() {}
  unobserve() {}
  disconnect() {}
}
(globalThis as unknown as { ResizeObserver: typeof ResizeObserverPolyfill }).ResizeObserver =
  ResizeObserverPolyfill;
if (!window.matchMedia) {
  window.matchMedia = (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => false,
  });
}

// Mock getAgents so the modal's internal query has something to render once
// the operator opens it; not strictly needed for the gating tests but keeps
// the React Query warnings off the console.
const getAgentsMock = vi.fn();
vi.mock('../../../api', async (importOriginal) => {
  const actual: typeof import('../../../api') = await importOriginal();
  return {
    ...actual,
    getAgents: (...args: unknown[]) =>
      getAgentsMock(...args) as ReturnType<typeof actual.getAgents>,
  };
});

import { SkillDraftDetailDrawer } from '../SkillDraftDetailDrawer';

function makeDraft(overrides: Partial<SkillDraft> = {}): SkillDraft {
  return {
    id: 'draft-xyz',
    ownerId: 1,
    name: 'csv-analyzer',
    description: 'A sample draft',
    status: 'draft',
    createdAt: '2026-05-18T10:00:00Z',
    ...overrides,
  };
}

function renderDrawer(draft: SkillDraft) {
  const onApprove = vi.fn();
  const onDiscard = vi.fn();
  const onIterate = vi.fn();
  const onEvaluationTriggered = vi.fn();
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 } },
  });
  const utils = render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <SkillDraftDetailDrawer
          draft={draft}
          onApprove={onApprove}
          onDiscard={onDiscard}
          onIterate={onIterate}
          onEvaluationTriggered={onEvaluationTriggered}
          approving={false}
          discarding={false}
        />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return { ...utils, onApprove, onDiscard, onIterate, onEvaluationTriggered };
}

describe('SkillDraftDetailDrawer — Trigger Evaluation footer button', () => {
  beforeEach(() => {
    getAgentsMock.mockReset();
    getAgentsMock.mockResolvedValue({ data: [] });
  });

  it("shows trigger button on status='draft' and opens modal on click", async () => {
    renderDrawer(makeDraft({ status: 'draft' }));

    const triggerBtn = screen.getByTestId('trigger-evaluation-btn');
    expect(triggerBtn).toBeInTheDocument();
    expect(triggerBtn).toHaveTextContent(/Trigger Evaluation/i);

    // Discard / Approve siblings present on 'draft' (existing footer)
    expect(screen.getByRole('button', { name: /Discard/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Approve/ })).toBeInTheDocument();

    fireEvent.click(triggerBtn);
    // Modal mounts with destroyOnHidden — the testid lands once open
    expect(await screen.findByTestId('trigger-evaluation-modal')).toBeInTheDocument();
  });

  it("shows trigger button on status='rejected' (alongside Iterate)", () => {
    renderDrawer(makeDraft({ status: 'rejected' }));

    const triggerBtn = screen.getByTestId('trigger-evaluation-btn');
    expect(triggerBtn).toBeInTheDocument();

    // Iterate button present too (existing rejected-state footer)
    expect(screen.getByTestId('iterate-btn')).toBeInTheDocument();

    // Approve / Discard hidden on 'rejected' (status is "processed" already)
    expect(screen.queryByRole('button', { name: /^Approve$/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^Discard$/ })).not.toBeInTheDocument();
  });

  it("hides trigger button on status='evaluating' (no double-fire)", () => {
    renderDrawer(makeDraft({ status: 'evaluating' }));
    expect(screen.queryByTestId('trigger-evaluation-btn')).not.toBeInTheDocument();
  });

  it("hides trigger button on status='evaluated_passed' / 'approved' / 'discarded' (terminal)", () => {
    const terminalStatuses: Array<SkillDraft['status']> = [
      'evaluated_passed',
      'approved',
      'discarded',
    ];
    for (const status of terminalStatuses) {
      const { unmount } = renderDrawer(makeDraft({ status }));
      expect(
        screen.queryByTestId('trigger-evaluation-btn'),
        `status=${status} should hide trigger button`,
      ).not.toBeInTheDocument();
      unmount();
    }
  });
});
