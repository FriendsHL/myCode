/**
 * MEMORY-LLM-SYNTHESIS FE — MemoryProposalsTab integration tests.
 *
 * Covers:
 *  1. Empty state renders "No pending proposals" copy when API returns [].
 *  2. Clicking "Run LLM Synthesis Now" hits the API + (D16 invariant)
 *     invalidates the cache so a fresh list is fetched.
 */
import React from 'react';
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import type {
  MemoryProposal,
  MemorySynthesisRunResult,
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

const listMemoryProposalsMock = vi.fn();
const runMemorySynthesisOnceMock = vi.fn();
const approveMemoryProposalMock = vi.fn();
const rejectMemoryProposalMock = vi.fn();
const editMemoryProposalMock = vi.fn();
const revertMemoryProposalMock = vi.fn();
const autoArchiveStaleMemoryProposalsMock = vi.fn();

vi.mock('../../../api/memoryProposalsApi', () => ({
  listMemoryProposals: (...args: unknown[]) => listMemoryProposalsMock(...args),
  approveMemoryProposal: (...args: unknown[]) => approveMemoryProposalMock(...args),
  rejectMemoryProposal: (...args: unknown[]) => rejectMemoryProposalMock(...args),
  editMemoryProposal: (...args: unknown[]) => editMemoryProposalMock(...args),
  revertMemoryProposal: (...args: unknown[]) => revertMemoryProposalMock(...args),
  autoArchiveStaleMemoryProposals: (...args: unknown[]) =>
    autoArchiveStaleMemoryProposalsMock(...args),
  runMemorySynthesisOnce: (...args: unknown[]) => runMemorySynthesisOnceMock(...args),
}));

vi.mock('../../../contexts/AuthContext', () => ({
  useAuth: () => ({ token: null, userId: 1, login: vi.fn(), logout: vi.fn() }),
}));

import MemoryProposalsTab from '../MemoryProposalsTab';

function renderTab() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, staleTime: 0 } },
  });
  return render(
    <QueryClientProvider client={client}>
      <MemoryProposalsTab />
    </QueryClientProvider>,
  );
}

function makeRunResult(overrides: Partial<MemorySynthesisRunResult> = {}): MemorySynthesisRunResult {
  return {
    runId: 'synth-test-1',
    dedupCount: 1,
    reflectionCount: 2,
    optimizeCount: 0,
    contradictionCount: 0,
    inputTokens: 100,
    outputTokens: 50,
    estimatedUsd: 0.0001,
    status: 'success',
    ...overrides,
  };
}

beforeEach(() => {
  listMemoryProposalsMock.mockReset();
  runMemorySynthesisOnceMock.mockReset();
  approveMemoryProposalMock.mockReset();
  rejectMemoryProposalMock.mockReset();
  editMemoryProposalMock.mockReset();
  revertMemoryProposalMock.mockReset();
  autoArchiveStaleMemoryProposalsMock.mockReset();
});

describe('MemoryProposalsTab — empty state', () => {
  it('renders "No pending proposals" copy when API returns []', async () => {
    listMemoryProposalsMock.mockResolvedValue({ data: [] as MemoryProposal[] });
    renderTab();
    await waitFor(() => {
      // Ant Empty renders the description text.
      expect(screen.getByText(/No pending proposals/i)).toBeInTheDocument();
    });
  });
});

describe('MemoryProposalsTab — Run LLM Synthesis Now button + invalidation', () => {
  it('calls runMemorySynthesisOnce(userId) and refetches proposals on success', async () => {
    // First fetch: empty list. After mutation invalidates, the list refetches
    // and we expect a second listMemoryProposals call.
    listMemoryProposalsMock.mockResolvedValue({ data: [] as MemoryProposal[] });
    runMemorySynthesisOnceMock.mockResolvedValue({ data: makeRunResult() });

    renderTab();
    await waitFor(() => {
      expect(listMemoryProposalsMock).toHaveBeenCalledTimes(1);
    });

    const initialFetchCount = listMemoryProposalsMock.mock.calls.length;
    const button = screen.getByTestId('run-llm-synthesis-btn');
    await act(async () => {
      fireEvent.click(button);
    });

    await waitFor(() => {
      expect(runMemorySynthesisOnceMock).toHaveBeenCalledTimes(1);
      expect(runMemorySynthesisOnceMock).toHaveBeenCalledWith(1);
    });

    // D16 invariant: the mutation onSuccess invalidates the proposals
    // cache namespace, so the list query refetches.
    await waitFor(() => {
      expect(listMemoryProposalsMock.mock.calls.length).toBeGreaterThan(initialFetchCount);
    });
  });
});
