import React, { useEffect, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { Modal, message } from 'antd';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  getSkillDrafts, reviewSkillDraft, mergeDraftIntoSkill,
  type SkillDraft,
} from '../api';
import { useAuth } from '../contexts/AuthContext';
import { SkillDraftsSection } from '../components/skills/SkillDraftPanel';
import { extractNameConflict, openNameConflictModal } from '../components/skills/draftApproveHelpers';
import '../components/agents/agents.css';
import '../components/skills/skills.css';

/** P1-C-8 dedup: above this similarity score the BE flags the candidate as a near-duplicate. */
const HIGH_SIMILARITY_THRESHOLD = 0.85;

/**
 * SKILL-DASHBOARD-POLISH §E — top-level Drafts page.
 *
 * Reuses `SkillDraftsSection` so the visual treatment matches what users see
 * inside the SkillList view; this page is a thin shell that hosts the panel
 * outside of any skill list filter / agent selector context. Side-effects
 * (approve confirms duplicates, WS pushes auto-refresh the list) mirror
 * SkillList's handlers so behaviour is identical.
 */
const SkillDraftsPage: React.FC = () => {
  const queryClient = useQueryClient();
  const { userId: currentUserId } = useAuth();
  const navigate = useNavigate();

  const { data: draftsData, isLoading } = useQuery({
    queryKey: ['skill-drafts', currentUserId],
    queryFn: () => getSkillDrafts(currentUserId).then(r => r.data),
    enabled: !!currentUserId,
  });
  const drafts: SkillDraft[] = useMemo(() => draftsData ?? [], [draftsData]);
  const pendingDrafts = useMemo(() => drafts.filter(d => d.status === 'draft'), [drafts]);

  /** Same shape as SkillList's helper — see SkillList.tsx for rationale. */
  const invalidateAfterDraftMutation = () => {
    queryClient.invalidateQueries({ queryKey: ['skill-drafts'] });
    queryClient.invalidateQueries({ queryKey: ['skills'] });
    queryClient.invalidateQueries({ queryKey: ['dashboard-skill-summary'] });
  };

  const approveDraftCore = async (id: string, opts?: { forceCreate?: boolean }) => {
    const draft = drafts.find((d) => d.id === id);
    try {
      await reviewSkillDraft(id, 'approve', currentUserId, opts);
      invalidateAfterDraftMutation();
      message.success('Skill approved');
    } catch (err: unknown) {
      const conflict = draft ? extractNameConflict(err) : null;
      if (conflict && draft) {
        openNameConflictModal({
          draft,
          conflict,
          onMerge: async (targetSkillId) => {
            await mergeDraftIntoSkill(id, targetSkillId, currentUserId);
            invalidateAfterDraftMutation();
            message.success(`Merged into skill #${targetSkillId}`);
          },
          onRename: async (newName) => {
            await reviewSkillDraft(id, 'approve', currentUserId, { newName });
            invalidateAfterDraftMutation();
            message.success(`Approved as "${newName}"`);
          },
          onReject: async () => {
            await reviewSkillDraft(id, 'discard', currentUserId);
            invalidateAfterDraftMutation();
            message.success('Draft rejected');
          },
        });
        return;
      }
      const e = err as { response?: { data?: { error?: string } } };
      message.error(e.response?.data?.error || 'Failed to approve draft');
    }
  };

  const approveMutation = useMutation({
    mutationFn: (vars: { id: string; forceCreate?: boolean }) =>
      approveDraftCore(vars.id, { forceCreate: vars.forceCreate }),
  });

  const discardMutation = useMutation({
    mutationFn: (id: string) => reviewSkillDraft(id, 'discard', currentUserId),
    onSuccess: () => {
      invalidateAfterDraftMutation();
      message.success('Draft discarded');
    },
    onError: () => message.error('Failed to discard draft'),
  });

  const handleApproveDraft = (id: string) => {
    const draft = drafts.find(d => d.id === id);
    const similarity = draft?.similarity ?? 0;
    if (draft && similarity >= HIGH_SIMILARITY_THRESHOLD) {
      Modal.confirm({
        title: 'Possible duplicate skill',
        content: (
          <div>
            <p>
              Skill <strong>{draft.name}</strong> looks similar to existing skill{' '}
              <strong>{draft.mergeCandidateName ?? draft.mergeCandidateId ?? 'unknown'}</strong>{' '}
              (similarity {Math.round(similarity * 100)}%).
            </p>
            <p>Create anyway?</p>
          </div>
        ),
        okText: 'Create anyway',
        cancelText: 'Cancel',
        okButtonProps: { danger: true },
        onOk: () => approveMutation.mutate({ id, forceCreate: true }),
      });
      return;
    }
    approveMutation.mutate({ id });
  };

  /**
   * WS subscription: keep the draft list fresh when the BE cron extracts
   * new candidates. Self-check #3: the badge in Layout.tsx invalidates on
   * the same query key, so the unread count refreshes alongside this page.
   */
  useEffect(() => {
    if (!currentUserId) return;
    const proto = window.location.protocol === 'https:' ? 'wss' : 'ws';
    const token = localStorage.getItem('sf_token') ?? '';
    const ws = new WebSocket(
      `${proto}://${window.location.host}/ws/users/${currentUserId}?token=${encodeURIComponent(token)}`,
    );
    ws.onmessage = (ev) => {
      try {
        const msg = JSON.parse(ev.data) as { type?: string };
        if (msg.type === 'skill_draft_extracted') {
          queryClient.invalidateQueries({ queryKey: ['skill-drafts'] });
        }
      } catch { /* ignore non-JSON */ }
    };
    return () => { try { ws.close(); } catch { /* ignore */ } };
  }, [currentUserId, queryClient]);

  return (
    <div className="agents-view">
      <section className="agents-main" style={{ flex: 1 }}>
        <header className="agents-head">
          <div>
            <h1 className="agents-head-title">Skill Drafts</h1>
            <p className="agents-head-sub">
              {pendingDrafts.length} pending · {drafts.length} total · review and approve
              candidates extracted from recent sessions
            </p>
          </div>
        </header>

        {isLoading ? (
          <div className="sf-empty-state" style={{ marginTop: 24 }}>Loading drafts…</div>
        ) : drafts.length === 0 ? (
          <div className="sf-empty-state" style={{ marginTop: 24 }}>
            No drafts yet. The Tuesday cron extracts new candidates from recent sessions
            automatically; you can also trigger extraction manually from the Skills page.
          </div>
        ) : (
          <SkillDraftsSection
            drafts={drafts}
            pendingCount={pendingDrafts.length}
            onClose={() => navigate('/skills')}
            onApprove={handleApproveDraft}
            onDiscard={(id) => discardMutation.mutate(id)}
            approvingId={
              approveMutation.isPending ? approveMutation.variables?.id ?? null : null
            }
            discardingId={discardMutation.isPending ? discardMutation.variables ?? null : null}
          />
        )}
      </section>
    </div>
  );
};

export default SkillDraftsPage;
