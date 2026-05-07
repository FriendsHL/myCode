import React, { useEffect, useRef } from 'react';
import type { CommandMetadata } from '../../types/command';

/**
 * P10 — Slash-command popup. Shows above the chat composer when the user
 * is typing a slash command and has not yet entered any whitespace
 * (INV-Q5 (a) — first character must be `/`).
 *
 * The popup is **presentational only**:
 *   - Filtering, keyboard navigation, and execution live in `ChatInput`.
 *   - This component renders the visible list, highlights `selectedIndex`,
 *     and calls `onSelect(idx)` on mouse click.
 *
 * INV-15: when the user has typed `/m`, the catalog filter passes BOTH
 * `/model` and `/models`; this component does not collapse them or alter
 * dispatch — it just renders both rows with their full names.
 */

interface CommandPopupProps {
  commands: ReadonlyArray<CommandMetadata>;
  selectedIndex: number;
  /** Called when the user clicks a row — parent triggers execute/select. */
  onSelect: (index: number) => void;
  /** Called when mouse hovers — parent typically updates `selectedIndex`. */
  onHover?: (index: number) => void;
}

const CommandPopup: React.FC<CommandPopupProps> = ({
  commands,
  selectedIndex,
  onSelect,
  onHover,
}) => {
  const listRef = useRef<HTMLDivElement>(null);

  // Auto-scroll the selected row into view as the user arrows through.
  useEffect(() => {
    const el = listRef.current?.querySelector<HTMLElement>(
      `[data-cmd-idx="${selectedIndex}"]`,
    );
    // `scrollIntoView` is not implemented in jsdom (it's optional in browsers
    // too). Guard so unit tests don't blow up.
    if (el && typeof el.scrollIntoView === 'function') {
      el.scrollIntoView({ block: 'nearest' });
    }
  }, [selectedIndex]);

  if (commands.length === 0) {
    return (
      <div
        className="cmd-popup cmd-popup--empty"
        role="listbox"
        aria-label="Slash command suggestions"
        data-testid="command-popup"
        style={popupStyle}
      >
        <div style={emptyStyle}>No matching commands</div>
      </div>
    );
  }

  return (
    <div
      ref={listRef}
      className="cmd-popup"
      role="listbox"
      aria-label="Slash command suggestions"
      data-testid="command-popup"
      style={popupStyle}
    >
      <div style={hintStyle}>
        <span className="kbd" style={kbdStyle}>↑↓</span> navigate{' '}
        <span className="kbd" style={kbdStyle}>↵</span> run{' '}
        <span className="kbd" style={kbdStyle}>⇥</span> complete{' '}
        <span className="kbd" style={kbdStyle}>esc</span> close
      </div>
      {commands.map((cmd, idx) => {
        const isSelected = idx === selectedIndex;
        return (
          <button
            key={cmd.name}
            type="button"
            role="option"
            aria-selected={isSelected}
            data-cmd-idx={idx}
            data-cmd-name={cmd.name}
            data-testid={`command-option-${cmd.name.slice(1)}`}
            className={`cmd-popup-row${isSelected ? ' cmd-popup-row--active' : ''}`}
            onMouseDown={(e) => {
              // mousedown so we win the race against the textarea blur.
              e.preventDefault();
              onSelect(idx);
            }}
            onMouseEnter={() => onHover?.(idx)}
            style={{
              ...rowStyle,
              background: isSelected ? 'var(--bg-elev-2, #2a2a30)' : 'transparent',
            }}
          >
            <span style={nameStyle}>{cmd.name}</span>
            <span style={descStyle}>{cmd.description}</span>
            <span style={usageStyle}>{cmd.usage}</span>
          </button>
        );
      })}
    </div>
  );
};

export default CommandPopup;

// --- styles (inline for portability — CSS classes still allow theming) ---

const popupStyle: React.CSSProperties = {
  position: 'absolute',
  bottom: 'calc(100% + 6px)',
  left: 0,
  right: 0,
  maxHeight: 320,
  overflowY: 'auto',
  zIndex: 50,
  background: 'var(--bg-elev-1, #1a1a1e)',
  border: '1px solid var(--border-subtle, #2a2a30)',
  borderRadius: 8,
  boxShadow:
    '0 4px 6px -1px rgba(0,0,0,0.30), 0 8px 24px -4px rgba(0,0,0,0.45)',
  padding: '4px 0',
  fontFamily:
    'ui-sans-serif, -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif',
};

const hintStyle: React.CSSProperties = {
  padding: '6px 12px 8px',
  fontSize: 11,
  color: 'var(--fg-3, #6e6e75)',
  borderBottom: '1px solid var(--border-subtle, #2a2a30)',
  display: 'flex',
  gap: 6,
  alignItems: 'center',
};

const kbdStyle: React.CSSProperties = {
  display: 'inline-block',
  padding: '0 5px',
  border: '1px solid var(--border-subtle, #2a2a30)',
  borderRadius: 3,
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
  fontSize: 10,
  background: 'var(--bg-elev-2, #2a2a30)',
};

const rowStyle: React.CSSProperties = {
  display: 'grid',
  gridTemplateColumns: '110px 1fr auto',
  gap: 12,
  alignItems: 'baseline',
  padding: '8px 12px',
  width: '100%',
  textAlign: 'left',
  border: 0,
  cursor: 'pointer',
  color: 'var(--fg-1, #e7e7ea)',
  fontSize: 13,
};

const nameStyle: React.CSSProperties = {
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
  fontWeight: 600,
  color: 'var(--accent, #818cf8)',
};

const descStyle: React.CSSProperties = {
  color: 'var(--fg-2, #b8b8bf)',
  fontSize: 12,
  whiteSpace: 'nowrap',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
};

const usageStyle: React.CSSProperties = {
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
  color: 'var(--fg-3, #6e6e75)',
  fontSize: 11,
};

const emptyStyle: React.CSSProperties = {
  padding: '12px 16px',
  color: 'var(--fg-3, #6e6e75)',
  fontSize: 13,
  fontStyle: 'italic',
};
