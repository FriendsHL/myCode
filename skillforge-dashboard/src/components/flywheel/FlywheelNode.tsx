import React from 'react';
import { Handle, Position, type NodeProps } from 'reactflow';
import StepCard from './StepCard';
import type { FlywheelMode, FlywheelRunDto, StepDescriptor, StepMetrics } from './types';

/**
 * Per-node payload threaded through `data` (React Flow contract — the
 * component receives a `NodeProps<FlywheelNodeData>` whose `data` field is
 * exactly this object). Keep it small + serializable; React Flow re-renders
 * a node only when this object identity changes.
 */
export interface FlywheelNodeData {
  step: StepDescriptor;
  metrics: StepMetrics;
  /** Whether the green pulse ring animates on this node (aggregate-mode PRD spec). */
  isRunning: boolean;
  /**
   * FLYWHEEL-PER-RUN — top-level mode. In `perRun` the node renders the
   * journey decoration instead of aggregate metrics + pulse.
   */
  mode: FlywheelMode;
  /**
   * FLYWHEEL-PER-RUN — this step matches the active run's currentStage (per
   * STAGE_TO_STEP). Drives the bright green "running for run" ring (or red
   * when the run errored on this step).
   */
  isCurrentForRun: boolean;
  /**
   * FLYWHEEL-PER-RUN — this step is in the pre-OptEvent context set
   * (entry/step1/2/3). Rendered in gray with a "context" tag so operators
   * understand it pre-dates the selected run.
   */
  isContextForRun: boolean;
  /**
   * FLYWHEEL-PER-RUN — this step's index in RUN_STAGE_ORDER is strictly
   * less than the active run's current step. Renders a checkmark + normal
   * border color (vs muted) to show "the run already passed through here".
   */
  isCompletedForRun: boolean;
  /**
   * FLYWHEEL-PER-RUN — true iff the active run's currentStage is an error
   * stage AND this step matches that step. Swaps the highlight ring to red.
   */
  isErrorForRun: boolean;
  /**
   * FLYWHEEL-PER-RUN — the active run, when one is selected. Used by
   * StepCard to swap the metric row in per-run mode.
   */
  activeRun: FlywheelRunDto | null;
  /**
   * Click handler — propagated to the inner StepCard so the parent
   * Flowchart can open its detail Drawer. Omitted for dormant nodes
   * (StepCard renders them as inert `<div>` regardless).
   */
  onSelect?: (step: StepDescriptor) => void;
}

/**
 * FLYWHEEL-FLOWCHART — custom React Flow node type registered as
 * `nodeTypes={{ flywheelStep: FlywheelNode }}` on the top-level <ReactFlow>.
 *
 * Responsibilities:
 *  - render the compact StepCard body
 *  - mount left (target) + right (source) Handles so React Flow can route
 *    edges to/from the node (both sides always present; unused side stays
 *    invisible when no edge connects)
 *  - apply the `fw-node--running` className when `data.isRunning` so the CSS
 *    keyframe pulse kicks in (Animation Spec in PRD — aggregate mode only)
 *  - in per-run mode, apply current/completed/context/error classNames per
 *    the run's journey position
 *
 * No internal state — the parent FlywheelFlowchart owns nodes + edges via
 * useMemo; this component is purely presentational.
 */
const FlywheelNode: React.FC<NodeProps<FlywheelNodeData>> = ({ data }) => {
  const {
    step,
    metrics,
    isRunning,
    mode,
    isCurrentForRun,
    isContextForRun,
    isCompletedForRun,
    isErrorForRun,
    activeRun,
    onSelect,
  } = data;
  // ENTRY nodes never have an incoming edge → hide the target handle visually
  // by class. Dormant terminal node (step9-decide) won't have an outgoing
  // edge but we still mount source handle (cheap; React Flow ignores it).
  const isEntry = step.nodeType === 'entry';

  // Build className: aggregate-mode pulse stays as-is; per-run mode classes
  // overlay journey-position decoration. Per-run + no-active-run muting is
  // handled at the .fw-flowchart-shell level to keep node-level CSS simple.
  const perRunClass = (() => {
    if (mode !== 'perRun') return '';
    if (isContextForRun) return ' fw-node--context';
    if (!activeRun) return ' fw-node--muted';
    if (isErrorForRun) return ' fw-node--error-for-run';
    if (isCurrentForRun) return ' fw-node--current-for-run';
    if (isCompletedForRun) return ' fw-node--completed-for-run';
    return ' fw-node--pending-for-run';
  })();

  const aggregateRunningClass =
    mode === 'aggregate' && isRunning ? ' fw-node--running' : '';

  return (
    <div
      className={`fw-node${aggregateRunningClass}${perRunClass}`}
      data-running={isRunning ? 'true' : 'false'}
      data-mode={mode}
      data-current-for-run={isCurrentForRun ? 'true' : 'false'}
      data-context-for-run={isContextForRun ? 'true' : 'false'}
      data-testid={`fw-node-${step.id}`}
    >
      {!isEntry && (
        <Handle
          type="target"
          position={Position.Left}
          className="fw-handle"
          isConnectable={false}
        />
      )}
      <StepCard
        step={step}
        metrics={metrics}
        onSelect={onSelect}
        mode={mode}
        isCurrentForRun={isCurrentForRun}
        isContextForRun={isContextForRun}
        isCompletedForRun={isCompletedForRun}
        isErrorForRun={isErrorForRun}
        activeRun={activeRun}
      />
      <Handle
        type="source"
        position={Position.Right}
        className="fw-handle"
        isConnectable={false}
      />
    </div>
  );
};

export default React.memo(FlywheelNode);
