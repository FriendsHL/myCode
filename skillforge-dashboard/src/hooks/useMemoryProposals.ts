import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  approveMemoryProposal,
  autoArchiveStaleMemoryProposals,
  editMemoryProposal,
  listMemoryProposals,
  rejectMemoryProposal,
  revertMemoryProposal,
  runMemorySynthesisOnce,
  type ApproveProposalOptions,
  type EditProposalPatch,
  type MemoryProposal,
  type MemoryProposalStatus,
  type MemoryProposalType,
} from '../api/memoryProposalsApi';

/**
 * MEMORY-LLM-SYNTHESIS — TanStack Query hook (D16 ratified).
 *
 * Cache layout (D16):
 *   queryKey = ['memoryProposals', { userId, status, proposalType? }]
 *   staleTime = 60s (proposal list is human-scale; no need for sub-second freshness)
 *
 * Every mutation invalidates the whole `['memoryProposals', ...]` namespace
 * so the list reflects status flips (approve → drop from `proposed`, etc.)
 * AND so newly archived source memories invalidate stale-check state on
 * sibling proposals.
 *
 * D16 invariant: also invalidates `['memories', userId]` so the existing
 * `MemoryList` page sees archived losers / new reflection rows immediately
 * after approve.
 */

export interface UseMemoryProposalsArgs {
  userId: number;
  status?: MemoryProposalStatus;
  proposalType?: MemoryProposalType;
  /** Allow caller to disable polling — defaults true. */
  enabled?: boolean;
}

const PROPOSAL_LIST_NS = 'memoryProposals';

function memoriesKey(userId: number) {
  return ['memories', userId] as const;
}

export function useMemoryProposals({
  userId,
  status = 'proposed',
  enabled = true,
}: UseMemoryProposalsArgs) {
  const queryClient = useQueryClient();

  const queryKey = [PROPOSAL_LIST_NS, { userId, status }] as const;

  const proposalsQuery = useQuery<MemoryProposal[]>({
    queryKey,
    queryFn: () =>
      listMemoryProposals({ userId, status }).then((r) => r.data),
    staleTime: 60_000,
    enabled,
  });

  const invalidate = () => {
    queryClient.invalidateQueries({ queryKey: [PROPOSAL_LIST_NS] });
    queryClient.invalidateQueries({ queryKey: memoriesKey(userId) });
  };

  const approveMutation = useMutation({
    mutationFn: (args: { id: number; options?: ApproveProposalOptions }) =>
      approveMemoryProposal(args.id, args.options).then((r) => r.data),
    onSuccess: invalidate,
  });

  const rejectMutation = useMutation({
    mutationFn: (id: number) => rejectMemoryProposal(id).then((r) => r.data),
    onSuccess: invalidate,
  });

  const editMutation = useMutation({
    mutationFn: (args: { id: number; patch: EditProposalPatch }) =>
      editMemoryProposal(args.id, args.patch).then((r) => r.data),
    onSuccess: invalidate,
  });

  const revertMutation = useMutation({
    mutationFn: (id: number) => revertMemoryProposal(id).then((r) => r.data),
    onSuccess: invalidate,
  });

  const autoArchiveMutation = useMutation({
    mutationFn: () => autoArchiveStaleMemoryProposals().then((r) => r.data),
    onSuccess: invalidate,
  });

  const runOnceMutation = useMutation({
    mutationFn: (uid: number) => runMemorySynthesisOnce(uid).then((r) => r.data),
    onSuccess: invalidate,
  });

  return {
    proposals: proposalsQuery.data ?? [],
    isLoading: proposalsQuery.isLoading,
    isError: proposalsQuery.isError,
    error: proposalsQuery.error,
    refetch: proposalsQuery.refetch,
    approveMutation,
    rejectMutation,
    editMutation,
    revertMutation,
    autoArchiveMutation,
    runOnceMutation,
  };
}
