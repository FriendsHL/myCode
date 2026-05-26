import React, { useEffect, useRef, useState } from 'react';
import MarkdownRenderer from './MarkdownRenderer';

/**
 * Throttled markdown renderer for streaming text. High-frequency `content`
 * updates (e.g. SSE `text_delta` / `reasoning_delta` accumulation) are
 * captured into a ref and committed to React state at 200ms intervals so the
 * DOM re-renders at most 5×/sec. See `.claude/rules/frontend.md` footgun #3.
 *
 * Extracted from `ChatWindow.tsx` into its own module so consumers
 * (`ReasoningPanel`) can import it without creating a
 * `ChatWindow ↔ ReasoningPanel` circular dependency (which Vite tolerates
 * at dev-time but breaks under fast-refresh and production tree-shaking
 * edge cases). CHAT-REASONING-PANEL.
 */
export const ThrottledMarkdown: React.FC<{ content: string }> = ({ content }) => {
  const [rendered, setRendered] = useState(content);
  const latestRef = useRef(content);
  latestRef.current = content;

  useEffect(() => {
    const interval = setInterval(() => setRendered(latestRef.current), 200);
    return () => clearInterval(interval);
  }, []);

  useEffect(() => {
    if (content && !rendered) setRendered(content);
  }, [content, rendered]);

  return <MarkdownRenderer content={rendered} />;
};

export default ThrottledMarkdown;
