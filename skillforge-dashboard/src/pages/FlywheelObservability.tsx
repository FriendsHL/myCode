import React from 'react';
import FlywheelFlowchart from '../components/flywheel/FlywheelFlowchart';

/**
 * FLYWHEEL-FLOWCHART — page wrapper for the Insights > Flywheel tab.
 *
 * Thin shell so the chart can be embedded both as an Insights tab (current
 * usage) and as a standalone route in the future. Replaces the original
 * card-style FlywheelObservabilityPanel (deleted in the same slice) with a
 * workflow DAG view per FLYWHEEL-FLOWCHART requirements.
 */
const FlywheelObservability: React.FC = () => {
  return <FlywheelFlowchart />;
};

export default FlywheelObservability;
