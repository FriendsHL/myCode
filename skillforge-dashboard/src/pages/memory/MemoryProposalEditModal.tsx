import React, { useState } from 'react';
import { Modal, Form, Input, Select, message } from 'antd';
import type {
  EditProposalPatch,
  MemoryImportance,
  MemoryProposal,
} from '../../api/memoryProposalsApi';

interface MemoryProposalEditModalProps {
  open: boolean;
  proposal: MemoryProposal;
  submitting: boolean;
  /** Save patch + then approve. Single-button UX per PRD F6: "Edit & Approve". */
  onSubmit: (patch: EditProposalPatch) => Promise<void> | void;
  onClose: () => void;
}

const IMPORTANCE_OPTIONS: { label: string; value: MemoryImportance }[] = [
  { label: 'Low', value: 'low' },
  { label: 'Medium', value: 'medium' },
  { label: 'High', value: 'high' },
];

const MAX_TITLE = 256;
const MAX_CONTENT = 8000;

const MemoryProposalEditModal: React.FC<MemoryProposalEditModalProps> = ({
  open,
  proposal,
  submitting,
  onSubmit,
  onClose,
}) => {
  const [title, setTitle] = useState(proposal.suggestedTitle ?? '');
  const [content, setContent] = useState(proposal.suggestedContent ?? '');
  const [importance, setImportance] = useState<MemoryImportance>(
    proposal.suggestedImportance ?? 'medium',
  );

  // R2-1 (TS B-2): useState initializers already seed from `proposal`. The
  // Modal `destroyOnHidden` prop tears the component down on close, so the
  // next open mounts fresh with new initial values — no useEffect needed.
  // A useEffect here would trip `react-hooks/set-state-in-effect`.

  const handleOk = async () => {
    const trimmedContent = content.trim();
    if (!trimmedContent) {
      message.error('Suggested content cannot be empty');
      return;
    }
    if (trimmedContent.length > MAX_CONTENT) {
      message.error(`Suggested content exceeds ${MAX_CONTENT} chars`);
      return;
    }
    const patch: EditProposalPatch = {
      suggestedTitle: title.trim() || undefined,
      suggestedContent: trimmedContent,
      suggestedImportance: importance,
    };
    await onSubmit(patch);
  };

  return (
    <Modal
      open={open}
      title="Edit & Approve Proposal"
      onCancel={onClose}
      onOk={handleOk}
      confirmLoading={submitting}
      okText="Save & Approve"
      cancelText="Cancel"
      destroyOnHidden
      width={640}
    >
      <Form layout="vertical">
        <Form.Item label="Suggested title">
          <Input
            value={title}
            maxLength={MAX_TITLE}
            placeholder="Optional title (reflection/optimize only)"
            onChange={(e) => setTitle(e.target.value)}
          />
        </Form.Item>
        <Form.Item label="Suggested content" required>
          <Input.TextArea
            value={content}
            onChange={(e) => setContent(e.target.value)}
            autoSize={{ minRows: 6, maxRows: 14 }}
            placeholder="LLM-suggested content; you may edit before approving"
          />
        </Form.Item>
        <Form.Item label="Importance">
          <Select<MemoryImportance>
            value={importance}
            onChange={(v) => setImportance(v)}
            options={IMPORTANCE_OPTIONS}
          />
        </Form.Item>
      </Form>
    </Modal>
  );
};

export default MemoryProposalEditModal;
