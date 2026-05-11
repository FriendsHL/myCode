import React, { useMemo, useState } from 'react';
import { Button, Space, Spin, Tag, Empty, Alert, message, Result } from 'antd';
import { useAuth } from '../../contexts/AuthContext';
import { useMemoryProposals } from '../../hooks/useMemoryProposals';
import type {
  ApproveProposalOptions,
  EditProposalPatch,
  MemoryProposalType,
} from '../../api/memoryProposalsApi';
import MemoryProposalCard from './MemoryProposalCard';

const TYPE_FILTERS: { label: string; value: MemoryProposalType | 'all' }[] = [
  { label: 'All', value: 'all' },
  { label: 'Dedup', value: 'dedup' },
  { label: 'Reflection', value: 'reflection' },
  { label: 'Optimize', value: 'optimize' },
  { label: 'Contradiction', value: 'contradiction' },
];

const MemoryProposalsTab: React.FC = () => {
  const { userId } = useAuth();
  const [typeFilter, setTypeFilter] = useState<MemoryProposalType | 'all'>('all');

  const {
    proposals,
    isLoading,
    isError,
    error,
    refetch,
    approveMutation,
    rejectMutation,
    editMutation,
    revertMutation,
    runOnceMutation,
  } = useMemoryProposals({
    userId,
    status: 'proposed',
    proposalType: typeFilter === 'all' ? undefined : typeFilter,
  });

  const counts = useMemo(() => {
    const c: Record<MemoryProposalType, number> = {
      dedup: 0,
      reflection: 0,
      optimize: 0,
      contradiction: 0,
    };
    for (const p of proposals) {
      c[p.proposalType] += 1;
    }
    return c;
  }, [proposals]);

  const handleRunNow = async () => {
    try {
      const result = await runOnceMutation.mutateAsync(userId);
      const total =
        result.dedupCount +
        result.reflectionCount +
        result.optimizeCount +
        result.contradictionCount;
      // Dogfood-mode trace pointer (option A) — appended to the toast when
      // BE ran synthesis as a memory-curator agent session.
      const traceSuffix = result.sessionId
        ? ` · session ${result.sessionId.slice(0, 8)}…`
        : '';
      if (result.status === 'skipped') {
        message.info(
          `Synthesis skipped: ${result.skipReason ?? 'no viable input'}.${traceSuffix}`,
        );
      } else if (total === 0) {
        message.info(
          `Run ${result.runId ?? '—'} finished. 0 new proposals · ~$${result.estimatedUsd.toFixed(4)}.${traceSuffix}`,
        );
      } else {
        message.success(
          `Run done · dedup ${result.dedupCount} / reflection ${result.reflectionCount} / `
            + `optimize ${result.optimizeCount} / contradiction ${result.contradictionCount} `
            + `· ~$${result.estimatedUsd.toFixed(4)}${traceSuffix}`,
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
        message.warning(
          `Proposal #${id} is stale: ${res.staleReason ?? 'source changed'}.`,
        );
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
      message.error(e.response?.data?.error || e.message || 'Revert failed');
    }
  };

  return (
    <div data-testid="memory-proposals-tab" style={{ padding: '0 4px' }}>
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          marginBottom: 16,
          flexWrap: 'wrap',
          gap: 8,
        }}
      >
        <div>
          <h2 style={{ margin: 0 }}>Pending Reflections</h2>
          <p style={{ margin: '4px 0 0', color: 'var(--text-secondary, #888)', fontSize: 13 }}>
            LLM-proposed memory edits awaiting your review. Auto-archive after
            7 days.
          </p>
        </div>
        <Space>
          <Button
            type="primary"
            loading={runOnceMutation.isPending}
            onClick={handleRunNow}
            data-testid="run-llm-synthesis-btn"
          >
            Run LLM Synthesis Now
          </Button>
        </Space>
      </div>

      <Space size={6} style={{ marginBottom: 16, flexWrap: 'wrap' }}>
        {TYPE_FILTERS.map((f) => {
          const total =
            f.value === 'all'
              ? proposals.length
              : (counts[f.value as MemoryProposalType] ?? 0);
          return (
            <Tag
              key={f.value}
              color={typeFilter === f.value ? 'blue' : 'default'}
              data-testid={`type-filter-${f.value}`}
              style={{ cursor: 'pointer', padding: '4px 10px', fontSize: 12 }}
              onClick={() => setTypeFilter(f.value)}
            >
              {f.label} · {total}
            </Tag>
          );
        })}
      </Space>

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
      ) : proposals.length === 0 ? (
        <Empty
          description={
            typeFilter === 'all'
              ? 'No pending proposals. Run synthesis manually or wait for the daily 04:30 cron.'
              : `No pending ${typeFilter} proposals.`
          }
          style={{ marginTop: 48 }}
        />
      ) : (
        <>
          <Alert
            type="info"
            showIcon
            message={`Showing ${proposals.length} pending proposals (limit 50 · sorted by created_at desc)`}
            style={{ marginBottom: 12 }}
          />
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
