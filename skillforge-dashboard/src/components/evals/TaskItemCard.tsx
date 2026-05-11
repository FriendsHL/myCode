import { useState } from 'react';
import { Link } from 'react-router-dom';
import type { EvalTaskItem } from '../../api';
import type { EvalMetric } from './evalUtils';
import { getMetricValue, formatMetricValue, isDimensionNotMeasured, scoreColor, TRACE_ICON, ANALYZE_ICON, ANNOTATE_ICON } from './evalUtils';

interface TaskItemCardProps {
  item: EvalTaskItem;
  metric: EvalMetric;
  onAnalyze: () => void;
  onAnnotate: () => void;
}

export default function TaskItemCard({ item, metric, onAnalyze, onAnnotate }: TaskItemCardProps) {
  const [showRationale, setShowRationale] = useState(false);
  const [showOutput, setShowOutput] = useState(false);

  // EVAL-V2 M4_V2 — when the user-selected metric is a sub-dim (not
  // composite) AND that dim is `not_measured` for this row, the header
  // must NOT fall back to compositeScore — that would mislabel composite
  // as the selected metric (e.g. "84% · latency"). Render "not measured"
  // in the score position instead. Composite has no not_measured state
  // (it's the renormalized aggregate), so the check is sub-dim-only.
  // Generic across all 4 sub-dims, not hard-coded to latency, so future
  // dims (e.g. cost without threshold) get the same treatment for free.
  const subMetric = metric === 'composite' ? null : metric;
  const headerNotMeasured = subMetric != null && isDimensionNotMeasured(item, subMetric);

  const score = headerNotMeasured ? 0 : (getMetricValue(item, metric) ?? item.compositeScore ?? 0);
  const score01 = score / 100;
  // Tier (drives card border color). When not_measured, the score is
  // meaningless for tier — fall back to overall case status instead of
  // letting score=0 paint everything red.
  const tier = headerNotMeasured
    ? (item.status === 'PASS' ? 'pass' : 'fail')
    : (score >= 80 ? 'pass' : score >= 60 ? 'warn' : item.status === 'PASS' ? 'pass' : 'fail');

  const attribution = item.attribution ?? 'NONE';
  const isFailedAttr = attribution !== 'NONE';

  return (
    <div className={`scn-result-card s-${tier}`}>
      {/* Header: name + status + score */}
      <div className="scn-result-h">
        <div className="scn-result-h-l">
          <span className="scn-result-name">{item.scenarioId}</span>
          <span className={`sess-status s-${item.status === 'PASS' ? 'idle' : item.status === 'TIMEOUT' ? 'waiting' : 'error'}`}>
            {item.status}
          </span>
          {isFailedAttr && (
            <span className="scn-result-attr attr-fail">
              {attribution.toLowerCase().replace(/_/g, ' ')}
            </span>
          )}
        </div>
        <div
          className={`scn-result-score${headerNotMeasured ? ' na' : ''}`}
          style={{ color: headerNotMeasured ? 'var(--fg-4, #8a8a93)' : scoreColor(score01) }}
          title={headerNotMeasured ? `No ${metric} threshold/baseline configured for this scenario — dimension was not contributed to composite.` : undefined}
        >
          {headerNotMeasured ? 'not measured' : formatMetricValue(score)}
          <em>{metric === 'composite' ? '' : ` · ${metric}`}</em>
        </div>
      </div>

      {/* Metric chips row */}
      <div className="scn-result-chips">
        {item.scenarioSource && <span className="kv-chip-sf">{item.scenarioSource}</span>}
        <span className={`kv-chip-sf ${metric === 'composite' ? 'on' : ''}`}>composite · {formatMetricValue(item.compositeScore)}</span>
        <span className={`kv-chip-sf ${metric === 'quality' ? 'on' : ''}`}>quality · {formatMetricValue(item.qualityScore)}</span>
        <span className={`kv-chip-sf ${metric === 'efficiency' ? 'on' : ''}`}>efficiency · {formatMetricValue(item.efficiencyScore)}</span>
        {/* EVAL-V2 M4_V2 — when latency is not_measured (no threshold configured),
            render "not measured" + dimmed style instead of a misleading number.
            Other 3 sub-dims are unchanged (intentional scope). */}
        {(() => {
          const latencyNotMeasured = isDimensionNotMeasured(item, 'latency');
          const cls = `kv-chip-sf ${metric === 'latency' ? 'on' : ''} ${latencyNotMeasured ? 'dimmed' : ''}`.trim().replace(/\s+/g, ' ');
          return (
            <span
              className={cls}
              title={latencyNotMeasured ? 'No latency threshold configured for this scenario — score not contributed to composite.' : undefined}
            >
              latency · {latencyNotMeasured ? 'not measured' : formatMetricValue(item.latencyScore)}
            </span>
          );
        })()}
        <span className={`kv-chip-sf ${metric === 'cost' ? 'on' : ''}`}>cost · {formatMetricValue(item.costScore)}</span>
        {item.costUsd != null && <span className="kv-chip-sf">cost · ${item.costUsd.toFixed(4)}</span>}
        {item.loopCount != null && <span className="kv-chip-sf">loops · {item.loopCount}</span>}
        {item.toolCallCount != null && <span className="kv-chip-sf">tools · {item.toolCallCount}</span>}
        {item.latencyMs != null && <span className="kv-chip-sf">latency · {item.latencyMs}ms</span>}
      </div>

      {/* Collapsible sections */}
      {item.judgeRationale && (
        <>
          <button className="scn-result-disclosure" onClick={() => setShowRationale(v => !v)}>
            {showRationale ? '▾' : '▸'} judge rationale
          </button>
          {showRationale && <div className="scn-result-section">{item.judgeRationale}</div>}
        </>
      )}
      {item.agentFinalOutput && (
        <>
          <button className="scn-result-disclosure" onClick={() => setShowOutput(v => !v)}>
            {showOutput ? '▾' : '▸'} agent final output
          </button>
          {showOutput && <div className="scn-result-section mono">{item.agentFinalOutput}</div>}
        </>
      )}

      {/* Actions: trace jump is now primary, analyze/annotate are icon buttons */}
      <div className="scn-result-actions">
        {item.rootTraceId && (
          <Link className="sf-mini-btn trace-btn" to={`/traces?traceId=${encodeURIComponent(item.rootTraceId)}`} title="View trace">
            {TRACE_ICON} Trace
          </Link>
        )}
        <button className="sf-mini-btn icon-btn" onClick={onAnalyze} title="Analyze this case">
          {ANALYZE_ICON}
        </button>
        <button className="sf-mini-btn icon-btn" onClick={onAnnotate} title="Annotate / correct score">
          {ANNOTATE_ICON}
        </button>
      </div>
    </div>
  );
}
