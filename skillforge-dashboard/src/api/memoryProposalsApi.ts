import api from './index';

/**
 * MEMORY-LLM-SYNTHESIS — FE API client for `/api/admin/memory/*` endpoints.
 *
 * Backed by `tech-design.md` §6 F5 endpoint list (r2-ratified). All endpoints
 * require admin role (BE-enforced). Per F-N1 nit fix, contradiction winner
 * picking is merged into approve as a single step — caller passes
 * `contradictionPick.winnerMemoryId` and BE PATCHes + approves atomically.
 */

export type MemoryProposalType = 'dedup' | 'reflection' | 'optimize' | 'contradiction';

export type MemoryProposalStatus =
  | 'proposed'
  | 'approved'
  | 'rejected'
  | 'auto_archived'
  | 'stale';

export type MemoryImportance = 'low' | 'medium' | 'high';

/** Source memory inline preview embedded in the proposal payload (B-3 fix:
 *  admin must see the original untrusted user-supplied content to spot
 *  injection attacks). Field name `memoryKind` matches BE
 *  `MemorySourceSummary.memoryKind` (W-1 r2 fix); `type` was a misnomer
 *  (BE has no field of that name on this DTO). */
export interface ProposalSourceMemoryPreview {
  id: number;
  title: string | null;
  content: string;
  importance: MemoryImportance | null;
  status: string;
  memoryKind?: string | null;
  createdAt: string;
  recallCount?: number | null;
}

export interface MemoryProposal {
  id: number;
  userId: number;
  synthesisRunId: string;
  proposalType: MemoryProposalType;
  sourceMemoryIds: number[];
  winnerMemoryId: number | null;
  suggestedTitle: string | null;
  suggestedContent: string | null;
  suggestedImportance: MemoryImportance | null;
  reasoning: string | null;
  // W-2 r2 fix: BE MemoryProposalDto does not expose llmPromptHash; field
  // removed to keep TS interface honest.
  llmResponseExcerpt: string | null;
  status: MemoryProposalStatus;
  reviewedByUserId: number | null;
  reviewedAt: string | null;
  createdAt: string;
  autoArchiveAfter: string;
  /** Backend-resolved inline preview of source memories — must come from BE
   *  to keep prompt-injection-aware admin UI in sync with what the LLM saw. */
  sourceMemories: ProposalSourceMemoryPreview[];
}

export interface MemorySynthesisRunResult {
  runId: string | null;
  dedupCount: number;
  reflectionCount: number;
  optimizeCount: number;
  contradictionCount: number;
  inputTokens: number;
  outputTokens: number;
  estimatedUsd: number;
  status: 'success' | 'skipped' | 'failed';
  skipReason?: string | null;
  /**
   * Dogfood mode (option A): when synthesis runs via a ScheduledTask-backed
   * memory-curator agent, BE returns the chat session that the agent
   * executed in so the operator can jump to its trace. Absent for legacy
   * direct-`LlmMemorySynthesizer.synthesize` runs.
   */
  sessionId?: string | null;
}

export interface ListProposalsParams {
  status?: MemoryProposalStatus;
  userId: number;
  limit?: number;
  proposalType?: MemoryProposalType;
}

export interface EditProposalPatch {
  suggestedTitle?: string;
  suggestedContent?: string;
  suggestedImportance?: MemoryImportance;
}

/** F-N1 fix: single-step approve. Contradiction proposals pass
 *  `contradictionPick.winnerMemoryId` so the BE PATCHes the winner and
 *  promotes in one transaction — no orphan "winner picked but not approved"
 *  state is possible. */
export interface ApproveProposalOptions {
  contradictionPick?: {
    winnerMemoryId: number;
  };
}

export interface ApproveResultDto {
  proposalId: number;
  status: 'approved' | 'stale';
  staleReason?: string | null;
}

export interface AutoArchiveResultDto {
  archived: number;
}

const BASE = '/admin/memory';

export const listMemoryProposals = (params: ListProposalsParams) =>
  api.get<MemoryProposal[]>(`${BASE}/proposals`, {
    params: {
      userId: params.userId,
      status: params.status ?? 'proposed',
      limit: params.limit ?? 50,
      proposalType: params.proposalType,
    },
  });

export const approveMemoryProposal = (
  id: number,
  options?: ApproveProposalOptions,
) =>
  api.post<ApproveResultDto>(
    `${BASE}/proposals/${id}/approve`,
    options?.contradictionPick ? { contradictionPick: options.contradictionPick } : {},
  );

export const rejectMemoryProposal = (id: number) =>
  api.post<ApproveResultDto>(`${BASE}/proposals/${id}/reject`, {});

export const editMemoryProposal = (id: number, patch: EditProposalPatch) =>
  api.patch<MemoryProposal>(`${BASE}/proposals/${id}`, patch);

/** Optimize-class only — restores `original_content` back onto `t_memory.content`. */
export const revertMemoryProposal = (id: number) =>
  api.post<ApproveResultDto>(`${BASE}/proposals/${id}/revert`, {});

export const autoArchiveStaleMemoryProposals = () =>
  api.post<AutoArchiveResultDto>(`${BASE}/proposals/auto-archive-stale`, {});

export const runMemorySynthesisOnce = (userId: number) =>
  api.post<MemorySynthesisRunResult>(`${BASE}/llm-synthesis/run-once`, null, {
    params: { userId },
  });
