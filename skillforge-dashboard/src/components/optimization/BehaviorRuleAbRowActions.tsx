import React, { useCallback } from 'react';
import { Modal, message } from 'antd';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { behaviorRuleApi, type BehaviorRuleAbRun } from '../../api/behaviorRule';
import { BehaviorRuleAbBadge } from './BehaviorRuleAbBadge';

export interface BehaviorRuleAbRowActionsProps {
  /** Candidate behavior_rule version id (UUID-style string). */
  versionId: string;
  /** Open the detail drawer for this version. */
  onOpenDetail: (versionId: string) => void;
}

/**
 * BEHAVIOR-RULE-AB-EVAL V1 — per-row container that owns the latestAbRun
 * query + promote/retry mutations for a single behavior_rule candidate.
 * Lifted out of OptimizationEvents so its react-query hooks don't run for
 * every timeline row (only behavior_rule + candidate_ready rows mount it).
 *
 * <p>Why a component (not a hook): the timeline column `render` callback can
 * mount this directly; that's the only React-tree friendly way to scope
 * useQuery to a row without lifting state to the parent.
 */
export const BehaviorRuleAbRowActions: React.FC<BehaviorRuleAbRowActionsProps> = ({
  versionId,
  onOpenDetail,
}) => {
  const queryClient = useQueryClient();

  const { data: run, isLoading } = useQuery<BehaviorRuleAbRun | null>({
    queryKey: ['behavior-rule-ab', versionId],
    queryFn: () =>
      behaviorRuleApi
        .latestAbRun(versionId)
        .then((r) => r.data)
        .catch(() => null /* 404 / network — treat as no-run-yet */),
    staleTime: 30_000,
    // r2-FE-2 fix: WS-only refresh strands the row when the socket drops.
    // Poll every 15s while the run is non-terminal so PENDING/RUNNING surfaces
    // progress even without a live WS push. Returning `false` stops polling
    // once the run hits COMPLETED/FAILED/SUPERSEDED (or no-run-yet).
    refetchInterval: (query) => {
      const status = query.state.data?.status;
      return status === 'PENDING' || status === 'RUNNING' ? 15_000 : false;
    },
  });

  const runAbMutation = useMutation({
    mutationFn: () => behaviorRuleApi.runAb(versionId),
    onSuccess: () => {
      message.success('A/B run started');
      queryClient.invalidateQueries({ queryKey: ['behavior-rule-ab', versionId] });
    },
    onError: (err: unknown) => {
      const e = err as { response?: { data?: { error?: string } }; message?: string };
      message.error(e.response?.data?.error || e.message || 'Failed to start A/B run');
    },
  });

  const promoteMutation = useMutation({
    mutationFn: () => behaviorRuleApi.promote(versionId),
    onSuccess: (resp) => {
      if (resp.data.status === 'noop') {
        message.info(resp.data.reason || 'Already active');
      } else {
        message.success('Behavior rule promoted to active');
      }
      queryClient.invalidateQueries({ queryKey: ['behavior-rule-ab', versionId] });
      // Cross-invalidate the version list so any "active version" pill updates.
      queryClient.invalidateQueries({ queryKey: ['behavior-rule-versions'] });
    },
    onError: (err: unknown) => {
      const e = err as { response?: { data?: { error?: string } }; message?: string };
      message.error(e.response?.data?.error || e.message || 'Failed to promote');
    },
  });

  const onPromote = useCallback(() => {
    Modal.confirm({
      title: 'Promote candidate to active?',
      content:
        'This retires the prior active version and makes the candidate the new ' +
        'default for the agent. Dual-criteria has been verified by BE.',
      okText: 'Promote',
      okType: 'primary',
      cancelText: 'Cancel',
      onOk: () => promoteMutation.mutateAsync(),
    });
  }, [promoteMutation]);

  const onRetry = useCallback(() => {
    Modal.confirm({
      title: 'Start a new A/B run?',
      content:
        'Existing PENDING / RUNNING runs for this candidate will be marked ' +
        'SUPERSEDED. A fresh dual-criteria evaluation will be queued.',
      okText: 'Start',
      okType: 'primary',
      cancelText: 'Cancel',
      onOk: () => runAbMutation.mutateAsync(),
    });
  }, [runAbMutation]);

  return (
    <BehaviorRuleAbBadge
      run={run ?? null}
      loading={isLoading}
      busy={runAbMutation.isPending || promoteMutation.isPending}
      onPromote={onPromote}
      onRetry={onRetry}
      onOpenDetail={() => onOpenDetail(versionId)}
    />
  );
};

export default BehaviorRuleAbRowActions;
