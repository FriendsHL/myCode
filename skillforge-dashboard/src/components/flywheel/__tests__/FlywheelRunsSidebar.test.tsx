/**
 * FLYWHEEL-PER-RUN — FlywheelRunsSidebar unit tests.
 *
 * Five cases:
 *   1. renders the list of runs sorted by lastUpdatedAt DESC + count
 *   2. click row → onSelectRun(optEventId); click active row again → null
 *   3. hideTerminal chip toggle invokes onToggleHideTerminal
 *   4. collapse toggle hides list + invokes onToggleCollapse
 *   5. empty state renders message when runs array is empty
 */
import React from 'react';
import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import FlywheelRunsSidebar from '../FlywheelRunsSidebar';
import type { FlywheelRunDto } from '../types';

function makeRun(overrides: Partial<FlywheelRunDto>): FlywheelRunDto {
  return {
    optEventId: 1,
    agentId: 10,
    agentName: 'agent-foo',
    surface: 'skill',
    patternId: 5,
    patternSignature: 'sig:hash:abcdef',
    currentStage: 'proposal_pending',
    errorLabel: null,
    startedAt: new Date(Date.now() - 60 * 60 * 1000).toISOString(),
    lastUpdatedAt: new Date(Date.now() - 30 * 60 * 1000).toISOString(),
    candidateSkillDraftUuid: null,
    abRunId: null,
    ...overrides,
  };
}

function renderSidebar(
  overrides: Partial<React.ComponentProps<typeof FlywheelRunsSidebar>> = {},
) {
  const onSelectRun = vi.fn();
  const onToggleCollapse = vi.fn();
  const onToggleHideTerminal = vi.fn();
  const defaultRuns: FlywheelRunDto[] = [
    makeRun({
      optEventId: 1,
      agentName: 'older-agent',
      lastUpdatedAt: new Date(Date.now() - 90 * 60 * 1000).toISOString(),
    }),
    makeRun({
      optEventId: 2,
      agentName: 'newer-agent',
      lastUpdatedAt: new Date(Date.now() - 10 * 60 * 1000).toISOString(),
    }),
    makeRun({
      optEventId: 3,
      agentName: 'errored-agent',
      currentStage: 'candidate_failed',
      errorLabel: 'json parse error',
      lastUpdatedAt: new Date(Date.now() - 5 * 60 * 1000).toISOString(),
    }),
  ];
  const utils = render(
    <FlywheelRunsSidebar
      runs={overrides.runs ?? defaultRuns}
      isLoading={overrides.isLoading ?? false}
      activeRunId={overrides.activeRunId ?? null}
      onSelectRun={overrides.onSelectRun ?? onSelectRun}
      isCollapsed={overrides.isCollapsed ?? false}
      onToggleCollapse={overrides.onToggleCollapse ?? onToggleCollapse}
      hideTerminal={overrides.hideTerminal ?? true}
      onToggleHideTerminal={
        overrides.onToggleHideTerminal ?? onToggleHideTerminal
      }
      mode={overrides.mode ?? 'perRun'}
    />,
  );
  return { ...utils, onSelectRun, onToggleCollapse, onToggleHideTerminal };
}

describe('FlywheelRunsSidebar', () => {
  it('renders runs sorted by lastUpdatedAt DESC with count + status emoji', () => {
    renderSidebar();
    expect(screen.getByTestId('flywheel-runs-sidebar')).toBeInTheDocument();
    expect(screen.getByText('Recent runs')).toBeInTheDocument();
    expect(screen.getByTestId('fw-runs-count').textContent).toBe('3');
    // Order: newest first (id=3 errored 5m ago, then id=2 10m, then id=1 90m).
    const rows = screen.getAllByRole('option');
    expect(rows).toHaveLength(3);
    expect(rows[0]).toHaveAttribute(
      'aria-selected',
      'false',
    );
    // First row is the errored one (most recent lastUpdatedAt).
    expect(rows[0].textContent).toContain('errored-agent');
    expect(rows[0].textContent).toContain('❌');
    // Middle row → newer-agent (proposal_pending → 🔄 active).
    expect(rows[1].textContent).toContain('newer-agent');
    expect(rows[1].textContent).toContain('🔄');
    // Last row → older-agent.
    expect(rows[2].textContent).toContain('older-agent');
  });

  it('click row emits onSelectRun(optEventId); click active row again → null', () => {
    const { onSelectRun, rerender } = renderSidebar();
    const row2 = screen.getByTestId('fw-runs-row-2');
    fireEvent.click(row2);
    expect(onSelectRun).toHaveBeenCalledWith(2);
    // Re-render with active id=2 to simulate parent state, then click row 2
    // again → expect null (deselect).
    rerender(
      <FlywheelRunsSidebar
        runs={[
          makeRun({ optEventId: 2, agentName: 'newer-agent' }),
        ]}
        isLoading={false}
        activeRunId={2}
        onSelectRun={onSelectRun}
        isCollapsed={false}
        onToggleCollapse={vi.fn()}
        hideTerminal={true}
        onToggleHideTerminal={vi.fn()}
        mode="perRun"
      />,
    );
    const row2b = screen.getByTestId('fw-runs-row-2');
    expect(row2b.getAttribute('aria-label')).toContain('OptEvent 2');
    fireEvent.click(row2b);
    // Second click: parent received `null` (deselect).
    expect(onSelectRun).toHaveBeenLastCalledWith(null);
  });

  it('hideTerminal chip toggle invokes the parent callback', () => {
    const { onToggleHideTerminal } = renderSidebar();
    const chipInput = screen
      .getByTestId('fw-runs-hideterminal-chip')
      .querySelector('input') as HTMLInputElement;
    expect(chipInput.checked).toBe(true);
    fireEvent.click(chipInput);
    expect(onToggleHideTerminal).toHaveBeenCalledTimes(1);
  });

  it('collapse toggle hides list and invokes onToggleCollapse', () => {
    const { onToggleCollapse, rerender } = renderSidebar();
    const btn = screen.getByTestId('fw-runs-collapse-btn');
    fireEvent.click(btn);
    expect(onToggleCollapse).toHaveBeenCalledTimes(1);
    // Re-render in collapsed state — list should not render, only the
    // collapse rail/button remains.
    rerender(
      <FlywheelRunsSidebar
        runs={[]}
        isLoading={false}
        activeRunId={null}
        onSelectRun={vi.fn()}
        isCollapsed={true}
        onToggleCollapse={onToggleCollapse}
        hideTerminal={true}
        onToggleHideTerminal={vi.fn()}
        mode="perRun"
      />,
    );
    const sidebar = screen.getByTestId('flywheel-runs-sidebar');
    expect(sidebar.className).toContain('fw-runs-sidebar--collapsed');
    expect(screen.queryByTestId('fw-runs-list')).not.toBeInTheDocument();
  });

  it('empty state renders helpful message when no runs', () => {
    renderSidebar({ runs: [] });
    expect(screen.getByTestId('fw-runs-empty')).toBeInTheDocument();
    expect(screen.getByTestId('fw-runs-empty').textContent).toMatch(
      /No active runs|Toggle/,
    );
  });
});
