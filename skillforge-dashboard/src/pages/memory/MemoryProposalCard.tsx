import React, { useState } from 'react';
import {
  Card,
  Tag,
  Button,
  Space,
  Typography,
  Modal,
  Drawer,
  Tooltip,
} from 'antd';
import type {
  ApproveProposalOptions,
  EditProposalPatch,
  MemoryProposal,
  MemoryProposalType,
  ProposalSourceMemoryPreview,
} from '../../api/memoryProposalsApi';
import MemoryProposalEditModal from './MemoryProposalEditModal';
import MemoryProposalContradictionPicker from './MemoryProposalContradictionPicker';

const { Text, Paragraph } = Typography;

interface MemoryProposalCardProps {
  proposal: MemoryProposal;
  approving: boolean;
  rejecting: boolean;
  editing: boolean;
  reverting: boolean;
  onApprove: (options?: ApproveProposalOptions) => Promise<void> | void;
  onReject: () => Promise<void> | void;
  onEditAndApprove: (patch: EditProposalPatch) => Promise<void> | void;
  onRevert: () => Promise<void> | void;
}

const TYPE_META: Record<
  MemoryProposalType,
  { label: string; color: string; tag: 'blue' | 'purple' | 'gold' | 'red' }
> = {
  dedup: { label: 'Dedup', color: 'var(--info, #4a7aa8)', tag: 'blue' },
  reflection: { label: 'Reflection', color: '#8a6fb3', tag: 'purple' },
  optimize: { label: 'Optimize', color: 'var(--color-warn, #d49a3a)', tag: 'gold' },
  contradiction: { label: 'Contradiction', color: 'var(--color-err, #b8412f)', tag: 'red' },
};

function describeApproveImpact(p: MemoryProposal): string {
  const n = p.sourceMemoryIds.length;
  switch (p.proposalType) {
    case 'dedup':
      return `Archive ${Math.max(0, n - 1)} memory rows and keep the winner.`;
    case 'reflection':
      return 'Create 1 new reflection memory (source rows untouched).';
    case 'optimize':
      return 'Rewrite 1 memory; original_content is kept and can be reverted.';
    case 'contradiction':
      return `Archive ${Math.max(0, n - 1)} contradicting fact(s), keep the winner.`;
  }
}

function fmtTime(iso: string | null): string {
  if (!iso) return '—';
  const d = new Date(iso);
  const diff = Date.now() - d.getTime();
  if (diff < 60_000) return 'just now';
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)}m ago`;
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)}h ago`;
  return d.toLocaleDateString('en-US', { month: 'short', day: 'numeric' });
}

function daysUntil(iso: string): number {
  return Math.max(0, Math.ceil((new Date(iso).getTime() - Date.now()) / 86_400_000));
}

const SourceMemoryItem: React.FC<{ src: ProposalSourceMemoryPreview }> = ({ src }) => {
  const [expanded, setExpanded] = useState(false);
  return (
    <div className="prop-source-item">
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          gap: 8,
          marginBottom: 2,
        }}
      >
        <Text strong style={{ fontSize: 'var(--font-size-xs)', color: 'var(--fg-1)' }}>
          {src.title || `Memory #${src.id}`}
        </Text>
        <Space size={4}>
          <Tag style={{ margin: 0, fontSize: 10, lineHeight: '16px', padding: '0 4px' }}>{src.status}</Tag>
          <Text style={{ fontSize: 10, color: 'var(--fg-4)' }}>
            {src.recallCount ?? 0}×
          </Text>
        </Space>
      </div>
      <Paragraph
        style={{
          marginBottom: 0,
          whiteSpace: 'pre-wrap',
          fontSize: 'var(--font-size-xs)',
          color: 'var(--fg-3)',
          lineHeight: 1.5,
        }}
        ellipsis={expanded ? false : { rows: 2, expandable: true, onExpand: () => setExpanded(true) }}
      >
        {src.content}
      </Paragraph>
    </div>
  );
};

const ChevronIcon: React.FC<{ open: boolean }> = ({ open }) => (
  <svg
    className={`prop-warning-chevron ${open ? 'open' : ''}`}
    width="12"
    height="12"
    viewBox="0 0 16 16"
    fill="none"
    stroke="currentColor"
    strokeWidth="2"
    strokeLinecap="round"
    strokeLinejoin="round"
  >
    <path d="M6 4l4 4-4 4" />
  </svg>
);

