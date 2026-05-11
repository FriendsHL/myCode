/**
 * MEMORY-LLM-SYNTHESIS FE — MemoryProposalCard component behavior tests.
 *
 * Covers:
 *  1. 4 type chips (dedup / reflection / optimize / contradiction) render
 *     with their data-testid markers + correct labels.
 *  2. Approve button click invokes onApprove for non-contradiction types
 *     when the dedup mass-archive threshold is NOT met.
 *  3. B-3 fix: dedup proposals with sourceMemoryIds.length - 1 >= 3 surface
 *     a confirmation Modal before approve fires (mass-archive UX guard).
 *  4. Contradiction "Pick Winner" button opens the picker instead of calling
 *     onApprove directly (F-N1: winner picked via picker → single-step
 *     approve(id, { contradictionPick }) — not a 2-step orphan path).
 */
import React from 'react';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import MemoryProposalCard from '../MemoryProposalCard';
import type {
  MemoryProposal,
  MemoryProposalType,
  ProposalSourceMemoryPreview,
} from '../../../api/memoryProposalsApi';

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

function makeSource(id: number, content = `source content ${id}`): ProposalSourceMemoryPreview {
  return {
    id,
    title: `mem-${id}`,
    content,
    importance: 'medium',
    status: 'ACTIVE',
    memoryKind: 'observation',
    createdAt: '2026-05-10T00:00:00Z',
    recallCount: 1,
  };
}

function makeProposal(
  type: MemoryProposalType,
  overrides: Partial<MemoryProposal> = {},
): MemoryProposal {
  const sourceCount = overrides.sourceMemoryIds?.length ?? 2;
  const sourceMemories =
    overrides.sourceMemories ??
    Array.from({ length: sourceCount }, (_, i) => makeSource(i + 1));
  return {
    id: 42,
    userId: 1,
    synthesisRunId: 'synth-abc-1234567890',
    proposalType: type,
    sourceMemoryIds: sourceMemories.map((m) => m.id),
    winnerMemoryId: type === 'dedup' ? sourceMemories[0].id : null,
    suggestedTitle: type === 'reflection' || type === 'optimize' ? 'suggested title' : null,
    suggestedContent:
      type === 'reflection' || type === 'optimize' ? 'suggested content' : null,
    suggestedImportance: 'medium',
    reasoning: 'because tests said so',
    llmResponseExcerpt: '{"proposals":[...]}',
    status: 'proposed',
    reviewedByUserId: null,
    reviewedAt: null,
    createdAt: '2026-05-11T00:00:00Z',
    autoArchiveAfter: '2026-05-18T00:00:00Z',
    sourceMemories,
    ...overrides,
  };
}

function renderCard(proposal: MemoryProposal, overrides: Partial<React.ComponentProps<typeof MemoryProposalCard>> = {}) {
  const onApprove = vi.fn(() => Promise.resolve());
  const onReject = vi.fn(() => Promise.resolve());
  const onEditAndApprove = vi.fn(() => Promise.resolve());
  const onRevert = vi.fn(() => Promise.resolve());
  render(
    <MemoryProposalCard
      proposal={proposal}
      approving={false}
      rejecting={false}
      editing={false}
      reverting={false}
      onApprove={onApprove}
      onReject={onReject}
      onEditAndApprove={onEditAndApprove}
      onRevert={onRevert}
      {...overrides}
    />,
  );
  return { onApprove, onReject, onEditAndApprove, onRevert };
}

describe('MemoryProposalCard — type chip rendering', () => {
  it.each<MemoryProposalType>(['dedup', 'reflection', 'optimize', 'contradiction'])(
    'renders %s type chip with correct testid',
    (type) => {
      renderCard(makeProposal(type));
      expect(screen.getByTestId(`proposal-type-chip-${type}`)).toBeInTheDocument();
    },
  );
});

