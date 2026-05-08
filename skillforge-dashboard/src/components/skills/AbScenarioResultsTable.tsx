import React, { useMemo } from 'react';
import { Tag } from 'antd';

/**
 * Per-scenario result row as serialized into `t_skill_ab_run.ab_scenario_results_json`.
 * The BE (SkillAbTestService.completeRun) writes one entry per scenario in the
 * baseline eval set, with both the baseline and candidate execution outcomes.
 *
 * Field shape mirrors `AbScenarioResult` in src/api/index.ts (used by the
 * agent-prompt improve flow), but skill A/B runs may use a slimmer projection.
 * We accept either shape via `unknown` parsing + defensive narrowing so a BE
 * schema drift doesn't break the panel — fields default to '—'.
 */
interface ParsedScenario {
  scenarioId: string;
  scenarioName?: string;
  baselineScore?: number;
  candidateScore?: number;
  baselineStatus?: string;
  candidateStatus?: string;
}

interface AbScenarioResultsTableProps {
  /** Raw JSON string from `SkillAbRun.abScenarioResultsJson`. */
  rawJson?: string;
}

function parseScenarios(raw?: string): ParsedScenario[] {
  if (!raw) return [];
  try {
    const arr = JSON.parse(raw);
    if (!Array.isArray(arr)) return [];
    return arr.map((entry: unknown): ParsedScenario | null => {
      if (entry == null || typeof entry !== 'object') return null;
      const o = entry as Record<string, unknown>;
      // Try a few key spellings since the BE column is loosely typed.
      const scenarioId =
        typeof o.scenarioId === 'string' ? o.scenarioId
        : typeof o.id === 'string' ? o.id
        : null;
      if (!scenarioId) return null;
      const baselineScore = pickScore(o.baselineScore ?? o.baseline);
      const candidateScore = pickScore(o.candidateScore ?? o.candidate);
      return {
        scenarioId,
        scenarioName: typeof o.scenarioName === 'string' ? o.scenarioName
          : typeof o.name === 'string' ? o.name : undefined,
        baselineScore,
        candidateScore,
        baselineStatus: pickStatus(o.baseline) ?? (typeof o.baselineStatus === 'string' ? o.baselineStatus : undefined),
        candidateStatus: pickStatus(o.candidate) ?? (typeof o.candidateStatus === 'string' ? o.candidateStatus : undefined),
      };
    }).filter((x): x is ParsedScenario => x !== null);
  } catch {
    return [];
  }
}

function pickScore(v: unknown): number | undefined {
  if (typeof v === 'number') return v;
  if (v != null && typeof v === 'object') {
    const o = v as Record<string, unknown>;
    if (typeof o.oracleScore === 'number') return o.oracleScore;
    if (typeof o.score === 'number') return o.score;
  }
  return undefined;
}

function pickStatus(v: unknown): string | undefined {
  if (v != null && typeof v === 'object') {
    const o = v as Record<string, unknown>;
    if (typeof o.status === 'string') return o.status;
  }
  return undefined;
}

function formatScore(n: number | undefined): string {
  if (n == null || !Number.isFinite(n)) return '—';
  // Scores in this project are 0..100 (composite oracle score).
  return n.toFixed(1);
}

function statusColor(s?: string): string {
  if (!s) return 'default';
  const u = s.toUpperCase();
  if (u === 'PASS') return 'success';
  if (u === 'FAIL') return 'error';
  if (u === 'TIMEOUT') return 'warning';
  return 'default';
}

export const AbScenarioResultsTable: React.FC<AbScenarioResultsTableProps> = ({ rawJson }) => {
  const scenarios = useMemo(() => parseScenarios(rawJson), [rawJson]);

  if (scenarios.length === 0) {
    return (
      <div
        className="sf-empty-state"
        data-testid="ab-scenarios-empty"
        style={{ padding: '12px 8px', fontSize: 12 }}
      >
        No per-scenario results yet — the A/B run has not produced detailed scores.
      </div>
    );
  }

  return (
    <div
      data-testid="ab-scenarios-table"
      style={{
        border: '1px solid var(--border-subtle, #2a2a31)',
        borderRadius: 6,
        overflow: 'auto',
      }}
    >
      <table
        style={{
          width: '100%',
          borderCollapse: 'collapse',
          fontSize: 11.5,
          fontFamily: 'var(--font-mono, monospace)',
        }}
      >
        <thead>
          <tr style={{ background: 'var(--bg-hover, #1d1d22)' }}>
            <Th>Scenario</Th>
            <Th>Baseline</Th>
            <Th>Candidate</Th>
            <Th>Δ</Th>
          </tr>
        </thead>
        <tbody>
          {scenarios.map((s) => {
            const delta =
              s.baselineScore != null && s.candidateScore != null
                ? s.candidateScore - s.baselineScore
                : undefined;
            const deltaColor =
              delta == null ? 'var(--fg-4, #8a8a93)'
              : delta > 0 ? '#36b37e'
              : delta < 0 ? '#f0616d'
              : 'var(--fg-3, #a8a8b1)';
            return (
              <tr
                key={s.scenarioId}
                style={{ borderTop: '1px solid var(--border-subtle, #2a2a31)' }}
              >
                <Td>
                  <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
                    <span style={{ color: 'var(--fg-2, #cccccc)' }}>{s.scenarioName ?? s.scenarioId}</span>
                    {s.scenarioName && (
                      <span style={{ fontSize: 10, color: 'var(--fg-4, #8a8a93)' }}>{s.scenarioId}</span>
                    )}
                  </div>
                </Td>
                <Td>
                  <span style={{ display: 'inline-flex', gap: 6, alignItems: 'center' }}>
                    {s.baselineStatus && (
                      <Tag color={statusColor(s.baselineStatus)} style={{ marginInlineEnd: 0, fontSize: 10 }}>
                        {s.baselineStatus.toLowerCase()}
                      </Tag>
                    )}
                    <span>{formatScore(s.baselineScore)}</span>
                  </span>
                </Td>
                <Td>
                  <span style={{ display: 'inline-flex', gap: 6, alignItems: 'center' }}>
                    {s.candidateStatus && (
                      <Tag color={statusColor(s.candidateStatus)} style={{ marginInlineEnd: 0, fontSize: 10 }}>
                        {s.candidateStatus.toLowerCase()}
                      </Tag>
                    )}
                    <span>{formatScore(s.candidateScore)}</span>
                  </span>
                </Td>
                <Td style={{ color: deltaColor, fontWeight: 600 }}>
                  {delta == null ? '—' : `${delta > 0 ? '+' : ''}${delta.toFixed(1)}`}
                </Td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
};

const Th: React.FC<{ children: React.ReactNode }> = ({ children }) => (
  <th
    style={{
      padding: '8px 10px',
      textAlign: 'left',
      fontFamily: 'var(--font-mono, monospace)',
      fontSize: 10,
      fontWeight: 600,
      color: 'var(--fg-4, #8a8a93)',
      textTransform: 'uppercase',
      letterSpacing: 0.08,
    }}
  >
    {children}
  </th>
);

const Td: React.FC<{ children: React.ReactNode; style?: React.CSSProperties }> = ({ children, style }) => (
  <td style={{ padding: '8px 10px', color: 'var(--fg-2, #cccccc)', ...style }}>{children}</td>
);
