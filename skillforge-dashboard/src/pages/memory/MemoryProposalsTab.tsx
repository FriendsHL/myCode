import React, { useMemo, useState } from 'react';
import { Button, Spin, message, Result } from 'antd';
import { useAuth } from '../../contexts/AuthContext';
import { useMemoryProposals } from '../../hooks/useMemoryProposals';
import type {
  ApproveProposalOptions,
  EditProposalPatch,
  MemoryProposal,
  MemoryProposalType,
} from '../../api/memoryProposalsApi';
import MemoryProposalCard from './MemoryProposalCard';
import '../../components/memory/proposals.css';

const TYPE_META: Record<
  MemoryProposalType,
  { label: string; color: string }
> = {
  dedup: { label: 'Dedup', color: 'var(--clr-dedup, #4a7aa8)' },
  reflection: { label: 'Reflection', color: 'var(--clr-reflection, #8a6fb3)' },
  optimize: { label: 'Optimize', color: 'var(--clr-optimize, #d49a3a)' },
  contradiction: { label: 'Contradiction', color: 'var(--clr-contradiction, #b8412f)' },
};

const TYPE_ORDER: (MemoryProposalType | 'all')[] = [
  'all', 'dedup', 'reflection', 'optimize', 'contradiction',
];

const FILTER_LABELS: Record<MemoryProposalType | 'all', string> = {
  all: 'All',
  dedup: 'Dedup',
  reflection: 'Reflection',
  optimize: 'Optimize',
  contradiction: 'Contradiction',
};

function daysUntilArchive(iso: string): number {
  return Math.max(0, Math.ceil((new Date(iso).getTime() - Date.now()) / 86_400_000));
}

