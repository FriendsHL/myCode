/**
 * SKILL-CREATOR-PHASE-1.6 (FE F4) — operator-facing modal for manually
 * triggering a draft evaluation against a chosen target agent.
 *
 * <p>Ratify (2026-05-18) wired this as the **only** evaluation entry-point —
 * uploads / imports / extract-from-sessions no longer fire `dispatchEvaluation`
 * implicitly. The operator picks:
 * <ol>
 *   <li>**Target agent** — filtered to `agentType=user` (system / cron-owned
 *       agents are excluded per Ratify so eval baselines are reproducible).</li>
 *   <li>**Scenarios** — optional source-session multi-pick. Defaults to the
 *       draft's own `sourceSessionId` (`firstByCreatedAt` for
 *       extract-from-sessions drafts). Upload / import path leaves this blank
 *       so the BE controller falls back to its own default.</li>
 *   <li>**Threshold** — hardcoded at 5pp for Phase 1.6 (Ratify decision —
 *       slider exposed in Phase 1.7). Field is rendered disabled with an
 *       inline hint so the operator can see the floor.</li>
 * </ol>
 *
 * <p>Submit calls `POST /api/skill-drafts/{id}/evaluate` (BE Phase 1.2
 * controller). BE writes `status='evaluating'` synchronously then dispatches
 * the eval coordinator on AFTER_COMMIT; the `session_updated` WebSocket
 * broadcast flips the FE badge to "Evaluating…" without a refetch — see
 * `SkillDraftDetailDrawer.deriveStatusKind` Phase 1.3 (W5 fix).
 *
 * <p><strong>Footgun #6 (FE-BE contract)</strong>: when be-dev Phase 1.2
 * lands, a grep of `SkillDraftController.evaluate(@RequestBody …)` must
 * verify the field names match `TriggerEvaluationPayload` 1:1. Single-side
 * tests will NOT catch a silent reflection mismatch (BE deserialises null →
 * eval runs against unrelated agent / wrong threshold).
 */
import { useEffect, useMemo, useState } from 'react';
import { Form, Modal, Select, Slider, message } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { getAgents } from '../../api';
import type { SkillDraft } from '../../api';
import { AgentSchema, safeParseList } from '../../api/schemas';
import type { AgentDto } from '../../api/schemas';
import {
  PASS_RATE_DELTA_THRESHOLD,
  triggerEvaluation,
} from '../../api/skillDrafts';

interface TriggerEvaluationModalProps {
  /** Draft being evaluated. The modal reads `id` + `sourceSessionId` only;
   *  status gating happens at the caller (only shown when 'draft'/'rejected'). */
  draft: SkillDraft;
  /** Modal visibility — caller owns the boolean. */
  open: boolean;
  /** Close handler — invoked on cancel, on success, and on close-X. */
  onClose: () => void;
  /** Optional success callback — fired after the API resolves with 200 so
   *  the caller can re-fetch / optimistically flip the draft to 'evaluating'. */
  onSuccess?: (draftId: string) => void;
}

interface TriggerFormValues {
  targetAgentId: number | undefined;
  scenarios: string[];
  /** Held for forward-compat — disabled in Phase 1.6 UX. */
  threshold: number;
}

const DEFAULT_THRESHOLD_PP = Math.round(PASS_RATE_DELTA_THRESHOLD * 100);

function extractList(res: unknown): unknown[] {
  // Mirrors the helper used in pages/Chat.tsx — axios responses arrive as
  // `{ data: T[] }` in tests but some callers wrap further; defensively
  // unwrap one layer if present.
  const wrapped = res as { data?: unknown };
  const inner = wrapped?.data ?? res;
  return Array.isArray(inner) ? inner : [];
}

/**
 * Modal — controlled by `open` prop. Resets form state when re-opened so the
 * operator doesn't see stale picks from a previous draft.
 */
