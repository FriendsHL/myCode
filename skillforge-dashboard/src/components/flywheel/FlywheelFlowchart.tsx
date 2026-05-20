import React, { useMemo } from 'react';
import ReactFlow, {
  Background,
  Controls,
  type Edge,
  type Node,
  type NodeTypes,
} from 'reactflow';
import 'reactflow/dist/style.css';
import dagre from 'dagre';
import FlywheelNode, { type FlywheelNodeData } from './FlywheelNode';
import ActivityFeed from './ActivityFeed';
import { useFlywheelObservability } from '../../hooks/useFlywheelObservability';
import { useLocalStorageString } from '../../hooks/useLocalStorageString';
import { useAuth } from '../../contexts/AuthContext';
import type {
  AgentTypeTab,
  FlywheelSurface,
  StepDescriptor,
  StepMetrics,
} from './types';
import { STEP_CATALOGUE } from './types';
import './flywheel.css';

const AGENT_TYPE_KEYS = ['user', 'system'] as const;
const SURFACE_KEYS = ['skill', 'prompt', 'behavior_rule'] as const;

/**
 * FLYWHEEL-FLOWCHART — full topology of the flywheel pipeline, listed as
 * `[source.id, target.id]` pairs.
 *
 * Edges are filtered at render time to only those whose **both** endpoints
 * are visible for the active (agentType × surface) pair (e.g. for
 * `behavior_rule` the chain truncates at G1 because step4…step9 surfaces
 * exclude behavior_rule per STEP_CATALOGUE — see types.ts).
 *
 * Topology source: prd "flywheel observability" v2 / FLYWHEEL-FLOWCHART
 * Implementation Notes.
 */
const EDGE_PAIRS: ReadonlyArray<readonly [string, string]> = [
  // Entry → first pipeline stage
  ['E1-user-chat', 'step1-annotate'],
  ['E3-extract-skill', 'step1-annotate'],
  ['E2-upload-skill', 'step4-candidate'],
  ['E4-write-prompt', 'step4-candidate'],
  // Pipeline mainline
  ['step1-annotate', 'step2-cluster'],
  ['step2-cluster', 'step3-attribute'],
  ['step3-attribute', 'G1-approve-event'],
  ['G1-approve-event', 'step4-candidate'],
  ['step4-candidate', 'G2-review-draft'],
  ['G2-review-draft', 'step5-abtest'],
  ['step5-abtest', 'step6-gate'],
  ['step6-gate', 'G3-promote-decision'],
  // Rollout (dormant)
  ['G3-promote-decision', 'step7-canary'],
  ['step7-canary', 'step8-metrics'],
  ['step8-metrics', 'step9-decide'],
];

// dagre node dimensions — match the CSS .fw-node max-width / target height so
// the layout solver places nodes without overlap. Adjusting these here also
// requires updating flywheel.css `.fw-node` width/min-height.
const NODE_WIDTH = 240;
const NODE_HEIGHT = 130;

const NODE_TYPES: NodeTypes = { flywheelStep: FlywheelNode };

interface DagreLayout {
  positions: Map<string, { x: number; y: number }>;
}

function computeLayout(
  visibleSteps: ReadonlyArray<StepDescriptor>,
  visibleEdges: ReadonlyArray<readonly [string, string]>,
): DagreLayout {
  const g = new dagre.graphlib.Graph();
  g.setDefaultEdgeLabel(() => ({}));
  g.setGraph({
    rankdir: 'LR',
    nodesep: 32,
    ranksep: 90,
    marginx: 24,
    marginy: 24,
  });
  for (const step of visibleSteps) {
    g.setNode(step.id, { width: NODE_WIDTH, height: NODE_HEIGHT });
  }
  for (const [src, dst] of visibleEdges) {
    g.setEdge(src, dst);
  }
  dagre.layout(g);
  const positions = new Map<string, { x: number; y: number }>();
  for (const step of visibleSteps) {
    const n = g.node(step.id);
    if (!n) continue;
    // dagre returns center coordinates; React Flow expects top-left.
    positions.set(step.id, {
      x: n.x - NODE_WIDTH / 2,
      y: n.y - NODE_HEIGHT / 2,
    });
  }
  return { positions };
}

function emptyMetrics(): StepMetrics {
  return {
    inFlight: 0,
    todayCount: 0,
    lastActivityAt: null,
    recentErrorCount: 0,
    loaded: false,
  };
}

/**
 * FLYWHEEL-FLOWCHART — top-level observability panel rendered as the 5th
 * Insights tab. Replaces FlywheelObservabilityPanel + FlywheelTimeline (both
 * deleted in this slice) with a workflow-style DAG view (n8n / Dagster /
 * Airflow visual idiom).
 *
 * Architecture:
 *   useFlywheelObservability  →  metricsByStep + runningByStep + events
 *                                     │
 *                                     ▼
 *                       useMemo(computeLayout via dagre)
 *                                     │
 *                                     ▼
 *                              <ReactFlow nodes/edges/>
 *                                     │
 *                                     ▼
 *                       FlywheelNode  (wraps StepCard + Handles)
 *
 * The hook handles 30s tick refresh; this component is fully reactive to
 * its memoised inputs and re-runs dagre only when agentType / surface /
 * metric shape changes (NOT on every monitor tick — `runningByStep`
 * identity stays stable when the boolean payload doesn't actually flip).
 */
