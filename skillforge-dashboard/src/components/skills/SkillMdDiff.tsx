import React, { useMemo } from 'react';

interface SkillMdDiffProps {
  parent: string;
  candidate: string;
  /**
   * Side labels rendered above each pane. Defaults to 'Parent SKILL.md' and
   * 'Candidate (improved)'. Pass custom labels when the panel is reused
   * outside the Evolution Detail tab.
   */
  parentLabel?: string;
  candidateLabel?: string;
}

interface DiffLine {
  kind: 'eq' | 'add' | 'del';
  text: string;
}

/**
 * Compute a line-level LCS-based diff. Pure local implementation so we don't
 * pull in `react-diff-viewer-continued` (~80kb extra) just to render the
 * Evolution diff — V1 only needs left/right side-by-side with add/remove
 * highlighting, not inline word diffs. If we ever need Monaco-grade syntax
 * highlighting we can swap this out without touching the panel.
 */
function diffLines(a: string, b: string): { left: DiffLine[]; right: DiffLine[] } {
  const linesA = a.split('\n');
  const linesB = b.split('\n');
  const m = linesA.length;
  const n = linesB.length;

  // LCS table — capped at moderate file size; SKILL.md typically <500 lines
  // so this stays comfortably under O(n²) practical cost.
  const dp: number[][] = Array.from({ length: m + 1 }, () => new Array<number>(n + 1).fill(0));
  for (let i = m - 1; i >= 0; i--) {
    for (let j = n - 1; j >= 0; j--) {
      if (linesA[i] === linesB[j]) dp[i][j] = dp[i + 1][j + 1] + 1;
      else dp[i][j] = Math.max(dp[i + 1][j], dp[i][j + 1]);
    }
  }

  const left: DiffLine[] = [];
  const right: DiffLine[] = [];
  let i = 0;
  let j = 0;
  while (i < m && j < n) {
    if (linesA[i] === linesB[j]) {
      left.push({ kind: 'eq', text: linesA[i] });
      right.push({ kind: 'eq', text: linesB[j] });
      i++;
      j++;
    } else if (dp[i + 1][j] >= dp[i][j + 1]) {
      left.push({ kind: 'del', text: linesA[i] });
      right.push({ kind: 'eq', text: '' });
      i++;
    } else {
      left.push({ kind: 'eq', text: '' });
      right.push({ kind: 'add', text: linesB[j] });
      j++;
    }
  }
  while (i < m) {
    left.push({ kind: 'del', text: linesA[i++] });
    right.push({ kind: 'eq', text: '' });
  }
  while (j < n) {
    left.push({ kind: 'eq', text: '' });
    right.push({ kind: 'add', text: linesB[j++] });
  }
  return { left, right };
}

const ROW_BG: Record<DiffLine['kind'], string> = {
  eq: 'transparent',
  del: 'rgba(240,97,109,0.10)',
  add: 'rgba(54,179,126,0.10)',
};
const ROW_FG: Record<DiffLine['kind'], string> = {
  eq: 'var(--fg-2, #cccccc)',
  del: '#f0616d',
  add: '#36b37e',
};

export const SkillMdDiff: React.FC<SkillMdDiffProps> = ({
  parent, candidate, parentLabel = 'Parent SKILL.md', candidateLabel = 'Candidate (improved)',
}) => {
  const { left, right } = useMemo(() => diffLines(parent, candidate), [parent, candidate]);

  return (
    <div
      data-testid="skill-md-diff"
      style={{
        display: 'grid',
        gridTemplateColumns: '1fr 1fr',
        gap: 12,
        fontFamily: 'var(--font-mono, monospace)',
        fontSize: 11.5,
        lineHeight: 1.5,
      }}
    >
      <DiffPane label={parentLabel} lines={left} />
      <DiffPane label={candidateLabel} lines={right} />
    </div>
  );
};

interface DiffPaneProps {
  label: string;
  lines: DiffLine[];
}

const DiffPane: React.FC<DiffPaneProps> = ({ label, lines }) => (
  <div
    style={{
      border: '1px solid var(--border-subtle, #2a2a31)',
      borderRadius: 6,
      background: 'var(--bg-primary, #0f0f10)',
      overflow: 'hidden',
      display: 'flex',
      flexDirection: 'column',
      minHeight: 0,
    }}
  >
    <div
      style={{
        padding: '6px 10px',
        borderBottom: '1px solid var(--border-subtle, #2a2a31)',
        background: 'var(--bg-hover, #1d1d22)',
        fontSize: 11,
        color: 'var(--fg-3, #a8a8b1)',
        fontWeight: 600,
      }}
    >
      {label}
    </div>
    <div
      style={{
        overflow: 'auto',
        maxHeight: 480,
      }}
    >
      {lines.length === 0 ? (
        <div style={{ padding: 10, color: 'var(--fg-4, #8a8a93)' }}>(empty)</div>
      ) : (
        lines.map((l, idx) => (
          <div
            key={idx}
            style={{
              display: 'flex',
              padding: '0 10px',
              background: ROW_BG[l.kind],
              color: ROW_FG[l.kind],
              whiteSpace: 'pre-wrap',
              wordBreak: 'break-word',
              minHeight: 17,
            }}
          >
            <span
              aria-hidden
              style={{
                display: 'inline-block',
                width: 12,
                color: 'var(--fg-4, #8a8a93)',
                flexShrink: 0,
              }}
            >
              {l.kind === 'add' ? '+' : l.kind === 'del' ? '-' : ' '}
            </span>
            <span style={{ flex: 1 }}>{l.text || ' '}</span>
          </div>
        ))
      )}
    </div>
  </div>
);