const MemoryProposalCard: React.FC<MemoryProposalCardProps> = ({
  proposal,
  approving,
  rejecting,
  editing,
  reverting,
  onApprove,
  onReject,
  onEditAndApprove,
  onRevert,
}) => {
  const [editOpen, setEditOpen] = useState(false);
  const [pickerOpen, setPickerOpen] = useState(false);
  const [rawOpen, setRawOpen] = useState(false);
  const [warnExpanded, setWarnExpanded] = useState(false);

  const meta = TYPE_META[proposal.proposalType];
  const impact = describeApproveImpact(proposal);
  const sourceCount = proposal.sourceMemoryIds.length;
  const archiveDays = daysUntil(proposal.autoArchiveAfter);
  const archiveUrgent = archiveDays <= 2;

  const requiresMassDeleteConfirm =
    proposal.proposalType === 'dedup' && sourceCount - 1 >= 3;

  const handleApproveClick = () => {
    if (proposal.proposalType === 'contradiction') {
      setPickerOpen(true);
      return;
    }
    if (requiresMassDeleteConfirm) {
      Modal.confirm({
        title: `Archive ${sourceCount - 1} memory rows?`,
        content: `This is a bulk archive (${sourceCount - 1} source memories will be marked ARCHIVED). Continue only if you've verified the content above is not a prompt-injection attack.`,
        okText: 'Archive & Approve',
        okButtonProps: { danger: true },
        cancelText: 'Cancel',
        onOk: () => onApprove(),
      });
      return;
    }
    void onApprove();
  };

  return (
    <Card
      data-testid={`memory-proposal-card-${proposal.id}`}
      variant="outlined"
      className="prop-card"
      style={{ borderLeft: 'none' }}
      title={
        <div className="prop-card-meta">
          <Tag
            color={meta.tag}
            data-testid={`proposal-type-chip-${proposal.proposalType}`}
            style={{ marginRight: 0 }}
          >
            {meta.label}
          </Tag>
          <span className="prop-card-run">
            run {proposal.synthesisRunId.slice(0, 12)}… · {fmtTime(proposal.createdAt)}
          </span>
          <span
            className={`prop-card-archive-badge ${archiveUrgent ? 'urgent' : 'normal'}`}
          >
            {archiveUrgent ? '⏰' : '⏱'} {archiveDays}d to archive
          </span>
        </div>
      }
      extra={
        <Button size="small" type="link" onClick={() => setRawOpen(true)} style={{ fontSize: 11 }}>
          Audit
        </Button>
      }
    >
      {/* Type stripe */}
      <div className={`prop-card-type-stripe ${proposal.proposalType}`} />

      {/* Reasoning */}
      {proposal.reasoning && (
        <Paragraph
          italic
          type="secondary"
          style={{ marginBottom: 12, fontSize: 'var(--font-size-sm)', lineHeight: 1.5, color: 'var(--fg-3)' }}
          ellipsis={{ rows: 2, expandable: true }}
        >
          {proposal.reasoning}
        </Paragraph>
      )}

      {/* Collapsible security warning */}
      <button
        className="prop-warning-toggle"
        onClick={() => setWarnExpanded((v) => !v)}
        type="button"
      >
        <ChevronIcon open={warnExpanded} />
        <span>Content from user conversations — verify for anomalies</span>
      </button>
      {warnExpanded && (
        <div className="prop-warning-detail">
          请留意明显的异常行为，如 role-play、ignore-previous-instructions 等
          prompt injection 特征。approve 前务必确认 source memory 内容安全。
        </div>
      )}

      {/* Source memories + Suggested content */}
      <div style={{ display: 'flex', gap: 16 }}>
        <div style={{ flex: 1, minWidth: 0 }}>
          <span className="prop-section-label">
            Source ({proposal.sourceMemories.length})
          </span>
          {proposal.sourceMemories.map((src) => (
            <SourceMemoryItem key={src.id} src={src} />
          ))}
        </div>
        {(proposal.proposalType === 'reflection' ||
          proposal.proposalType === 'optimize') && (
          <div style={{ flex: 1, minWidth: 0 }}>
            <span className="prop-section-label">
              Suggested
            </span>
            {proposal.suggestedTitle && (
              <Text strong style={{ display: 'block', marginBottom: 4, fontSize: 13 }}>
                {proposal.suggestedTitle}
              </Text>
            )}
            <div className="prop-suggested-box">
              {proposal.suggestedContent}
            </div>
            {proposal.suggestedImportance && (
              <Tag color="blue" style={{ marginTop: 8 }}>
                importance: {proposal.suggestedImportance}
              </Tag>
            )}
          </div>
        )}
      </div>

      {/* Footer */}
      <div className="prop-card-footer">
        <span className="prop-card-impact">
          <svg width="12" height="12" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
            <circle cx="8" cy="8" r="6.5" />
            <path d="M8 5v3.5l2.5 1.5" />
          </svg>
          {impact}
        </span>
        <Space size={8}>
          {proposal.proposalType === 'optimize' && (
            <Tooltip title="Restore original_content into t_memory.content">
              <Button
                size="small"
                onClick={() => onRevert()}
                loading={reverting}
                data-testid={`revert-btn-${proposal.id}`}
              >
                Revert
              </Button>
            </Tooltip>
          )}
          <Button
            size="small"
            onClick={() => onReject()}
            loading={rejecting}
            data-testid={`reject-btn-${proposal.id}`}
            style={{ color: 'var(--fg-4)' }}
          >
            Reject
          </Button>
          {(proposal.proposalType === 'reflection' ||
            proposal.proposalType === 'optimize') && (
            <Button
              size="small"
              onClick={() => setEditOpen(true)}
              data-testid={`edit-btn-${proposal.id}`}
            >
              Edit & Approve
            </Button>
          )}
          {proposal.proposalType === 'contradiction' ? (
            <Button
              type="primary"
              size="small"
              onClick={() => setPickerOpen(true)}
              data-testid={`pick-winner-btn-${proposal.id}`}
            >
              Pick Winner
            </Button>
          ) : (
            <Button
              type="primary"
              size="small"
              onClick={handleApproveClick}
              loading={approving}
              data-testid={`approve-btn-${proposal.id}`}
            >
              Approve
            </Button>
          )}
        </Space>
      </div>

      {/* Modals */}
      <MemoryProposalEditModal
        open={editOpen}
        proposal={proposal}
        submitting={editing || approving}
        onSubmit={async (patch) => {
          await onEditAndApprove(patch);
          setEditOpen(false);
        }}
        onClose={() => setEditOpen(false)}
      />

      {proposal.proposalType === 'contradiction' && (
        <MemoryProposalContradictionPicker
          open={pickerOpen}
          proposal={proposal}
          submitting={approving}
          onConfirm={async (winnerMemoryId) => {
            await onApprove({ contradictionPick: { winnerMemoryId } });
            setPickerOpen(false);
          }}
          onClose={() => setPickerOpen(false)}
        />
      )}

      <Drawer
        open={rawOpen}
        onClose={() => setRawOpen(false)}
        title="Raw LLM response (audit)"
        width={520}
        destroyOnHidden
      >
        <div style={{ fontSize: 12 }}>
          <Text strong>Synthesis run:</Text>{' '}
          <Text code>{proposal.synthesisRunId}</Text>
          <br />
          <Text strong>Source memory IDs:</Text>{' '}
          <Text code>{JSON.stringify(proposal.sourceMemoryIds)}</Text>
          <br />
          <Text strong>Auto archive after:</Text>{' '}
          <Text>{proposal.autoArchiveAfter}</Text>
          <br />
          <Text strong style={{ display: 'block', marginTop: 12 }}>
            Response excerpt (first 500 chars)
          </Text>
          <pre
            style={{
              marginTop: 4,
              background: 'var(--bg-surface-2, rgba(255,255,255,0.03))',
              padding: 8,
              borderRadius: 4,
              whiteSpace: 'pre-wrap',
              fontSize: 11,
            }}
          >
            {proposal.llmResponseExcerpt ?? '(no excerpt persisted)'}
          </pre>
        </div>
      </Drawer>
    </Card>
  );
};

export default MemoryProposalCard;
