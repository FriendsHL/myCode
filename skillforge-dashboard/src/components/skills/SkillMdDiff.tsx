import React, { useMemo, useRef } from 'react';

interface DiffLine {
  kind: 'add' | 'del' | 'normal';
  text: string;
}

interface SkillMdDiffProps {
  parent: string;
  candidate: string;
  parentLabel?: string;
  candidateLabel?: string;
}

// Simple line-by-line diff logic (preserving existing behavior)
function diffLines(oldStr: string, newStr: string): { left: DiffLine[]; right: DiffLine[] } {
  const oldLines = oldStr.split('\n');
  const newLines = newStr.split('\n');
  const maxLen = Math.max(oldLines.length, newLines.length);
  const left: DiffLine[] = [];
  const right: DiffLine[] = [];

  for (let i = 0; i < maxLen; i++) {
    const oldL = oldLines[i];
    const newL = newLines[i];
    if (oldL === newL) {
      left.push({ kind: 'normal', text: oldL ?? '' });
      right.push({ kind: 'normal', text: newL ?? '' });
    } else {
      if (oldL !== undefined) left.push({ kind: 'del', text: oldL });
      else left.push({ kind: 'normal', text: '' });
      
      if (newL !== undefined) right.push({ kind: 'add', text: newL });
      else right.push({ kind: 'normal', text: '' });
    }
  }
  return { left, right };
}

const ROW_BG: Record<DiffLine['kind'], string> = {
  normal: 'transparent',
  add: 'rgba(46, 160, 67, 0.15)',
  del: 'rgba(248, 81, 73, 0.15)',
};

const ROW_FG: Record<DiffLine['kind'], string> = {
  normal: 'var(--fg-2, #c0c0c5)',
  add: '#3fb950',
  del: '#f85149',
};

interface DiffPaneProps {
  label: string;
  lines: DiffLine[];
  scrollRef?: React.RefObject<HTMLDivElement | null>;
  onScroll?: () => void;
}

const DiffPane: React.FC<DiffPaneProps> = ({ label, lines, scrollRef, onScroll }) => (
  <div style={{ border: '1px solid var(--border-subtle, #2a2a31)', borderRadius: 6, background: 'var(--bg-primary, #0f0f10)', overflow: 'hidden', display: 'flex', flexDirection: 'column' }}>
    <div style={{ padding: '6px 10px', borderBottom: '1px solid var(--border-subtle, #2a2a31)', background: 'var(--bg-hover, #1d1d22)', fontSize: 11, color: 'var(--fg-3, #a8a8b1)', fontWeight: 600 }}>
      {label}
    </div>
    <div ref={scrollRef} onScroll={onScroll} style={{ overflow: 'auto', maxHeight: 'calc(100vh - 250px)' }}>
      <table style={{ width: '100%', borderCollapse: 'collapse', font: 'inherit' }}>
        <tbody>
          {lines.map((l, idx) => (
            <tr key={idx} style={{ background: ROW_BG[l.kind], color: ROW_FG[l.kind] }}>
              <td style={{ width: 40, textAlign: 'right', paddingRight: 8, color: 'var(--fg-4, #8a8a93)', userSelect: 'none', borderRight: '1px solid var(--border-subtle, #2a2a31)', fontSize: 10, verticalAlign: 'top', paddingTop: 2 }}>
                {idx + 1}
              </td>
              <td style={{ padding: '2px 10px', whiteSpace: 'pre-wrap', wordBreak: 'break-word', position: 'relative' }}>
                <span style={{ position: 'absolute', left: 4, opacity: 0.5 }}>{l.kind === 'add' ? '+' : l.kind === 'del' ? '-' : ' '}</span>
                <span style={{ paddingLeft: 12 }}>{l.text || ' '}</span>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  </div>
);

export const SkillMdDiff: React.FC<SkillMdDiffProps> = ({
  parent, candidate, parentLabel = 'Parent SKILL.md', candidateLabel = 'Candidate (improved)',
}) => {
  const { left, right } = useMemo(() => diffLines(parent, candidate), [parent, candidate]);
  
  const leftRef = useRef<HTMLDivElement>(null);
  const rightRef = useRef<HTMLDivElement>(null);
  const isSyncing = useRef(false);

  const handleScroll = (source: 'left' | 'right') => {
    if (isSyncing.current) return;
    isSyncing.current = true;
    const sourceEl = source === 'left' ? leftRef.current : rightRef.current;
    const targetEl = source === 'left' ? rightRef.current : leftRef.current;
    if (sourceEl && targetEl) targetEl.scrollTop = sourceEl.scrollTop;
    requestAnimationFrame(() => { isSyncing.current = false; });
  };

  return (
    <div data-testid="skill-md-diff" style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12, fontFamily: 'var(--font-mono, monospace)', fontSize: 12, lineHeight: 1.5 }}>
      <DiffPane label={parentLabel} lines={left} scrollRef={leftRef} onScroll={() => handleScroll('left')} />
      <DiffPane label={candidateLabel} lines={right} scrollRef={rightRef} onScroll={() => handleScroll('right')} />
    </div>
  );
};
