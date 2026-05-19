import React, { useMemo } from 'react';
import FlywheelTimeline from './FlywheelTimeline';
import ActivityFeed from './ActivityFeed';
import { useFlywheelObservability } from '../../hooks/useFlywheelObservability';
import { useLocalStorageString } from '../../hooks/useLocalStorageString';
import { useAuth } from '../../contexts/AuthContext';
import type { AgentTypeTab, FlywheelSurface } from './types';
import './flywheel.css';

const AGENT_TYPE_KEYS = ['user', 'system'] as const;
const SURFACE_KEYS = ['skill', 'prompt', 'behavior_rule'] as const;

/**
 * FLYWHEEL-VISUAL-STATUS Phase 2 — main observability panel embedded as
 * the 5th tab of Insights. Combines a 2-level tab (agentType × surface),
 * a vertical step timeline, and a 24h activity feed (PRD R1 + N4 + N7).
 *
 * The panel itself is read-only (PRD N5) — every interactive element is a
 * drill-down link to an existing operate page; there are no Approve /
 * Trigger / Run-Manually buttons.
 */
const FlywheelObservabilityPanel: React.FC = () => {
  const { userId } = useAuth();
  const [agentType, setAgentType] = useLocalStorageString<AgentTypeTab>(
    'flywheel.agent_type_tab',
    'user',
    AGENT_TYPE_KEYS,
  );
  // Per PRD N7: separate localStorage key per first-tab.
  const surfaceLsKey = `flywheel.surface_tab.${agentType}`;
  const [surface, setSurface] = useLocalStorageString<FlywheelSurface>(
    surfaceLsKey,
    'skill',
    SURFACE_KEYS,
  );

  const { metricsByStep, events, isLoading, isError, errorMsg } =
    useFlywheelObservability({ agentType, surface, userId });

  // PRD N3 — surface is "empty" if all steps have 0 + null history. We hint
  // this in the tab label so the operator immediately sees "this column
  // never lit up". Cheap derived check; no extra fetch.
  const surfaceIsEmpty = useMemo<Record<FlywheelSurface, boolean>>(() => {
    const empty: Record<FlywheelSurface, boolean> = {
      skill: false,
      prompt: false,
      behavior_rule: false,
    };
    // Only evaluate after first-load completes; otherwise everything looks
    // empty during the initial fetch.
    if (isLoading) return empty;
    // Surface-agnostic: we judge the current `surface` only — switching tabs
    // refetches, so this is true at the time of render for the active value.
    const any = Object.values(metricsByStep).some(
      (m) => m.inFlight > 0 || m.todayCount > 0 || m.lastActivityAt != null,
    );
    empty[surface] = !any;
    return empty;
  }, [metricsByStep, isLoading, surface]);

  return (
    <div className="fw-page" data-testid="flywheel-observability-panel">
      <header className="fw-head">
        <h1 className="fw-head-title">Flywheel observability</h1>
        <p className="fw-head-sub">
          Read-only timeline of every flywheel stage across the
          annotate → cluster → attribute → A/B → gate → rollout cycle. Click
          any step to drill into the operate page.
        </p>
      </header>

      {/* code-WARN-3 a11y — tab tiers expose WAI-ARIA tablist semantics
          so screen readers can announce position + selection state. Both
          tiers point `aria-controls` at the shared timeline panel id —
          they don't have separate panel containers (tier 1 swaps the
          data source, tier 2 swaps the surface filter; same DOM region). */}
      <div
        className="underline-tabs"
        data-testid="agent-type-tabs"
        role="tablist"
        aria-label="Agent type"
      >
        {AGENT_TYPE_KEYS.map((k) => (
          <button
            key={k}
            type="button"
            role="tab"
            id={`fw-tab-agent-${k}`}
            aria-selected={agentType === k}
            aria-controls="fw-timeline-panel"
            tabIndex={agentType === k ? 0 : -1}
            className={agentType === k ? 'on' : ''}
            onClick={() => setAgentType(k)}
          >
            {k === 'user' ? 'User agents' : 'System agents'}
          </button>
        ))}
      </div>

      {/* Tier 2 — surface (skill / prompt / behavior_rule). */}
      <div
        className="fw-surface-tabs"
        data-testid="surface-tabs"
        role="tablist"
        aria-label="Surface"
      >
        {SURFACE_KEYS.map((k) => (
          <button
            key={k}
            type="button"
            role="tab"
            id={`fw-tab-surface-${k}`}
            aria-selected={surface === k}
            aria-controls="fw-timeline-panel"
            tabIndex={surface === k ? 0 : -1}
            className={`fw-surface-tab${surface === k ? ' on' : ''}`}
            data-empty={surface === k ? surfaceIsEmpty[k] : undefined}
            onClick={() => setSurface(k)}
          >
            {surfaceLabel(k)}
          </button>
        ))}
      </div>

      {isError && errorMsg && (
        <div className="fw-error" role="alert">
          Failed to load some flywheel metrics: {errorMsg}
        </div>
      )}

      <div
        id="fw-timeline-panel"
        role="tabpanel"
        aria-labelledby={`fw-tab-surface-${surface}`}
      >
        <FlywheelTimeline
          agentType={agentType}
          surface={surface}
          metricsByStep={metricsByStep}
        />

        <ActivityFeed events={events} loading={isLoading} />
      </div>
    </div>
  );
};

function surfaceLabel(s: FlywheelSurface): string {
  switch (s) {
    case 'skill':         return 'skill';
    case 'prompt':        return 'prompt';
    case 'behavior_rule': return 'behavior_rule';
  }
}

export default FlywheelObservabilityPanel;
