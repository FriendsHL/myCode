/**
 * SKILL-CREATOR-PHASE-1.6 (FE F4) — tests for TriggerEvaluationModal.
 *
 * 3 cases (matching task #4 brief):
 *   1. Render → agent picker + scenarios picker + disabled threshold slider
 *      with hint, default values correct (sourceSessionId pre-filled)
 *   2. Click "Trigger" → triggerEvaluation API called with correct payload
 *      shape (matches BE Phase 1.2 @RequestBody contract); onClose + onSuccess
 *      fire after resolution
 *   3. API error → message.error surfaced, modal stays open
 */
import React from 'react';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { SkillDraft } from '../../../api';

// jsdom polyfills that AntD Select / Modal touch.
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

// Module-level mocks so we can assert calls. `triggerEvaluation` is the unit
// under test wiring; `getAgents` mocked to a fixed user-typed agent list so
// the form is deterministic without an HTTP shim.
const triggerEvaluationMock = vi.fn();
const getAgentsMock = vi.fn();
const messageErrorMock = vi.fn();
const messageSuccessMock = vi.fn();

vi.mock('../../../api/skillDrafts', async (importOriginal) => {
  const actual: typeof import('../../../api/skillDrafts') = await importOriginal();
  return {
    ...actual,
    triggerEvaluation: (...args: unknown[]) =>
      triggerEvaluationMock(...args) as ReturnType<
        typeof actual.triggerEvaluation
      >,
  };
});

vi.mock('../../../api', async (importOriginal) => {
  const actual: typeof import('../../../api') = await importOriginal();
  return {
    ...actual,
    getAgents: (...args: unknown[]) =>
      getAgentsMock(...args) as ReturnType<typeof actual.getAgents>,
  };
});

vi.mock('antd', async (importOriginal) => {
  const actual: typeof import('antd') = await importOriginal();
  return {
    ...actual,
    message: {
      ...actual.message,
      error: (...args: unknown[]) => messageErrorMock(...args),
      success: (...args: unknown[]) => messageSuccessMock(...args),
    },
  };
});

import { TriggerEvaluationModal } from '../TriggerEvaluationModal';

const draftFixture: SkillDraft = {
  id: 'draft-abc',
  ownerId: 1,
  name: 'csv-analyzer',
  description: 'Analyze CSVs',
  status: 'draft',
  createdAt: '2026-05-18T10:00:00Z',
  sourceSessionId: 'sess-source-1',
};

const userAgentsFixture = [
  { id: 42, name: 'analyst-agent', description: 'Helps with data work' },
  { id: 43, name: 'writer-agent', description: 'Writes copy' },
];

function renderModal(overrides: Partial<React.ComponentProps<typeof TriggerEvaluationModal>> = {}) {
  const onClose = vi.fn();
  const onSuccess = vi.fn();
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 } },
  });
  const utils = render(
    <QueryClientProvider client={client}>
      <TriggerEvaluationModal
        draft={draftFixture}
        open
        onClose={onClose}
        onSuccess={onSuccess}
        {...overrides}
      />
    </QueryClientProvider>,
  );
  return { ...utils, onClose, onSuccess };
}

