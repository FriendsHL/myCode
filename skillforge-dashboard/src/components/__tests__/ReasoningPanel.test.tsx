/**
 * CHAT-REASONING-PANEL — ReasoningPanel rendering matrix.
 *
 * Three modes:
 *   - streaming  (streamingText non-empty + forceStreaming): spinner + live text
 *   - completed  (reasoningContent non-empty): collapsible header
 *   - null       (neither): renders nothing
 *
 * Plus expand/collapse toggle behavior + defaultExpanded prop.
 */
import React from 'react';
import { describe, it, expect } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import ReasoningPanel from '../ReasoningPanel';

// jsdom polyfills the tests need — keep light, ReasoningPanel doesn't use
// AntD Tooltip / Modal so no ResizeObserver / matchMedia is required.

describe('ReasoningPanel', () => {
  it('renders null when reasoningContent is empty/null/whitespace and no streamingText', () => {
    const { container: c1 } = render(<ReasoningPanel />);
    expect(c1.firstChild).toBeNull();

    const { container: c2 } = render(<ReasoningPanel reasoningContent="" />);
    expect(c2.firstChild).toBeNull();

    const { container: c3 } = render(<ReasoningPanel reasoningContent={null} />);
    expect(c3.firstChild).toBeNull();

    // Use JS expression form so the `\n` is interpreted as a newline
    // (JSX attribute string form would treat `\n` as the literal 2-char
    // sequence backslash+n, which `.trim()` does NOT strip).
    const { container: c4 } = render(<ReasoningPanel reasoningContent={'   \n  '} />);
    expect(c4.firstChild).toBeNull();

    const { container: c5 } = render(<ReasoningPanel streamingText="" reasoningContent="" />);
    expect(c5.firstChild).toBeNull();
  });

  it('renders streaming mode when streamingText non-empty and forceStreaming=true', () => {
    render(
      <ReasoningPanel
        streamingText="Step 1: think about the problem"
        forceStreaming
      />,
    );
    // Streaming-mode test id present (no completed-mode test id)
    expect(screen.getByTestId('reasoning-panel-streaming')).toBeInTheDocument();
    expect(screen.queryByTestId('reasoning-panel-completed')).not.toBeInTheDocument();
    expect(screen.getByText(/Thinking/)).toBeInTheDocument();
    // Live reasoning text is rendered (ThrottledMarkdown commits initial content
    // synchronously via the `content && !rendered` effect).
    expect(screen.getByText(/Step 1: think about the problem/)).toBeInTheDocument();
  });

  it('renders completed mode with formatted duration when reasoningContent present and not forced streaming', () => {
    render(
      <ReasoningPanel
        reasoningContent="The chain of thought lives here."
        durationMs={3200}
      />,
    );
    expect(screen.getByTestId('reasoning-panel-completed')).toBeInTheDocument();
    // Toggle has the formatted duration label
    const toggle = screen.getByTestId('reasoning-panel-toggle');
    expect(toggle.textContent).toContain('Thought for 3.2s');
    // Collapsed by default — content body is in DOM (max-height transition) but
    // wrapper has aria-hidden=true and lacks the --expanded modifier.
    const body = screen.getByText(/chain of thought/).closest(
      '.reasoning-panel__content',
    ) as HTMLElement;
    expect(body).not.toHaveClass('reasoning-panel__content--expanded');
  });

  it('falls back to "Thought" (no seconds) when durationMs is null — historical message path', () => {
    render(
      <ReasoningPanel
        reasoningContent="Historical reasoning text."
        durationMs={null}
      />,
    );
    const toggle = screen.getByTestId('reasoning-panel-toggle');
    // The bare "Thought" label without seconds — ensure no "for" string leaked.
    expect(toggle.textContent).toContain('Thought');
    expect(toggle.textContent).not.toContain('Thought for');
  });

  it('toggles expanded/collapsed when header is clicked', () => {
    render(
      <ReasoningPanel
        reasoningContent="Some reasoning."
        durationMs={2500}
      />,
    );
    const toggle = screen.getByTestId('reasoning-panel-toggle');
    const body = screen.getByText('Some reasoning.').closest(
      '.reasoning-panel__content',
    ) as HTMLElement;
    // Starts collapsed
    expect(body).not.toHaveClass('reasoning-panel__content--expanded');
    expect(toggle).toHaveAttribute('aria-expanded', 'false');

    fireEvent.click(toggle);
    expect(body).toHaveClass('reasoning-panel__content--expanded');
    expect(toggle).toHaveAttribute('aria-expanded', 'true');

    fireEvent.click(toggle);
    expect(body).not.toHaveClass('reasoning-panel__content--expanded');
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
  });

  it('starts expanded when defaultExpanded=true (agent.thinkingVisible=true path)', () => {
    render(
      <ReasoningPanel
        reasoningContent="Default-visible reasoning."
        durationMs={1500}
        defaultExpanded
      />,
    );
    const toggle = screen.getByTestId('reasoning-panel-toggle');
    expect(toggle).toHaveAttribute('aria-expanded', 'true');
    const body = screen.getByText('Default-visible reasoning.').closest(
      '.reasoning-panel__content',
    ) as HTMLElement;
    expect(body).toHaveClass('reasoning-panel__content--expanded');
  });
});
