import React, { useState } from 'react';
import { Input, Modal, Radio, Tag, Typography, message } from 'antd';
import type { DraftNameConflictError, SkillDraft } from '../../api';

const { Paragraph, Text } = Typography;

/**
 * SKILL-DASHBOARD-POLISH-V2 §H — try to read the BE 409 conflict body. The
 * server returns `{ error: 'name_conflict', existingSkillId, name, ... }` on
 * approve when a draft's name exact-matches an existing skill (case-insensitive).
 *
 * Returns null when the error is something else (network, 5xx, validation,
 * the legacy ≥0.85-similarity 4xx without `error: 'name_conflict'` payload),
 * so callers fall through to message.error.
 */
export function extractNameConflict(err: unknown): DraftNameConflictError | null {
  if (!err || typeof err !== 'object') return null;
  const resp = (err as { response?: { status?: number; data?: unknown } }).response;
  if (!resp || resp.status !== 409) return null;
  const data = resp.data;
  if (!data || typeof data !== 'object') return null;
  const d = data as Record<string, unknown>;
  if (d.error !== 'name_conflict') return null;
  const existingSkillId = typeof d.existingSkillId === 'number' ? d.existingSkillId : null;
  const name = typeof d.name === 'string' ? d.name : null;
  if (existingSkillId == null || name == null) return null;
  return {
    error: 'name_conflict',
    existingSkillId,
    name,
    existingSkillEnabled:
      typeof d.existingSkillEnabled === 'boolean' ? d.existingSkillEnabled : undefined,
  };
}

type MergeChoice = 'update' | 'rename' | 'reject';

interface MergeConflictBodyProps {
  draft: SkillDraft;
  conflict: DraftNameConflictError;
  initialChoice: MergeChoice;
  initialNewName: string;
  onChange: (state: { choice: MergeChoice; newName: string }) => void;
}

/**
 * Inner content component — has its own local state so radio + input
 * re-renders don't re-mount the modal. Notifies the parent (which holds
 * the imperative Modal.confirm onOk closure) via `onChange`.
 */
const MergeConflictBody: React.FC<MergeConflictBodyProps> = ({
  draft, conflict, initialChoice, initialNewName, onChange,
}) => {
  const [choice, setChoice] = useState<MergeChoice>(initialChoice);
  const [newName, setNewName] = useState(initialNewName);

  // Self-check #2 — distinguish active vs disabled existing skill in copy.
  const existingStatus = conflict.existingSkillEnabled === false
    ? <Tag color="default" style={{ marginInlineEnd: 0 }}>disabled</Tag>
    : <Tag color="green" style={{ marginInlineEnd: 0 }}>active</Tag>;

  return (
    <div style={{ paddingTop: 4 }}>
      <Paragraph style={{ marginBottom: 12 }}>
        A skill named <Text strong code>{conflict.name}</Text> already exists{' '}
        (<Text code>#{conflict.existingSkillId}</Text> {existingStatus}).{' '}
        Choose how to resolve the conflict:
      </Paragraph>

      <Radio.Group
        value={choice}
        onChange={(e) => {
          const next = e.target.value as MergeChoice;
          setChoice(next);
          onChange({ choice: next, newName });
        }}
        style={{ display: 'flex', flexDirection: 'column', gap: 8 }}
      >
        <Radio value="update">
          <span>
            <strong>Update existing skill</strong>{' '}
            <span style={{ color: 'var(--text-muted, #8a8a93)' }}>
              — overwrite SKILL.md / description / triggers on{' '}
              <Text code>#{conflict.existingSkillId}</Text>
              {conflict.existingSkillEnabled === false
                ? ' (currently disabled — will not auto-enable)'
                : ''}
            </span>
          </span>
        </Radio>
        <Radio value="rename">
          <span>
            <strong>Rename and create new</strong>{' '}
            <span style={{ color: 'var(--text-muted, #8a8a93)' }}>
              — keep both skills (assign a new name)
            </span>
          </span>
        </Radio>
        <Radio value="reject">
          <span>
            <strong>Reject this draft</strong>{' '}
            <span style={{ color: 'var(--text-muted, #8a8a93)' }}>
              — discard, keep existing skill as-is
            </span>
          </span>
        </Radio>
      </Radio.Group>

      {choice === 'rename' && (
        <div style={{ marginTop: 12 }}>
          <div style={{ fontSize: 12, color: 'var(--text-muted, #8a8a93)', marginBottom: 4 }}>
            New name (must differ from <Text code>{conflict.name}</Text>):
          </div>
          <Input
            data-testid="merge-modal-new-name"
            placeholder={`${draft.name}-v2`}
            value={newName}
            onChange={(e) => {
              setNewName(e.target.value);
              onChange({ choice: 'rename', newName: e.target.value });
            }}
            maxLength={120}
          />
        </div>
      )}
    </div>
  );
};

interface OpenNameConflictModalArgs {
  draft: SkillDraft;
  conflict: DraftNameConflictError;
  onMerge: (targetSkillId: number) => Promise<unknown>;
  onRename: (newName: string) => Promise<unknown>;
  onReject: () => Promise<unknown>;
}

/**
 * Open the imperative AntD merge-conflict modal. Resolves the user's choice
 * by calling the appropriate `onMerge` / `onRename` / `onReject` callback;
 * the caller is responsible for downstream invalidation + toast feedback
 * (kept here so tanstack mutations stay in the page-level component).
 *
 * Self-check #2: copy is differentiated when the existing skill is disabled
 * (via the inline `<Tag>` + a one-liner inside the "Update existing" radio).
 */
export function openNameConflictModal({
  draft, conflict, onMerge, onRename, onReject,
}: OpenNameConflictModalArgs): void {
  // Initial state captured by closure so onOk reads the latest values.
  const state = {
    choice: 'update' as MergeChoice,
    newName: `${draft.name}-v2`,
  };

  Modal.confirm({
    title: `Skill name conflict: "${conflict.name}"`,
    width: 520,
    icon: null,
    content: (
      <MergeConflictBody
        draft={draft}
        conflict={conflict}
        initialChoice={state.choice}
        initialNewName={state.newName}
        onChange={({ choice, newName }) => {
          state.choice = choice;
          state.newName = newName;
        }}
      />
    ),
    okText: 'Apply',
    cancelText: 'Cancel',
    onOk: async () => {
      try {
        if (state.choice === 'update') {
          await onMerge(conflict.existingSkillId);
        } else if (state.choice === 'rename') {
          const trimmed = state.newName.trim();
          if (!trimmed) {
            message.error('New name cannot be empty');
            // Reject the promise so AntD keeps the modal open.
            return Promise.reject(new Error('empty-name'));
          }
          if (trimmed.toLowerCase() === conflict.name.toLowerCase()) {
            message.error('New name must differ from the conflicting name');
            return Promise.reject(new Error('same-name'));
          }
          await onRename(trimmed);
        } else {
          await onReject();
        }
      } catch (err) {
        // Caller handles the toast; we just rethrow so AntD doesn't auto-close
        // the modal on a transient backend failure (lets the user retry).
        throw err;
      }
    },
  });
}
