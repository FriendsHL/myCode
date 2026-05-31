/**
 * EvolveRunList — rendering + selection tests.
 */
import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import EvolveRunList from '../EvolveRunList';
import type { EvolveRunSummary } from '../../../api/evolve';

function makeSummary(overrides: Partial<EvolveRunSummary> = {}): EvolveRunSummary {
  return {
    evolveRunId: 'run-001',
    status: 'completed',
    createdAt: '2026-05-31T09:00:00Z',
    updatedAt: '2026-05-31T09:45:00Z',
    iterationCount: 5,
    finalDelta: 0.12,
    ...overrides,
  };
}

describe('EvolveRunList', () => {
  it('renders loading state', () => {
    render(
      <EvolveRunList runs={[]} selectedRunId={null} loading onSelect={() => {}} />,
    );
    expect(screen.getByTestId('evolve-run-list-loading')).toBeInTheDocument();
  });

  it('renders empty state when no runs', () => {
    render(
      <EvolveRunList runs={[]} selectedRunId={null} loading={false} onSelect={() => {}} />,
    );
    expect(screen.getByTestId('evolve-run-list-empty')).toBeInTheDocument();
  });

  it('renders a row for each run', () => {
    const runs = [
      makeSummary({ evolveRunId: 'run-001' }),
      makeSummary({ evolveRunId: 'run-002', iterationCount: 2 }),
    ];
    render(
      <EvolveRunList runs={runs} selectedRunId={null} loading={false} onSelect={() => {}} />,
    );
    expect(screen.getByTestId('evolve-run-list')).toBeInTheDocument();
    expect(screen.getByTestId('evolve-run-row-run-001')).toBeInTheDocument();
    expect(screen.getByTestId('evolve-run-row-run-002')).toBeInTheDocument();
  });

  it('calls onSelect with the correct runId when a row is clicked', () => {
    const onSelect = vi.fn();
    const runs = [makeSummary({ evolveRunId: 'run-abc' })];
    render(
      <EvolveRunList runs={runs} selectedRunId={null} loading={false} onSelect={onSelect} />,
    );
    fireEvent.click(screen.getByTestId('evolve-run-row-run-abc'));
    expect(onSelect).toHaveBeenCalledOnce();
    expect(onSelect).toHaveBeenCalledWith('run-abc');
  });

  it('marks the selected run row with --selected class', () => {
    const runs = [
      makeSummary({ evolveRunId: 'run-001' }),
      makeSummary({ evolveRunId: 'run-002' }),
    ];
    render(
      <EvolveRunList
        runs={runs}
        selectedRunId="run-001"
        loading={false}
        onSelect={() => {}}
      />,
    );
    expect(screen.getByTestId('evolve-run-row-run-001')).toHaveClass('erl-row--selected');
    expect(screen.getByTestId('evolve-run-row-run-002')).not.toHaveClass('erl-row--selected');
  });

  it('shows positive delta in green-ish class text', () => {
    const runs = [makeSummary({ evolveRunId: 'run-001', finalDelta: 0.12 })];
    render(
      <EvolveRunList runs={runs} selectedRunId={null} loading={false} onSelect={() => {}} />,
    );
    const row = screen.getByTestId('evolve-run-row-run-001');
    // delta text contains + prefix
    expect(row.textContent).toContain('+0.1pp');
  });

  it('shows null delta as em dash', () => {
    const runs = [makeSummary({ evolveRunId: 'run-001', finalDelta: null })];
    render(
      <EvolveRunList runs={runs} selectedRunId={null} loading={false} onSelect={() => {}} />,
    );
    expect(screen.getByTestId('evolve-run-row-run-001').textContent).toContain('—');
  });
});
