import React from 'react';
import FlywheelObservabilityPanel from '../components/flywheel/FlywheelObservabilityPanel';

/**
 * FLYWHEEL-VISUAL-STATUS Phase 2 — page wrapper for the Insights >
 * Flywheel tab. Thin shell so the panel can be embedded both as an
 * Insights tab (current usage) and as a standalone route in the future
 * (just point `App.tsx` here).
 */
const FlywheelObservability: React.FC = () => {
  return <FlywheelObservabilityPanel />;
};

export default FlywheelObservability;
