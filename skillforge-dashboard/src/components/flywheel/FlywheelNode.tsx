import React from 'react';
import { Handle, Position, type NodeProps } from 'reactflow';
import StepCard from './StepCard';
import type { StepDescriptor, StepMetrics } from './types';

/**
 * Per-node payload threaded through `data` (React Flow contract — the
 * component receives a `NodeProps<FlywheelNodeData>` whose `data` field is
 * exactly this object). Keep it small + serializable; React Flow re-renders
 * a node only when this object identity changes.
 */
export interface FlywheelNodeData {
  step: StepDescriptor;
  metrics: StepMetrics;
  /** Whether the green pulse ring animates on this node (PRD spec). */
  isRunning: boolean;
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
 *    keyframe pulse kicks in (Animation Spec in PRD)
 *
 * No internal state — the parent FlywheelFlowchart owns nodes + edges via
 * useMemo; this component is purely presentational.
 */
const FlywheelNode: React.FC<NodeProps<FlywheelNodeData>> = ({ data }) => {
  const { step, metrics, isRunning, onSelect } = data;
  // ENTRY nodes never have an incoming edge → hide the target handle visually
  // by class. Dormant terminal node (step9-decide) won't have an outgoing
  // edge but we still mount source handle (cheap; React Flow ignores it).
  const isEntry = step.nodeType === 'entry';

  return (
    <div
      className={`fw-node${isRunning ? ' fw-node--running' : ''}`}
      data-running={isRunning ? 'true' : 'false'}
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
      <StepCard step={step} metrics={metrics} onSelect={onSelect} />
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