const MemoryProposalsTab: React.FC = () => {
  const { userId } = useAuth();
  const [typeFilter, setTypeFilter] = useState<MemoryProposalType | 'all'>('all');

  const {
    proposals: allProposals,
    isLoading,
    isError,
    error,
    refetch,
    approveMutation,
    rejectMutation,
    editMutation,
    revertMutation,
    runOnceMutation,
  } = useMemoryProposals({ userId, status: 'proposed' });

  /* ── Client-side counts & filter ── */
  const counts = useMemo(() => {
    const c: Record<MemoryProposalType, number> = {
      dedup: 0, reflection: 0, optimize: 0, contradiction: 0,
    };
    for (const p of allProposals) c[p.proposalType] += 1;
    return c;
  }, [allProposals]);

  const proposals = useMemo<MemoryProposal[]>(
    () => typeFilter === 'all'
      ? allProposals
      : allProposals.filter((p) => p.proposalType === typeFilter),
    [allProposals, typeFilter],
  );

  const soonestArchive = useMemo(() => {
    if (allProposals.length === 0) return null;
    let earliest = Infinity;
    for (const p of allProposals) {
      const d = new Date(p.autoArchiveAfter).getTime();
      if (d < earliest) earliest = d;
    }
    return daysUntilArchive(new Date(earliest).toISOString());
  }, [allProposals]);

  /* ── Handlers ── */
  const handleRunNow = async () => {
    try {
      const result = await runOnceMutation.mutateAsync(userId);
      const total = result.dedupCount + result.reflectionCount + result.optimizeCount + result.contradictionCount;
      const traceSuffix = result.sessionId ? ` · session ${result.sessionId.slice(0, 8)}…` : '';
      if (result.status === 'skipped') {
        message.info(`Synthesis skipped: ${result.skipReason ?? 'no viable input'}.${traceSuffix}`);
      } else if (total === 0) {
        message.info(`Run done · 0 new proposals · ~$${result.estimatedUsd.toFixed(4)}.${traceSuffix}`);
      } else {
        message.success(
          `Run done · dedup ${result.dedupCount} / reflection ${result.reflectionCount} / optimize ${result.optimizeCount} / contradiction ${result.contradictionCount} · ~$${result.estimatedUsd.toFixed(4)}${traceSuffix}`,
          6,
        );
      }
    } catch (err: unknown) {
      const e = err as { response?: { data?: { error?: string } }; message?: string };
      message.error(e.response?.data?.error || e.message || 'Synthesis run failed');
    }
  };

  const handleApprove = async (id: number, options?: ApproveProposalOptions) => {
    try {
      const res = await approveMutation.mutateAsync({ id, options });
      if (res.status === 'stale') {
        message.warning(`Proposal #${id} is stale: ${res.staleReason ?? 'source changed'}.`);
      } else {
        message.success(`Proposal #${id} approved.`);
      }
    } catch (err: unknown) {
      const e = err as { response?: { data?: { error?: string } }; message?: string };
      message.error(e.response?.data?.error || e.message || 'Approve failed');
    }
  };

  const handleReject = async (id: number) => {
    try {
      await rejectMutation.mutateAsync(id);
      message.success(`Proposal #${id} rejected.`);
    } catch (err: unknown) {
      const e = err as { response?: { data?: { error?: string } }; message?: string };
      message.error(e.response?.data?.error || e.message || 'Reject failed');
    }
  };

  const handleEditApprove = async (id: number, patch: EditProposalPatch) => {
    try {
      await editMutation.mutateAsync({ id, patch });
      await handleApprove(id);
    } catch (err: unknown) {
      const e = err as { response?: { data?: { error?: string } }; message?: string };
      message.error(e.response?.data?.error || e.message || 'Edit failed');
    }
  };

  const handleRevert = async (id: number) => {
    try {
      await revertMutation.mutateAsync(id);
      message.success(`Proposal #${id} reverted; original_content restored.`);
    } catch (err: unknown) {
      const e = err as { response?: { data?: { error?: string } }; message?: string };
      message.error(e.response?.data?.error || e.message || '回退失败');
    }
  };

  return (
    <div data-testid="memory-proposals-tab" className="prop-tab">
      {/* ── Header ── */}
      <div className="prop-header">
        <div>
          <h2 className="prop-header-title">Dreaming Reflections</h2>
          <p className="prop-header-sub">
            LLM-proposed memory edits awaiting review
            {soonestArchive != null && (
              <> · auto-archive in <strong>{soonestArchive}d</strong></>
            )}
          </p>
        </div>
        <Button
          type="primary"
          loading={runOnceMutation.isPending}
          onClick={handleRunNow}
          data-testid="run-llm-synthesis-btn"
          icon={
            <svg width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
              <polygon points="5 3 13 8 5 13 5 3" fill="currentColor" />
            </svg>
          }
        >
          Run LLM Synthesis Now
        </Button>
      </div>

      {/* ── Summary Stats ── */}
      {!isLoading && !isError && (
        <div className="prop-stats">
          <div className="prop-stat">
            <span className="prop-stat-val">{allProposals.length}</span>
            <span className="prop-stat-label">Total</span>
          </div>
          {(Object.keys(TYPE_META) as MemoryProposalType[]).map((t) => (
            <div key={t} className="prop-stat">
              <span className={`prop-stat-val clr-${t}`}>{counts[t]}</span>
              <span className="prop-stat-label">{TYPE_META[t].label}</span>
            </div>
          ))}
        </div>
      )}

      {/* ── Type Filter Pills ── */}
      <div className="prop-filters">
        <div className="prop-filter-pills">
          {TYPE_ORDER.map((f) => {
            const total = f === 'all' ? allProposals.length : (counts[f as MemoryProposalType] ?? 0);
            const active = typeFilter === f;
            const dotColor = f === 'all' ? 'var(--fg-3)' : TYPE_META[f as MemoryProposalType].color;
            return (
              <button
                key={f}
                className={`prop-pill ${active ? 'on' : ''}`}
                data-testid={`type-filter-${f}`}
                onClick={() => setTypeFilter(f)}
              >
                <span className="prop-pill-dot" style={{ background: dotColor }} />
                <span>{FILTER_LABELS[f]}</span>
                <span className="prop-pill-cnt">{total}</span>
              </button>
            );
          })}
        </div>
      </div>

      {/* ── Content ── */}
      {isError ? (
        <Result
          status="error"
          title="Failed to load proposals"
          subTitle={(error as { message?: string })?.message ?? 'Unknown error'}
          extra={
            <Button type="primary" onClick={() => refetch()}>
              Retry
            </Button>
          }
        />
      ) : isLoading ? (
        <div style={{ textAlign: 'center', padding: 48 }}>
          <Spin size="large" />
        </div>
      ) : allProposals.length === 0 ? (
        <div className="prop-empty">
          <div className="prop-empty-icon">✦</div>
          <div className="prop-empty-text">No pending proposals right now</div>
          <div className="prop-empty-hint">
            Click "Run LLM Synthesis Now" or wait for the daily auto-run
          </div>
        </div>
      ) : proposals.length === 0 ? (
        <div className="prop-empty">
          <div className="prop-empty-icon">✦</div>
          <div className="prop-empty-text">
            No pending {FILTER_LABELS[typeFilter]} proposals
          </div>
          <div className="prop-empty-hint">
            No matching results for the current filter
          </div>
        </div>
      ) : (
        <>
          {proposals.map((p) => (
            <MemoryProposalCard
              key={p.id}
              proposal={p}
              approving={approveMutation.isPending && approveMutation.variables?.id === p.id}
              rejecting={rejectMutation.isPending && rejectMutation.variables === p.id}
              editing={editMutation.isPending && editMutation.variables?.id === p.id}
              reverting={revertMutation.isPending && revertMutation.variables === p.id}
              onApprove={(options) => handleApprove(p.id, options)}
              onReject={() => handleReject(p.id)}
              onEditAndApprove={(patch) => handleEditApprove(p.id, patch)}
              onRevert={() => handleRevert(p.id)}
            />
          ))}
        </>
      )}
    </div>
  );
};

export default MemoryProposalsTab;
