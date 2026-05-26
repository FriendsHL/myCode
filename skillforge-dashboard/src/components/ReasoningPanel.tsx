/**
 * CHAT-REASONING-PANEL — Reasoning / thinking panel rendered above an
 * assistant bubble.
 *
 * Three rendering modes, decided by props:
 *
 *  1. **Streaming** — `streamingText` non-empty AND (`forceStreaming` OR no
 *     completed `reasoningContent` yet). Shows a spinner + live reasoning
 *     text via `ThrottledMarkdown` (200ms render throttle, satisfies
 *     `.claude/rules/frontend.md` footgun #3). No expand/collapse toggle:
 *     content is always visible while the model is still thinking.
 *  2. **Completed** — `reasoningContent` non-empty (whitespace-trimmed).
 *     Header reads `Thought for N.Ns ▾` (or `Thought ▾` when `durationMs`
 *     is null — e.g. historical messages persisted before this feature
 *     captured wall-clock data). Header is click-to-toggle.
 *  3. **Null** — neither `streamingText` nor `reasoningContent` carries
 *     meaningful content → component returns `null` (renders nothing).
 *
 * Initial expanded state in completed mode follows `defaultExpanded`
 * (sourced from `agent.thinkingVisible`).
 *
 * Visual direction: SkillForge ember-orange accent (`--accent-primary`)
 * with left border-stripe + soft-tinted background, matching the existing
 * `.compact-notice` / `.msg-compaction-summary` chat-thread vocabulary so
 * the panel reads as part of the SkillForge design system rather than a
 * generic Ant Design panel. See `.claude/rules/design.md` Anti-Template
 * Policy + Required Design Qualities #1 (scale contrast), #3 (depth via
 * accent border / tinted surface), #5 (color used semantically — orange
 * accent reserved for "agent's internal voice"), #6 (designed
 * hover/focus/active states on the toggle button).
 */
import React, { useId, useState } from 'react';
import { ThrottledMarkdown } from './ThrottledMarkdown';
import MarkdownRenderer from './MarkdownRenderer';

export interface ReasoningPanelProps {
  /**
   * Live reasoning text accumulated from SSE `reasoning_delta`. Non-empty
   * → streaming mode is candidate. Pass an empty string for completed
   * historical messages.
   */
  streamingText?: string;
  /**
   * Final / persisted reasoning content (from `t_session_message.reasoning_content`
   * or the streaming accumulator handed off to completed mode). Empty /
   * undefined / whitespace-only → panel renders null in completed mode.
   */
  reasoningContent?: string | null;
  /**
   * Wall-clock duration of the reasoning phase in milliseconds. Null =
   * unknown (still in reasoning phase, OR historical message with no
   * timing data). Drives the `Thought for N.Ns ▾` vs `Thought ▾` label.
   */
  durationMs?: number | null;
  /**
   * Initial expand state for completed mode. Sourced from
   * `agent.thinkingVisible` upstream. Defaults to false (collapsed).
   */
  defaultExpanded?: boolean;
  /**
   * Force streaming-mode rendering even when `reasoningContent` is set.
   * Used by ChatWindow during the live-stream phase to keep the spinner
   * visible until the first `text_delta` lands (which flips
   * `reasoningDurationMs` → non-null, releasing this lock).
   */
  forceStreaming?: boolean;
}

function formatDuration(ms: number | null | undefined): string {
  if (ms == null || !Number.isFinite(ms) || ms < 0) return '';
  const seconds = ms / 1000;
  // 1 decimal under 60s, integer minutes:seconds above (rare; reasoning
  // rarely exceeds a minute, but defensive formatting keeps the label
  // compact if it does).
  if (seconds < 60) return `${seconds.toFixed(1)}s`;
  const m = Math.floor(seconds / 60);
  const s = Math.round(seconds % 60);
  return `${m}m${s.toString().padStart(2, '0')}s`;
}

const ReasoningPanel: React.FC<ReasoningPanelProps> = ({
  streamingText,
  reasoningContent,
  durationMs,
  defaultExpanded = false,
  forceStreaming = false,
}) => {
  const [isExpanded, setIsExpanded] = useState<boolean>(defaultExpanded);
  // useId() gives a stable, instance-unique DOM id so multiple
  // ReasoningPanels in the same thread don't collide on a hardcoded id
  // (breaks HTML uniqueness + makes `aria-controls` point at the wrong
  // panel — picked up by ts-r1 W1 / llm-compat W2).
  const panelBodyId = useId();

  const hasStreaming = !!streamingText && streamingText.length > 0;
  const completedText =
    typeof reasoningContent === 'string' && reasoningContent.trim().length > 0
      ? reasoningContent
      : null;

  // Mode selection
  const isStreaming = hasStreaming && (forceStreaming || !completedText);
  const isCompleted = !isStreaming && completedText !== null;

  // Null mode: nothing meaningful to show.
  if (!isStreaming && !isCompleted) return null;

  if (isStreaming) {
    return (
      <div
        className="reasoning-panel reasoning-panel--streaming"
        data-testid="reasoning-panel-streaming"
      >
        <div className="reasoning-panel__header reasoning-panel__header--static">
          <span
            className="reasoning-panel__spinner"
            aria-hidden="true"
          />
          <span className="reasoning-panel__label">Thinking…</span>
        </div>
        <div className="reasoning-panel__content reasoning-panel__content--streaming">
          {/* ThrottledMarkdown throttles internal DOM commits to 200ms
              (frontend.md footgun #3) — safe to feed every reasoning_delta
              accumulation into it. */}
          <ThrottledMarkdown content={streamingText ?? ''} />
        </div>
      </div>
    );
  }

  // Completed mode
  const durationLabel = formatDuration(durationMs);
  const headerLabel = durationLabel ? `Thought for ${durationLabel}` : 'Thought';
  const chevron = isExpanded ? '▴' : '▾';

  return (
    <div
      className="reasoning-panel reasoning-panel--completed"
      data-testid="reasoning-panel-completed"
    >
      <button
        type="button"
        className="reasoning-panel__header reasoning-panel__header--toggle"
        onClick={() => setIsExpanded((v) => !v)}
        aria-expanded={isExpanded}
        aria-controls={panelBodyId}
        data-testid="reasoning-panel-toggle"
      >
        <span className="reasoning-panel__chevron" aria-hidden="true">
          {chevron}
        </span>
        <span className="reasoning-panel__label">{headerLabel}</span>
      </button>
      <div
        id={panelBodyId}
        className={
          'reasoning-panel__content reasoning-panel__content--completed' +
          (isExpanded ? ' reasoning-panel__content--expanded' : '')
        }
        // aria-hidden mirrors the visual collapse so SR users get the same
        // affordance. Content is still in the DOM (display:none on the inner
        // wrapper is avoided so max-height transition works smoothly).
        aria-hidden={!isExpanded}
      >
        <div className="reasoning-panel__content-inner">
          <MarkdownRenderer content={completedText ?? ''} />
        </div>
      </div>
    </div>
  );
};

export default ReasoningPanel;