const FlywheelFlowchart: React.FC = () => {
  const { userId } = useAuth();
  const [agentType, setAgentType] = useLocalStorageString<AgentTypeTab>(
    'flywheel.agent_type_tab',
    'user',
    AGENT_TYPE_KEYS,
  );
  const surfaceLsKey = `flywheel.surface_tab.${agentType}`;
  const [surface, setSurface] = useLocalStorageString<FlywheelSurface>(
    surfaceLsKey,
    'skill',
    SURFACE_KEYS,
  );

  const {
    metricsByStep,
    runningByStep,
    events,
    isLoading,
    isError,
    errorMsg,
  } = useFlywheelObservability({ agentType, surface, userId });

  const { nodes, edges } = useMemo(() => {
    const visible = STEP_CATALOGUE.filter((s) => {
      if (!s.surfaces.includes(surface)) return false;
      if (s.agentTypes && !s.agentTypes.includes(agentType)) return false;
      return true;
    });
    const visibleIds = new Set(visible.map((s) => s.id));
    const visibleEdges = EDGE_PAIRS.filter(
      ([src, dst]) => visibleIds.has(src) && visibleIds.has(dst),
    );

    const { positions } = computeLayout(visible, visibleEdges);

    const builtNodes: Node<FlywheelNodeData>[] = visible.map((step) => {
      const m = metricsByStep[step.id] ?? emptyMetrics();
      return {
        id: step.id,
        type: 'flywheelStep',
        position: positions.get(step.id) ?? { x: 0, y: 0 },
        data: {
          step,
          metrics: m,
          isRunning: runningByStep[step.id] ?? false,
        },
        // Read-only DAG — disable interactions React Flow defaults on.
        draggable: false,
        connectable: false,
        selectable: false,
      };
    });

    // Edge gets animated dashed flow when **both** endpoints have in-flight
    // > 0 (PRD: data is actively flowing between these stages). Note we
    // base on metricsByStep.inFlight rather than runningByStep so that
    // entry → pipeline edges (entry nodes never "run") also animate when
    // there's actual traffic.
    const inFlightIds = new Set(
      visible
        .filter((s) => (metricsByStep[s.id]?.inFlight ?? 0) > 0)
        .map((s) => s.id),
    );

    const builtEdges: Edge[] = visibleEdges.map(([src, dst]) => {
      const isLive = inFlightIds.has(src) && inFlightIds.has(dst);
      return {
        id: `${src}->${dst}`,
        source: src,
        target: dst,
        type: 'smoothstep',
        animated: isLive,
        className: isLive ? 'fw-edge-animated' : 'fw-edge-static',
        style: { strokeWidth: isLive ? 2 : 1.5 },
      };
    });

    return { nodes: builtNodes, edges: builtEdges };
  }, [agentType, surface, metricsByStep, runningByStep]);

  // PRD N3 — "this surface never lit up" hint on the tab label.
  const surfaceIsEmpty = useMemo<Record<FlywheelSurface, boolean>>(() => {
    const empty: Record<FlywheelSurface, boolean> = {
      skill: false,
      prompt: false,
      behavior_rule: false,
    };
    if (isLoading) return empty;
    const any = Object.values(metricsByStep).some(
      (m) => m.inFlight > 0 || m.todayCount > 0 || m.lastActivityAt != null,
    );
    empty[surface] = !any;
    return empty;
  }, [metricsByStep, isLoading, surface]);

  return (
    <div className="fw-page" data-testid="flywheel-flowchart-panel">
      <header className="fw-head">
        <h1 className="fw-head-title">Flywheel observability</h1>
        <p className="fw-head-sub">
          Workflow DAG of every flywheel stage across the
          annotate → cluster → attribute → A/B → gate → rollout cycle. Nodes
          pulse green when actively running; edges animate when data is
          flowing. Click any step to drill into the operate page.
        </p>
      </header>

      {/* Tier 1 — agent type (user / system). */}
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
            aria-controls="fw-flowchart-panel"
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
            aria-controls="fw-flowchart-panel"
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
        id="fw-flowchart-panel"
        role="tabpanel"
        aria-labelledby={`fw-tab-surface-${surface}`}
        className="fw-flowchart-shell"
        data-testid="flywheel-flowchart"
      >
        <ReactFlow
          nodes={nodes}
          edges={edges}
          nodeTypes={NODE_TYPES}
          fitView
          fitViewOptions={{ padding: 0.15, maxZoom: 1 }}
          minZoom={0.4}
          maxZoom={1.5}
          nodesDraggable={false}
          nodesConnectable={false}
          // F4 (code A11Y-1) — must be false: when true, React Flow
          // intercepts Enter/Space on its node wrapper for its own
          // selection logic, which prevents the inner StepCard <a>
          // (react-router-dom <Link>) from receiving the keypress. With
          // false, keyboard users Tab straight to the <a> element and
          // Enter/Space follows the drill-down link as expected.
          nodesFocusable={false}
          edgesFocusable={false}
          elementsSelectable={false}
          panOnDrag
          panOnScroll={false}
          zoomOnScroll
          zoomOnPinch
          proOptions={{ hideAttribution: false }}
        >
          <Background gap={20} size={1} />
          <Controls
            showInteractive={false}
            position="bottom-right"
          />
        </ReactFlow>
      </div>

      <ActivityFeed events={events} loading={isLoading} />
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

export default FlywheelFlowchart;