describe('MemoryProposalCard — approve button behavior', () => {
  it('reflection: Approve button click directly calls onApprove (no confirm modal)', async () => {
    const { onApprove } = renderCard(makeProposal('reflection'));
    fireEvent.click(screen.getByTestId('approve-btn-42'));
    await waitFor(() => {
      expect(onApprove).toHaveBeenCalledTimes(1);
    });
    // No contradiction options — single-arg call.
    expect(onApprove).toHaveBeenCalledWith();
  });

  it('dedup with sourceMemoryIds N-1 >= 3 opens confirmation Modal (B-3 mass-delete guard)', async () => {
    // 5 sources → 4 archive losers → triggers confirm modal.
    const big = makeProposal('dedup', {
      sourceMemoryIds: [1, 2, 3, 4, 5],
      sourceMemories: [1, 2, 3, 4, 5].map((i) => makeSource(i)),
    });
    const { onApprove } = renderCard(big);
    fireEvent.click(screen.getByTestId('approve-btn-42'));

    // Modal.confirm should now be visible; onApprove must NOT have fired yet.
    await waitFor(() => {
      // getAllByText: Ant Design Modal.confirm renders title both in the
      // visible heading and an accessibility node, so multiple matches are
      // expected.
      expect(screen.getAllByText(/Archive 4 memory rows\?/i).length).toBeGreaterThan(0);
    });
    expect(onApprove).not.toHaveBeenCalled();
  });

  it('dedup with sourceMemoryIds N-1 < 3 skips confirmation modal', async () => {
    // 3 sources → 2 archive losers → below threshold.
    const small = makeProposal('dedup', {
      sourceMemoryIds: [1, 2, 3],
      sourceMemories: [1, 2, 3].map((i) => makeSource(i)),
    });
    const { onApprove } = renderCard(small);
    fireEvent.click(screen.getByTestId('approve-btn-42'));
    await waitFor(() => {
      expect(onApprove).toHaveBeenCalledTimes(1);
    });
  });
});

describe('MemoryProposalCard — describeApproveImpact regression (R2-3)', () => {
  /**
   * R2-3 (after BE TS B-1 fix: sourceMemoryIds: String → List<Long>):
   * dedup proposal with `[1, 2]` should render "Archive 1 memory rows" —
   * i.e. `.length` returns the element count (2), not the JSON-string
   * length (e.g. "[1,2]" → 5). Catches future regressions if FE typing
   * silently widens back to `string`.
   */
  it('dedup [1,2] renders "Archive 1 memory rows" (not "Archive N json-chars")', () => {
    const proposal = makeProposal('dedup', {
      sourceMemoryIds: [1, 2],
      sourceMemories: [makeSource(1), makeSource(2)],
    });
    renderCard(proposal);
    expect(
      screen.getByText(/Archive 1 memory rows and keep the winner\./i),
    ).toBeInTheDocument();
  });

  it('dedup [1,2,3,4,5] N-1=4 ≥3 boundary fires Modal.confirm (B-3 mass-delete)', async () => {
    const proposal = makeProposal('dedup', {
      sourceMemoryIds: [1, 2, 3, 4, 5],
      sourceMemories: [1, 2, 3, 4, 5].map((i) => makeSource(i)),
    });
    const { onApprove } = renderCard(proposal);
    fireEvent.click(screen.getByTestId('approve-btn-42'));
    await waitFor(() => {
      expect(screen.getAllByText(/Archive 4 memory rows\?/i).length).toBeGreaterThan(0);
    });
    expect(onApprove).not.toHaveBeenCalled();
  });
});

describe('MemoryProposalCard — contradiction picker (F-N1 single-step)', () => {
  it('contradiction: clicking Pick Winner opens picker, NOT direct approve', async () => {
    const proposal = makeProposal('contradiction', {
      sourceMemoryIds: [10, 20],
      sourceMemories: [makeSource(10), makeSource(20)],
    });
    const { onApprove } = renderCard(proposal);

    // Contradiction shows "Pick Winner" button instead of plain Approve.
    fireEvent.click(screen.getByTestId('pick-winner-btn-42'));

    // Picker modal should appear with both candidates.
    await waitFor(() => {
      expect(screen.getByText(/Pick the fact to keep/i)).toBeInTheDocument();
    });
    // onApprove should NOT have fired — the picker still needs a winner.
    expect(onApprove).not.toHaveBeenCalled();
  });
});
