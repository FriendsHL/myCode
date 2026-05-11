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
  Descriptions,
  message as antMsg,
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
        <Tooltip title="View raw LLM response & synthesis metadata">
          <Button
            size="small"
            className="prop-audit-btn"
            onClick={() => setRawOpen(true)}
            icon={
              <svg width="13" height="13" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
                <circle cx="7" cy="7" r="4.5" />
                <path d="M10.5 10.5L14 14" />
              </svg>
            }
          >
            Audit
          </Button>
        </Tooltip>
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
        <div className="prop-card-actions">
          {proposal.proposalType === 'optimize' && (
            <Tooltip title="Restore original_content into t_memory.content">
              <Button
                size="small"
                className="prop-btn-revert"
                onClick={() => onRevert()}
                loading={reverting}
                data-testid={`revert-btn-${proposal.id}`}
                icon={
                  <svg width="12" height="12" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
                    <path d="M2 6h12M2 6l3-3M2 6l3 3" />
                  </svg>
                }
              >
                Revert
              </Button>
            </Tooltip>
          )}
          <Button
            size="small"
            danger
            className="prop-btn-reject"
            onClick={() => onReject()}
            loading={rejecting}
            data-testid={`reject-btn-${proposal.id}`}
            icon={
              <svg width="12" height="12" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
                <path d="M4 4l8 8M12 4l-8 8" />
              </svg>
            }
          >
            Reject
          </Button>
          {(proposal.proposalType === 'reflection' ||
            proposal.proposalType === 'optimize') && (
            <Button
              size="small"
              className="prop-btn-edit"
              onClick={() => setEditOpen(true)}
              data-testid={`edit-btn-${proposal.id}`}
              icon={
                <svg width="12" height="12" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
                  <path d="M11.5 2.5l2 2L5 13H3v-2L11.5 2.5z" />
                </svg>
              }
            >
              Edit & Approve
            </Button>
          )}
          {proposal.proposalType === 'contradiction' ? (
            <Button
              type="primary"
              size="small"
              className="prop-btn-approve"
              onClick={() => setPickerOpen(true)}
              data-testid={`pick-winner-btn-${proposal.id}`}
              icon={
                <svg width="12" height="12" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                  <polyline points="3 8 7 12 13 4" />
                </svg>
              }
            >
              Pick Winner
            </Button>
          ) : (
            <Button
              type="primary"
              size="small"
              className="prop-btn-approve"
              onClick={handleApproveClick}
              loading={approving}
              data-testid={`approve-btn-${proposal.id}`}
              icon={
                <svg width="12" height="12" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                  <polyline points="3 8 7 12 13 4" />
                </svg>
              }
            >
              Approve
            </Button>
          )}
        </div>
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

      {/* Audit Drawer */}
      <Drawer
        open={rawOpen}
        onClose={() => setRawOpen(false)}
        title={
          <span style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
              <circle cx="7" cy="7" r="4.5" />
              <path d="M10.5 10.5L14 14" />
            </svg>
            Synthesis Audit
          </span>
        }
        width={560}
        destroyOnHidden
        styles={{ body: { padding: '16px 24px' } }}
      >
        {/* Proposal metadata */}
        <Descriptions
          column={1}
          size="small"
          labelStyle={{ width: 140, color: 'var(--fg-3)', fontSize: 13, fontWeight: 500 }}
          contentStyle={{ fontSize: 13, color: 'var(--fg-1)' }}
          style={{ marginBottom: 20 }}
        >
          <Descriptions.Item label="Proposal ID">
            <Text code style={{ fontSize: 12 }}>{proposal.id}</Text>
          </Descriptions.Item>
          <Descriptions.Item label="Type">
            <Tag color={meta.tag} style={{ margin: 0 }}>{meta.label}</Tag>
          </Descriptions.Item>
          <Descriptions.Item label="Synthesis Run">
            <Text code style={{ fontSize: 12 }}>{proposal.synthesisRunId}</Text>
            <Tooltip title="Copy run ID">
              <Button
                type="text"
                size="small"
                style={{ marginLeft: 4, padding: '0 2px' }}
                onClick={() => {
                  navigator.clipboard.writeText(proposal.synthesisRunId);
                  antMsg.success('Copied');
                }}
                icon={
                  <svg width="12" height="12" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
                    <rect x="5" y="5" width="8" height="8" rx="1.5" />
                    <path d="M3 11V3.5A1.5 1.5 0 0 1 4.5 2H11" />
                  </svg>
                }
              />
            </Tooltip>
          </Descriptions.Item>
          <Descriptions.Item label="Source Memory IDs">
            <Space size={4} wrap>
              {proposal.sourceMemoryIds.map((id) => (
                <Tag key={id} style={{ margin: 0, fontSize: 12, fontFamily: 'var(--font-mono)' }}>#{id}</Tag>
              ))}
            </Space>
          </Descriptions.Item>
          <Descriptions.Item label="Created">
            {proposal.createdAt ? new Date(proposal.createdAt).toLocaleString() : '—'}
          </Descriptions.Item>
          <Descriptions.Item label="Auto-archive after">
            <span style={{ color: archiveUrgent ? 'var(--color-warn, #d49a3a)' : undefined }}>
              {proposal.autoArchiveAfter ? new Date(proposal.autoArchiveAfter).toLocaleDateString() : '—'}
              {archiveDays != null && (
                <Text type="secondary" style={{ marginLeft: 6, fontSize: 12 }}>
                  ({archiveDays}d remaining)
                </Text>
              )}
            </span>
          </Descriptions.Item>
          {proposal.reasoning && (
            <Descriptions.Item label="LLM Reasoning">
              <span style={{ color: 'var(--fg-2)', lineHeight: 1.6 }}>{proposal.reasoning}</span>
            </Descriptions.Item>
          )}
        </Descriptions>

        {/* LLM response excerpt */}
        <div style={{ marginBottom: 8 }}>
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 6 }}>
            <Text strong style={{ fontSize: 13, color: 'var(--fg-2)' }}>LLM Response Excerpt</Text>
            <Text type="secondary" style={{ fontSize: 11 }}>first 500 chars</Text>
          </div>
          <pre className="prop-audit-pre">
            {proposal.llmResponseExcerpt ?? '(no excerpt persisted)'}
          </pre>
        </div>
      </Drawer>
    </Card>
  );
};

export default MemoryProposalCard;
