import React, { useMemo, useState } from 'react';
import { Modal, Select, InputNumber, message } from 'antd';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import axios from 'axios';
import { getAgents } from '../../api/index';
import { listOptReports } from '../../api/optReport';
import { triggerEvolveRun } from '../../api/evolve';
import './evolve.css';

/**
 * AUTOEVOLVE-AGENT-FLYWHEEL Module C — Trigger an agent-driven evolve run.
 *
 * Lets the operator pick a target agent, optionally pin a completed opt-report
 * to drive the loop from (focused-loop path — skips the live opt-report
 * workflow), and set maxIter, then fires
 * `POST /api/evolve/agents/{agentId}/run`. On 202 it toasts the evolveRunId and
 * notifies the parent (which scrolls to the trajectory panel). 409 → an evolve
 * run is already in flight for the agent.
 *
 * Mirrors {@code TriggerWorkflowModal} (antd Modal + react-query + message).
 */
interface EvolveTriggerModalProps {
  open: boolean;
  onClose: () => void;
  /** Called with the new evolveRunId (and its agentId) after a successful trigger. */
  onTriggered?: (evolveRunId: string, agentId: number) => void;
}

interface AgentLite {
  id: number;
  name: string;
}

const DEFAULT_MAX_ITER = 10;
const MIN_MAX_ITER = 1;
const MAX_MAX_ITER = 50;

const EvolveTriggerModal: React.FC<EvolveTriggerModalProps> = ({
  open,
  onClose,
  onTriggered,
}) => {
  const queryClient = useQueryClient();
  const [agentId, setAgentId] = useState<number | null>(null);
  const [reportId, setReportId] = useState<string | null>(null);
  const [maxIter, setMaxIter] = useState<number>(DEFAULT_MAX_ITER);
  const [submitting, setSubmitting] = useState(false);

  // Agents for the target dropdown — only while open.
  const { data: agents, isLoading: agentsLoading } = useQuery({
    queryKey: ['agents', 'evolve-trigger'],
    queryFn: () => getAgents().then((r) => (r.data as AgentLite[]) ?? []),
    enabled: open,
    staleTime: 60_000,
  });

  // Completed opt-reports for the selected agent (optional anchor).
  const { data: reports, isLoading: reportsLoading } = useQuery({
    queryKey: ['opt-reports', 'evolve-trigger', agentId],
    queryFn: () =>
      listOptReports(agentId as number, 20).then((r) =>
        (r.data.items ?? []).filter((it) => it.status === 'completed'),
      ),
    enabled: open && agentId != null,
    staleTime: 30_000,
  });

  const reportOptions = useMemo(
    () =>
      (reports ?? []).map((rep) => ({
        label: `${rep.reportId.slice(0, 8)}… · ${new Date(rep.createdAt).toLocaleDateString()}`,
        value: rep.reportId,
      })),
    [reports],
  );

  const reset = () => {
    setAgentId(null);
    setReportId(null);
    setMaxIter(DEFAULT_MAX_ITER);
    setSubmitting(false);
  };

  const handleClose = () => {
    if (submitting) return;
    reset();
    onClose();
  };

  const handleSubmit = async () => {
    if (agentId == null) {
      message.warning('Select a target agent first');
      return;
    }
    setSubmitting(true);
    try {
      const res = await triggerEvolveRun(agentId, {
        reportId: reportId ?? undefined,
        maxIter,
      });
      message.success(`Evolve run started: ${res.data.evolveRunId.slice(0, 8)}…`);
      // Refresh the trajectory panel's run list for this agent.
      queryClient.invalidateQueries({ queryKey: ['evolve-runs', agentId] });
      onTriggered?.(res.data.evolveRunId, agentId);
      reset();
      onClose();
    } catch (err: unknown) {
      const status =
        axios.isAxiosError(err) && err.response ? err.response.status : null;
      if (status === 409) {
        message.warning('This agent already has an evolve run in flight');
      } else if (status === 404) {
        message.error('Agent not found');
      } else if (status === 400) {
        message.error('Invalid report id');
      } else {
        const msg =
          axios.isAxiosError(err) && err.message
            ? err.message
            : 'Failed to start evolve run';
        message.error(msg);
      }
      setSubmitting(false);
    }
  };

  return (
    <Modal
      title="Evolve an agent"
      open={open}
      onCancel={handleClose}
      onOk={handleSubmit}
      okText="Run"
      okButtonProps={{ loading: submitting, disabled: agentId == null }}
      cancelButtonProps={{ disabled: submitting }}
      destroyOnHidden
      data-testid="evolve-trigger-modal"
    >
      <div className="evolve-trigger-body">
        <label className="evolve-trigger-label" htmlFor="evolve-trigger-agent">
          Target agent
        </label>
        <Select
          id="evolve-trigger-agent"
          style={{ width: '100%' }}
          placeholder={agentsLoading ? 'Loading agents…' : 'Select an agent to evolve'}
          loading={agentsLoading}
          showSearch
          optionFilterProp="label"
          value={agentId ?? undefined}
          onChange={(v: number) => {
            setAgentId(v);
            setReportId(null); // reports are agent-scoped; clear on agent change
          }}
          options={(agents ?? []).map((a) => ({ label: `${a.name} (#${a.id})`, value: a.id }))}
          data-testid="evolve-trigger-agent"
        />

        <label className="evolve-trigger-label" htmlFor="evolve-trigger-report">
          Anchor report (optional)
        </label>
        <Select
          id="evolve-trigger-report"
          style={{ width: '100%' }}
          placeholder={
            agentId == null
              ? 'Pick an agent first'
              : reportsLoading
                ? 'Loading reports…'
                : reportOptions.length === 0
                  ? 'No completed reports — runs opt-report itself'
                  : 'Drive from an existing completed report'
          }
          loading={reportsLoading}
          allowClear
          disabled={agentId == null}
          value={reportId ?? undefined}
          onChange={(v: string | undefined) => setReportId(v ?? null)}
          options={reportOptions}
          data-testid="evolve-trigger-report"
        />
        <p className="evolve-trigger-hint">
          Leave empty to run the opt-report workflow first; pick one to iterate on
          its issues directly (faster).
        </p>

        <label className="evolve-trigger-label" htmlFor="evolve-trigger-maxiter">
          Max iterations
        </label>
        <InputNumber
          id="evolve-trigger-maxiter"
          style={{ width: '100%' }}
          min={MIN_MAX_ITER}
          max={MAX_MAX_ITER}
          value={maxIter}
          onChange={(v) => setMaxIter(typeof v === 'number' ? v : DEFAULT_MAX_ITER)}
          disabled={submitting}
          data-testid="evolve-trigger-maxiter"
        />
      </div>
    </Modal>
  );
};

export default EvolveTriggerModal;
