import React from 'react';
import { Modal } from 'antd';
import MarkdownRenderer from '../MarkdownRenderer';

/**
 * P10 — Generic modal that renders a slash command's `markdownBody` result.
 * Used for `/models`, `/skill`, `/tool`, `/context`, and `/help` (i.e. the
 * read-only commands whose output is a markdown report — INV-14).
 *
 * The modal is purely presentational: state ownership lives in `ChatWindow`,
 * which decides when to open/close based on the `CommandResult.displayMode`.
 */

interface CommandResultModalProps {
  open: boolean;
  /** Title shown in the modal header — usually the command name like "/help". */
  title?: string;
  /** Markdown body to render. Empty string falls back to a placeholder. */
  markdownBody?: string;
  onClose: () => void;
}

const CommandResultModal: React.FC<CommandResultModalProps> = ({
  open,
  title,
  markdownBody,
  onClose,
}) => {
  return (
    <Modal
      open={open}
      onCancel={onClose}
      footer={null}
      title={title ?? 'Command result'}
      width={720}
      data-testid="command-result-modal"
    >
      {markdownBody && markdownBody.trim().length > 0 ? (
        <div data-testid="command-result-body">
          <MarkdownRenderer content={markdownBody} />
        </div>
      ) : (
        <div
          data-testid="command-result-empty"
          style={{ color: 'var(--fg-3, #6e6e75)', fontStyle: 'italic' }}
        >
          (empty result)
        </div>
      )}
    </Modal>
  );
};

export default CommandResultModal;