export function TriggerEvaluationModal({
  draft,
  open,
  onClose,
  onSuccess,
}: TriggerEvaluationModalProps) {
  const [form] = Form.useForm<TriggerFormValues>();
  const [submitting, setSubmitting] = useState(false);

  // Fetch user-typed agents only — system/cron-owned agents excluded by Ratify.
  const agentsQuery = useQuery({
    queryKey: ['agents', 'user', 'for-trigger-evaluation'],
    queryFn: () =>
      getAgents('user').then((res) => safeParseList(AgentSchema, extractList(res))),
    enabled: open,
    staleTime: 30_000,
  });

  const agentOptions = useMemo(() => {
    const agents = (agentsQuery.data ?? []) as AgentDto[];
    return agents.map((a) => ({
      value: a.id,
      label: a.name,
      // Description preview drives `optionLabelProp` rendering — see Select below.
      description: a.description ?? undefined,
    }));
  }, [agentsQuery.data]);

  // Scenarios picker — default to the draft's source session when present so
  // the operator can submit immediately. `firstByCreatedAt` parity sits on the
  // BE so the empty list also yields a valid run.
  const initialScenarios = useMemo(
    () => (draft.sourceSessionId ? [draft.sourceSessionId] : []),
    [draft.sourceSessionId],
  );

  // Reset form whenever the modal opens with a (possibly new) draft.
  useEffect(() => {
    if (open) {
      form.setFieldsValue({
        targetAgentId: draft.targetAgentId ?? undefined,
        scenarios: initialScenarios,
        threshold: DEFAULT_THRESHOLD_PP,
      });
    }
  }, [open, draft.id, draft.targetAgentId, initialScenarios, form]);

  const handleOk = async () => {
    try {
      const values = await form.validateFields();
      if (values.targetAgentId == null) return; // belt-and-suspenders; rules enforce
      setSubmitting(true);
      await triggerEvaluation(draft.id, {
        targetAgentId: values.targetAgentId,
        scenarios: values.scenarios?.length ? values.scenarios : undefined,
        // threshold intentionally omitted in Phase 1.6 — BE default 5pp wins.
      });
      message.success('Evaluation started — typically completes in 5–10 minutes.');
      onSuccess?.(draft.id);
      onClose();
    } catch (err: unknown) {
      // AntD validateFields rejects with an `errorFields` array — ignore those
      // silently (the form already surfaces inline errors). Real API failures
      // get an explicit toast.
      const isValidationReject =
        typeof err === 'object' && err !== null && 'errorFields' in (err as object);
      if (!isValidationReject) {
        message.error(
          err instanceof Error
            ? `Failed to trigger evaluation: ${err.message}`
            : 'Failed to trigger evaluation',
        );
      }
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal
      open={open}
      onCancel={onClose}
      onOk={handleOk}
      title="Trigger Evaluation"
      okText="Trigger"
      cancelText="Cancel"
      okButtonProps={{ loading: submitting }}
      destroyOnHidden
      data-testid="trigger-evaluation-modal"
    >
      <Form<TriggerFormValues>
        form={form}
        layout="vertical"
        initialValues={{
          scenarios: initialScenarios,
          threshold: DEFAULT_THRESHOLD_PP,
        }}
      >
        <Form.Item
          name="targetAgentId"
          label="Target agent"
          rules={[{ required: true, message: 'Pick a target agent' }]}
          tooltip="System agents are excluded — evaluation baselines must be reproducible."
        >
          {/* Wrapper div carries data-testid because AntD Select doesn't
              forward arbitrary HTML attrs to its outer DOM element. */}
          <Select
            placeholder="Select a user agent…"
            loading={agentsQuery.isLoading}
            options={agentOptions}
            showSearch
            optionFilterProp="label"
            // popupMatchSelectWidth=false keeps the dropdown legible in narrow
            // form column under jsdom. Inherited from AntD default elsewhere.
            notFoundContent={
              agentsQuery.isLoading
                ? 'Loading…'
                : agentOptions.length === 0
                  ? 'No user agents available'
                  : 'No match'
            }
            optionRender={(option) => {
              const desc = (option.data as { description?: string }).description;
              return (
                <div style={{ display: 'flex', flexDirection: 'column' }}>
                  <span style={{ fontWeight: 500 }}>{option.label}</span>
                  {desc && (
                    <span
                      style={{
                        fontSize: 11,
                        color: 'var(--fg-3, #8a8a93)',
                        whiteSpace: 'nowrap',
                        overflow: 'hidden',
                        textOverflow: 'ellipsis',
                      }}
                    >
                      {desc}
                    </span>
                  )}
                </div>
              );
            }}
            // Use AntD's className passthrough as a stable testid target.
            className="trigger-eval-agent-select"
          />
        </Form.Item>

        <Form.Item
          name="scenarios"
          label="Source sessions (scenarios)"
          tooltip="Leave empty to let the backend pick the draft's source session or earliest matching session (firstByCreatedAt)."
        >
          <Select
            mode="tags"
            placeholder={
              draft.sourceSessionId
                ? `Defaults to source session #${draft.sourceSessionId}`
                : 'Optional — BE picks earliest by createdAt'
            }
            tokenSeparators={[',', ' ']}
            maxTagCount="responsive"
            className="trigger-eval-scenarios-select"
          />
        </Form.Item>

        <Form.Item
          name="threshold"
          label={
            <span>
              Delta threshold{' '}
              <span style={{ fontSize: 11, color: 'var(--fg-3, #8a8a93)' }}>
                (hardcoded {DEFAULT_THRESHOLD_PP}pp · slider in Phase 1.7)
              </span>
            </span>
          }
        >
          <Slider
            disabled
            min={1}
            max={20}
            marks={{ 1: '1pp', 5: '5pp', 10: '10pp', 20: '20pp' }}
            className="trigger-eval-threshold-slider"
          />
        </Form.Item>
      </Form>
    </Modal>
  );
}

export default TriggerEvaluationModal;
