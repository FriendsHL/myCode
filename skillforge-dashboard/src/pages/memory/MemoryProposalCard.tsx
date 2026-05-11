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
  Alert,
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
  dedup: { label: 'Dedup', color: '#2563eb', tag: 'blue' },
  reflection: { label: 'Reflection', color: '#9333ea', tag: 'purple' },
  optimize: { label: 'Optimize', color: '#ca8a04', tag: 'gold' },
  contradiction: { label: 'Contradiction', color: '#dc2626', tag: 'red' },
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

const SourceMemoryItem: React.FC<{ src: ProposalSourceMemoryPreview }> = ({ src }) => {
  const [expanded, setExpanded] = useState(false);
  return (
    <div
      style={{
        borderLeft: '2px solid var(--border-subtle, #2a2a30)',
        padding: '8px 12px',
        marginBottom: 8,
        background: 'var(--bg-surface-2, rgba(255,255,255,0.02))',
        borderRadius: 4,
      }}
    >
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          gap: 8,
          marginBottom: 4,
        }}
      >
        <Text strong style={{ fontSize: 13 }}>
          {src.title || `Memory #${src.id}`}
        </Text>
        <Space size={6}>
          <Tag style={{ margin: 0, fontSize: 10 }}>{src.status}</Tag>
          <Text type="secondary" style={{ fontSize: 11 }}>
            recall {src.recallCount}
          </Text>
        </Space>
      </div>
      <Paragraph
        style={{
          marginBottom: 0,
          whiteSpace: 'pre-wrap',
          fontSize: 12,
          color: 'var(--text-secondary, #aaa)',
        }}
        ellipsis={expanded ? false : { rows: 2, expandable: true, onExpand: () => setExpanded(true) }}
      >
        {src.content}
      </Paragraph>
    </div>
  );
};

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

  const meta = TYPE_META[proposal.proposalType];
  const impact = describeApproveImpact(proposal);
  const sourceCount = proposal.sourceMemoryIds.length;
  /**
   * B-3 fix: dedup proposals archive (N - 1) source memories. If that's
   * ≥ 3 we surface a hard confirmation modal so an admin can't mass-archive
   * by misclick. Contradiction has its own picker so it doesn't need this.
   */
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
      style={{
        marginBottom: 16,
        borderLeft: `3px solid ${meta.color}`,
      }}
      title={
        <Space>
          <Tag
            color={meta.tag}
            data-testid={`proposal-type-chip-${proposal.proposalType}`}
            style={{ marginRight: 0 }}
          >
            {meta.label}
          </Tag>
          <Text type="secondary" style={{ fontWeight: 400, fontSize: 12 }}>
            run {proposal.synthesisRunId.slice(0, 12)}… · created {fmtTime(proposal.createdAt)}
          </Text>
        </Space>
      }
      extra={
        <Button size="small" type="link" onClick={() => setRawOpen(true)}>
          View raw LLM response
        </Button>
      }
    >
      {proposal.reasoning && (
        <Paragraph
          italic
          type="secondary"
          style={{ marginBottom: 12 }}
          ellipsis={{ rows: 2, expandable: true }}
        >
          {proposal.reasoning}
        </Paragraph>
      )}
      <div style={{ display: 'flex', gap: 16 }}>
        <div style={{ flex: 1, minWidth: 0 }}>
          <Alert
            type="warning"
            showIcon
            style={{ marginBottom: 8, fontSize: 12 }}
            message="内容来自用户对话，请注意识别明显异常 (e.g. role-play, ignore-previous-instructions)。"
          />
          <Text strong style={{ fontSize: 12, display: 'block', marginBottom: 6 }}>
            Source memories ({proposal.sourceMemories.length})
          </Text>
          {proposal.sourceMemories.map((src) => (
            <SourceMemoryItem key={src.id} src={src} />
          ))}
        </div>
        {(proposal.proposalType === 'reflection' ||
          proposal.proposalType === 'optimize') && (
          <div style={{ flex: 1, minWidth: 0 }}>
            <Text strong style={{ fontSize: 12, display: 'block', marginBottom: 6 }}>
              Suggested content
            </Text>
            {proposal.suggestedTitle && (
              <Text style={{ display: 'block', marginBottom: 4 }}>
                {proposal.suggestedTitle}
              </Text>
            )}
            <Paragraph
              style={{
                marginBottom: 4,
                whiteSpace: 'pre-wrap',
                fontSize: 13,
                background: 'var(--bg-surface-2, rgba(99,102,241,0.06))',
                padding: 10,
                borderRadius: 4,
              }}
            >
              {proposal.suggestedContent}
            </Paragraph>
            {proposal.suggestedImportance && (
              <Tag color="blue">importance: {proposal.suggestedImportance}</Tag>
            )}
          </div>
        )}
      </div>

      <div
        style={{
          marginTop: 16,
          paddingTop: 12,
          borderTop: '1px solid var(--border-subtle, #2a2a30)',
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          gap: 8,
          flexWrap: 'wrap',
        }}
      >
        <Text type="secondary" style={{ fontSize: 12 }}>
          {impact}
        </Text>
        <Space>
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
            // F-N1 single-step: pass winnerMemoryId straight to approve.
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
