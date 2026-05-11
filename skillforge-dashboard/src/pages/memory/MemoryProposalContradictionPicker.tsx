import React, { useState } from 'react';
import { Modal, Radio, Card, Typography, Space, message } from 'antd';
import type {
  MemoryProposal,
  ProposalSourceMemoryPreview,
} from '../../api/memoryProposalsApi';

const { Text, Paragraph } = Typography;

interface MemoryProposalContradictionPickerProps {
  open: boolean;
  proposal: MemoryProposal;
  submitting: boolean;
  /**
   * F-N1 fix: single-step approve. Caller hands BE `winnerMemoryId` inside
   * an `approveMemoryProposal(id, { contradictionPick: { winnerMemoryId } })`
   * round-trip — no separate `contradiction-pick` PATCH followed by approve.
   * BE atomically PATCHes + promotes inside one transaction.
   */
  onConfirm: (winnerMemoryId: number) => Promise<void> | void;
  onClose: () => void;
}

const MemoryProposalContradictionPicker: React.FC<MemoryProposalContradictionPickerProps> = ({
  open,
  proposal,
  submitting,
  onConfirm,
  onClose,
}) => {
  const candidates: ProposalSourceMemoryPreview[] = proposal.sourceMemories;
  const [winnerId, setWinnerId] = useState<number | null>(
    proposal.winnerMemoryId ?? null,
  );

  const handleOk = async () => {
    if (winnerId == null) {
      message.error('Pick the memory you want to keep');
      return;
    }
    await onConfirm(winnerId);
  };

  return (
    <Modal
      open={open}
      title="Pick the fact to keep"
      onCancel={onClose}
      onOk={handleOk}
      confirmLoading={submitting}
      okText="Confirm & Approve"
      cancelText="Cancel"
      destroyOnHidden
      width={760}
    >
      <Paragraph type="secondary" style={{ marginBottom: 12 }}>
        LLM detected a factual contradiction. The memory you keep stays
        ACTIVE; the others will be archived with reason{' '}
        <Text code>llm_dedup_merge_with_&lt;winnerId&gt;_proposal_&lt;pid&gt;</Text>.
      </Paragraph>
      {proposal.reasoning && (
        <Paragraph italic style={{ marginBottom: 16 }}>
          LLM reasoning: {proposal.reasoning}
        </Paragraph>
      )}
      <Radio.Group
        value={winnerId}
        onChange={(e) => setWinnerId(Number(e.target.value))}
        style={{ width: '100%' }}
      >
        <Space direction="vertical" style={{ width: '100%' }} size={12}>
          {candidates.map((cand) => (
            <Radio key={cand.id} value={cand.id} style={{ width: '100%' }}>
              <Card
                size="small"
                variant="outlined"
                style={{
                  borderColor: winnerId === cand.id ? '#6366f1' : undefined,
                  marginLeft: 4,
                }}
                title={cand.title || `Memory #${cand.id}`}
                extra={
                  <Text type="secondary" style={{ fontSize: 12 }}>
                    {cand.status} · recall {cand.recallCount}
                  </Text>
                }
              >
                <Paragraph
                  style={{ marginBottom: 0, whiteSpace: 'pre-wrap', fontSize: 13 }}
                  ellipsis={{ rows: 4, expandable: true }}
                >
                  {cand.content}
                </Paragraph>
              </Card>
            </Radio>
          ))}
        </Space>
      </Radio.Group>
    </Modal>
  );
};

export default MemoryProposalContradictionPicker;
