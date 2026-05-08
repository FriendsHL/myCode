import React, { useMemo } from 'react';
import { Tag, Tooltip, message } from 'antd';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  evaluateSkill,
  getSkillEvalHistory,
  type EvalHistoryEntry,
} from '../../api';
import { EvalHistoryChart } from './EvalHistoryChart';
import { formatScore, visualForScore } from './evalScore';

interface EvalHistoryPanelProps {
  skillId: number;
  currentUserId?: number;
  /**
   * Source agent for the manual evaluation. Null when the operator hasn't
   * picked one in the SkillList header — the BE rejects the request with
   * 400 if `agentId` is blank, so we disable the button + render a tooltip
   * hint instead of letting the request fly.
   */
  agentId: number | null;
}

/**
 * SKILL-EVOLVE-LOOP Phase 6 — Evaluation History tab content. Composes
 * the multi-line chart with a "Evaluate Now" button that POSTs to
 * `/api/skills/{id}/evaluate` and refreshes the history once the BE
 * persists the new row (triggered_by='manual').
 *
 * Latest-score chip surfaces above the chart so the operator gets the
 * one-glance health read without parsing the curve.
 */
export const EvalHistoryPanel: React.FC<EvalHistoryPanelProps> = ({
  skillId,
  currentUserId,
  agentId,
}) => {
  const queryClient = useQueryClient();

  const { data: history, isLoading } = useQuery<EvalHistoryEntry[]>({
    queryKey: ['skill-eval-history', skillId, 20],
    queryFn: () =>
      getSkillEvalHistory(skillId, currentUserId ?? 0, 20).then((r) => r.data),
    enabled: !!currentUserId,
    // Mirror SkillList batch query (staleTime 60s) so re-opening the drawer
    // within the same minute doesn't re-fire the GET. Manual evaluate +
    // WS skill_auto_upgraded explicitly invalidate this key, so the staleness
    // window only matters for plain tab/drawer churn.
    staleTime: 60_000,
  });

  const latest = useMemo(() => {
    if (!history || history.length === 0) return undefined;
    // BE returns DESC; the latest is index 0.
    return history[0];
  }, [history]);
  const latestVisual = visualForScore(latest?.compositeScore);

  const evaluateMutation = useMutation({
    mutationFn: () => {
      if (!currentUserId) {
        return Promise.reject(new Error('Sign in required'));
      }
      if (agentId == null) {
        // BE-1 returns 400 when agentId is blank; gate client-side so the
        // operator gets a clearer error than a generic "400 Bad Request".
        return Promise.reject(new Error('Pick a source agent in the Skills header first'));
      }
      return evaluateSkill(skillId, currentUserId, agentId).then((r) => r.data);
    },
    onSuccess: (data) => {
      const score = formatScore(data?.compositeScore);
      message.success(`Evaluation complete · score ${score}`);
      // Refresh both detail and the parent list (for the Trend / Latest cells).
      queryClient.invalidateQueries({ queryKey: ['skill-eval-history', skillId] });
      queryClient.invalidateQueries({ queryKey: ['skill-eval-history-list'] });
    },
    onError: (err: unknown) => {
      const e = err as { response?: { data?: { error?: string } }; message?: string };
      message.error(e.response?.data?.error || e.message || 'Failed to evaluate skill');
    },
  });

  const disabled = evaluateMutation.isPending || !currentUserId || agentId == null;
  const disabledReason =
    !currentUserId
      ? 'Sign in required'
      : agentId == null
        ? 'Pick a source agent in the Skills header first'
        : null;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 12,
          flexWrap: 'wrap',
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <span
            style={{
              fontSize: 11,
              color: 'var(--fg-4, #8a8a93)',
              textTransform: 'uppercase',
              letterSpacing: 0.4,
            }}
          >
            Latest score
          </span>
          <Tag
            color={latestVisual.tagColor}
            style={{
              fontFamily: 'var(--font-mono, monospace)',
              fontSize: 12,
              marginInlineEnd: 0,
            }}
          >
            {formatScore(latest?.compositeScore)}
          </Tag>
          {latest && (
            <span
              style={{
                fontSize: 11,
                color: 'var(--fg-4, #8a8a93)',
                fontFamily: 'var(--font-mono, monospace)',
              }}
            >
              {latest.triggeredBy} · {new Date(latest.createdAt).toLocaleString()}
            </span>
          )}
        </div>
        <Tooltip
          title={
            disabledReason ??
            'Run a single-skill direct evaluation now (writes to t_skill_eval_history)'
          }
        >
          {/* span wrapper so disabled native <button> still hovers — same
              footgun pattern used in SkillDrawer for system-skill delete. */}
          <span style={{ marginLeft: 'auto' }}>
            <button
              className="btn-ghost-sf"
              disabled={disabled}
              onClick={() => evaluateMutation.mutate()}
              style={{ fontSize: 11, padding: '4px 12px' }}
              data-testid="evaluate-now-btn"
            >
              {evaluateMutation.isPending ? 'Evaluating…' : 'Evaluate Now'}
            </button>
          </span>
        </Tooltip>
      </div>
      <EvalHistoryChart history={history} loading={isLoading} />
    </div>
  );
};
