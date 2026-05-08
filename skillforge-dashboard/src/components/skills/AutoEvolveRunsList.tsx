import React from 'react';
import { Tag, Tooltip } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { getSkillEvolutions, type SkillEvolutionRun } from '../../api';
import { useAuth } from '../../contexts/AuthContext';
import { timeAgo } from './utils';

interface AutoEvolveRunsListProps {
  skillId: number;
}

function statusColor(status: SkillEvolutionRun['status']): string {
  switch (status) {
    case 'COMPLETED': return 'success';
    case 'RUNNING': return 'processing';
    case 'PENDING': return 'default';
    case 'PARTIAL': return 'gold';
    case 'FAILED': return 'error';
  }
}

/**
 * SKILL-EVOLVE-LOOP Phase 6 — chronological list of evolution runs for
 * a single skill. Distinct from the inline `SkillEvolutionPanel` (which
 * surfaces only the latest run + the Evolve button) — this list shows
 * every fork → A/B → promote/reject decision so the operator can audit
 * the self-improve loop.
 *
 * The current BE row shape (`SkillEvolutionRun`) doesn't expose
 * `promotedAt` or a structured decision rationale yet — for now we
 * render `successRateBefore` and `failureReason` as the decision
 * proxies, and link to the underlying A/B run via `abRunId`. Future
 * work tracked in needs/SKILL-EVOLVE-LOOP `tech-design.md` §"V2".
 */
export const AutoEvolveRunsList: React.FC<AutoEvolveRunsListProps> = ({ skillId }) => {
  // Match other skill panels' pattern (SkillAbPanel) — read userId from
  // context rather than threading a prop through the drawer. The query
  // is gated so opening the drawer pre-login doesn't fire a 401-bound
  // request through the auth interceptor (W3 review fix).
  const { userId: currentUserId } = useAuth();

  const { data: runs, isLoading, isError } = useQuery<SkillEvolutionRun[]>({
    queryKey: ['skill-evolution-runs', skillId],
    queryFn: () => getSkillEvolutions(skillId).then((r) => r.data),
    enabled: !!currentUserId,
  });

  if (isLoading) {
    return <div className="sf-empty-state">Loading evolution runs…</div>;
  }
  if (isError) {
    return (
      <div
        className="sf-empty-state"
        style={{ color: 'var(--color-err, #f0616d)' }}
      >
        Failed to load evolution runs.
      </div>
    );
  }
  if (!runs || runs.length === 0) {
    return (
      <div className="sf-empty-state">
        No automatic evolutions yet. The Tuesday cron triggers fork → A/B →
        promote when the latest score drops below the configured threshold.
      </div>
    );
  }

  // Newest first — mirrors how operators read incident logs.
  const sorted = [...runs].sort((a, b) => {
    const ta = a.createdAt ? new Date(a.createdAt).getTime() : 0;
    const tb = b.createdAt ? new Date(b.createdAt).getTime() : 0;
    return tb - ta;
  });

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
      {sorted.map((run) => {
        const before = run.successRateBefore;
        return (
          <div
            key={run.id}
            style={{
              padding: '10px 12px',
              border: '1px solid var(--border-subtle, #2a2a31)',
              borderRadius: 6,
              background: 'var(--bg-card, #15151a)',
              display: 'flex',
              flexDirection: 'column',
              gap: 4,
              fontSize: 12,
            }}
          >
            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: 8,
                flexWrap: 'wrap',
              }}
            >
              <Tag color={statusColor(run.status)} style={{ marginInlineEnd: 0 }}>
                {run.status.toLowerCase()}
              </Tag>
              <span
                style={{
                  fontFamily: 'var(--font-mono, monospace)',
                  color: 'var(--fg-3, #a8a8b1)',
                }}
              >
                #{run.id.slice(0, 8)}
              </span>
              {run.forkedSkillId != null && (
                <span style={{ color: 'var(--fg-4, #8a8a93)' }}>
                  fork → skill #{run.forkedSkillId}
                </span>
              )}
              {run.abRunId && (
                <Tooltip title={`A/B run ${run.abRunId}`}>
                  <span
                    style={{
                      color: 'var(--fg-4, #8a8a93)',
                      fontFamily: 'var(--font-mono, monospace)',
                    }}
                  >
                    A/B {run.abRunId.slice(0, 8)}
                  </span>
                </Tooltip>
              )}
              <span
                style={{
                  marginLeft: 'auto',
                  color: 'var(--fg-4, #8a8a93)',
                  fontFamily: 'var(--font-mono, monospace)',
                  fontSize: 11,
                }}
              >
                {timeAgo(run.createdAt)}
              </span>
            </div>
            {(before != null || run.usageCountBefore != null) && (
              <div
                style={{
                  color: 'var(--fg-4, #8a8a93)',
                  fontFamily: 'var(--font-mono, monospace)',
                  fontSize: 11,
                }}
              >
                {before != null && <>baseline {Math.round(before)}%</>}
                {before != null && run.usageCountBefore != null && ' · '}
                {run.usageCountBefore != null && (
                  <>{run.usageCountBefore} usage</>
                )}
              </div>
            )}
            {run.failureReason && (
              <div
                style={{
                  color: 'var(--color-err, #f0616d)',
                  fontStyle: 'italic',
                  fontSize: 11,
                }}
              >
                {run.failureReason}
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
};
