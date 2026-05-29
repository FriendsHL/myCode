/**
 * AUTOEVOLVING V1 Q2 (bug fix) — WorkflowDag node-click path.
 *
 * Proves the click is handled by the node's own DOM `onClick` (not React Flow's
 * `onNodeClick`, which RF's pan/drag detection intermittently swallows). Firing
 * a DOM click on the rendered agent-node element must invoke `onStepClick` with
 * the backing step.
 */
import React from 'react';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

// React Flow needs ResizeObserver / matchMedia / DOMMatrix in jsdom.
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
if (!(globalThis as unknown as { DOMMatrixReadOnly?: unknown }).DOMMatrixReadOnly) {
  class DOMMatrixReadOnly {
    m22 = 1;
    constructor() {}
  }
  (globalThis as unknown as { DOMMatrixReadOnly: typeof DOMMatrixReadOnly }).DOMMatrixReadOnly =
    DOMMatrixReadOnly;
}

import WorkflowDag from '../WorkflowDag';
import type { WorkflowStep } from '../../../api/workflow';

function mkStep(p: Partial<WorkflowStep>): WorkflowStep {
  return {
    stepIndex: 0,
    stepKind: 'subagent_dispatch',
    status: 'completed',
    agentSlug: 'annotator',
    phase: null,
    payload: null,
    createdAt: null,
    updatedAt: null,
    ...p,
  };
}

describe('WorkflowDag node click (DOM onClick path)', () => {
  it('fires onStepClick with the backing step when an agent node is clicked', async () => {
    const onStepClick = vi.fn();
    render(
      <div style={{ width: 600, height: 400 }}>
        <WorkflowDag
          steps={[mkStep({ agentSlug: 'annotator', stepIndex: 0 })]}
          runStatus="completed"
          onStepClick={onStepClick}
        />
      </div>,
    );

    // Linear branch → agent node label = agentSlug → testid wf-node-agent-annotator.
    const node = await waitFor(() => screen.getByTestId('wf-node-agent-annotator'));
    fireEvent.click(node);

    expect(onStepClick).toHaveBeenCalledTimes(1);
    expect(onStepClick).toHaveBeenCalledWith(
      expect.objectContaining({ agentSlug: 'annotator', stepIndex: 0 }),
    );
  });
});