describe('TriggerEvaluationModal', () => {
  beforeEach(() => {
    triggerEvaluationMock.mockReset();
    getAgentsMock.mockReset();
    messageErrorMock.mockReset();
    messageSuccessMock.mockReset();
    getAgentsMock.mockResolvedValue({ data: userAgentsFixture });
    triggerEvaluationMock.mockResolvedValue({
      data: { draftId: draftFixture.id, status: 'evaluating' },
    });
  });

  it('renders modal with agent picker, scenarios picker, and disabled threshold hint', async () => {
    renderModal();

    // Modal frame visible
    expect(
      await screen.findByTestId('trigger-evaluation-modal'),
    ).toBeInTheDocument();
    expect(screen.getByText('Trigger Evaluation')).toBeInTheDocument();

    // Agent select (Form.Item label "Target agent") — className used as
    // testid surrogate because AntD Select swallows data-testid prop.
    expect(screen.getByText(/Target agent/i)).toBeInTheDocument();
    expect(document.querySelector('.trigger-eval-agent-select')).toBeTruthy();

    // Source session info row (Phase 1.6 hotfix r2: removed scenarios picker
    // because BE auto-builds from draft.sourceSessionId — picker UX was
    // misleading, BE ignores user-typed session ids).
    expect(screen.getByText(/Source session:/i)).toBeInTheDocument();
    expect(document.querySelector('[data-testid="trigger-eval-source-session-info"]')).toBeTruthy();

    // Threshold slider disabled + hint visible
    expect(screen.getByText(/hardcoded 5pp/i)).toBeInTheDocument();
    const slider = document.querySelector('.trigger-eval-threshold-slider');
    expect(slider).toBeTruthy();
    // Slider has disabled class when `disabled` prop set
    expect(slider).toHaveClass('ant-slider-disabled');

    // getAgents was called with 'user' filter (Ratify: exclude system agents)
    await waitFor(() => {
      expect(getAgentsMock).toHaveBeenCalledWith('user');
    });
  });

  it('submits triggerEvaluation with correct payload shape and closes', async () => {
    const { onClose, onSuccess } = renderModal();

    // Wait for agents to load (Select renders loading state otherwise)
    await waitFor(() => {
      expect(getAgentsMock).toHaveBeenCalled();
    });
    // Use accessible role 'combobox' — AntD Select inner input has it.
    // Two comboboxes are rendered (target agent + scenarios); the first one
    // is "Target agent" since it's higher in the Form.
    const comboboxes = await waitFor(() => {
      const list = screen.getAllByRole('combobox');
      if (list.length < 1) throw new Error('comboboxes not ready');
      return list;
    });
    const agentSelect = comboboxes[0];
    fireEvent.mouseDown(agentSelect);

    // Wait for dropdown options to mount
    const analystOption = await screen.findByText('analyst-agent');
    fireEvent.click(analystOption);

    // Click OK button (AntD renders as "Trigger" per okText)
    const okBtn = screen.getByRole('button', { name: /^Trigger$/ });
    fireEvent.click(okBtn);

    await waitFor(() => {
      expect(triggerEvaluationMock).toHaveBeenCalledTimes(1);
    });

    const [draftId, payload] = triggerEvaluationMock.mock.calls[0] as [
      string,
      { targetAgentId: number; scenarios?: string[]; threshold?: number },
    ];
    expect(draftId).toBe('draft-abc');
    expect(payload.targetAgentId).toBe(42);
    // Phase 1.6 hotfix (2026-05-19): scenarios is intentionally undefined so
    // BE auto-builds ephemeral EvalScenario rows from draft.sourceSessionId.
    // Pre-filling with [draft.sourceSessionId] (session UUID) caused BE 400
    // "Eval scenario not found" because scenarios[] expects EvalScenarioEntity
    // IDs, not session UUIDs (different entities).
    expect(payload.scenarios).toBeUndefined();
    // Phase 1.6 hardcodes threshold at BE default — payload omits the field
    expect(payload.threshold).toBeUndefined();

    await waitFor(() => {
      expect(messageSuccessMock).toHaveBeenCalled();
      expect(onSuccess).toHaveBeenCalledWith('draft-abc');
      expect(onClose).toHaveBeenCalled();
    });
  });

  it('shows error toast and keeps modal open when API fails', async () => {
    triggerEvaluationMock.mockRejectedValueOnce(new Error('Network down'));
    const { onClose, onSuccess } = renderModal();

    await waitFor(() => {
      expect(getAgentsMock).toHaveBeenCalled();
    });

    // Same agent-pick flow (via accessible combobox role)
    const comboboxes = await waitFor(() => {
      const list = screen.getAllByRole('combobox');
      if (list.length < 1) throw new Error('comboboxes not ready');
      return list;
    });
    const agentSelect = comboboxes[0];
    fireEvent.mouseDown(agentSelect);
    const writerOption = await screen.findByText('writer-agent');
    fireEvent.click(writerOption);

    const okBtn = screen.getByRole('button', { name: /^Trigger$/ });
    fireEvent.click(okBtn);

    await waitFor(() => {
      expect(messageErrorMock).toHaveBeenCalled();
    });
    expect(messageErrorMock.mock.calls[0][0]).toMatch(/Network down/);
    // Modal stays open (onClose NOT called) — operator can retry
    expect(onClose).not.toHaveBeenCalled();
    expect(onSuccess).not.toHaveBeenCalled();
  });
});
