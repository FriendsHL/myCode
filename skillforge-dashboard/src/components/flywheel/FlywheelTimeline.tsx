import React from 'react';
import StepCard from './StepCard';
import type {
  AgentTypeTab,
  FlywheelSurface,
  StepDescriptor,
  StepMetrics,
} from './types';
import { STEP_CATALOGUE } from './types';

interface FlywheelTimelineProps {
  agentType: AgentTypeTab;
  surface: FlywheelSurface;
  /** Map step.id → metrics produced by useFlywheelObservability. */
  metricsByStep: Record<string, StepMetrics>;
}

/**
 * Vertical step swim-lane for ONE (agentType × surface) pair. Renders the
 * static catalogue filtered by surface + agentType scope, grouped by
 * 'entry' / 'pipeline' / 'rollout' so the operator can read the flywheel
 * top-down (input → processing → output).
 */
const FlywheelTimeline: React.FC<FlywheelTimelineProps> = ({
  agentType,
  surface,
  metricsByStep,
}) => {
  const visible = STEP_CATALOGUE.filter((s) => {
    if (!s.surfaces.includes(surface)) return false;
    if (s.agentTypes && !s.agentTypes.includes(agentType)) return false;
    return true;
  });

  const groups: { key: StepDescriptor['group']; label: string; steps: StepDescriptor[] }[] = [
    { key: 'entry',    label: 'Entry · what is coming in?',           steps: visible.filter((s) => s.group === 'entry') },
    { key: 'pipeline', label: 'Pipeline · auto + operator decisions', steps: visible.filter((s) => s.group === 'pipeline') },
    { key: 'rollout',  label: 'Rollout · canary & decisions',         steps: visible.filter((s) => s.group === 'rollout') },
  ];

  return (
    <div className="fw-timeline" data-testid={`timeline-${agentType}-${surface}`}>
      {groups.map(
        (g) =>
          g.steps.length > 0 && (
            <div key={g.key} className="fw-timeline-group">
              <div className="fw-timeline-group-h">{g.label}</div>
              {g.steps.map((step) => {
                const m = metricsByStep[step.id] ?? emptyMetrics();
                return <StepCard key={step.id} step={step} metrics={m} />;
              })}
            </div>
          ),
      )}
    </div>
  );
};

function emptyMetrics(): StepMetrics {
  return {
    inFlight: 0,
    todayCount: 0,
    lastActivityAt: null,
    recentErrorCount: 0,
    loaded: false,
  };
}

export default FlywheelTimeline;
