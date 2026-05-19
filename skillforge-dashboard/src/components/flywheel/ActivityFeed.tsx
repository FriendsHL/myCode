import React from 'react';
import type { ActivityEvent } from './types';
import { STEP_CATALOGUE } from './types';

interface ActivityFeedProps {
  events: ActivityEvent[];
  loading?: boolean;
}

/**
 * Bottom 24h activity feed (PRD §6). Capped to ~20 most-recent events to
 * keep the panel scannable; the data hook is responsible for sorting (DESC
 * by `at`) and slicing.
 */
const ActivityFeed: React.FC<ActivityFeedProps> = ({ events, loading }) => {
  return (
    <section className="fw-feed" aria-label="24h activity feed">
      <header className="fw-feed-h">
        <h3 className="fw-feed-title">Recent activity · last 24 hours</h3>
        <span className="fw-feed-sub">
          {loading ? 'loading…' : `${events.length} event${events.length === 1 ? '' : 's'}`}
        </span>
      </header>

      {loading ? (
        <div className="fw-loading">Aggregating events…</div>
      ) : events.length === 0 ? (
        <div className="fw-feed-empty">
          No activity in the last 24 hours. Run a chat session or wait for the
          attribution-curator cron (top of every hour) to populate the feed.
        </div>
      ) : (
        <div className="fw-feed-list" data-testid="activity-feed-list">
          {events.map((ev) => {
            const step = STEP_CATALOGUE.find((s) => s.id === ev.stepId);
            const kind = ev.isError ? 'error' : (step?.nodeType ?? 'auto');
            return (
              <div key={ev.id} className="fw-feed-item">
                <span className="fw-feed-time">{fmtShort(ev.at)}</span>
                <span className="fw-feed-dot" data-kind={kind} aria-hidden="true" />
                <span className="fw-feed-label">{ev.label}</span>
                {ev.meta && <span className="fw-feed-meta">{ev.meta}</span>}
              </div>
            );
          })}
        </div>
      )}
    </section>
  );
};

function fmtShort(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  const now = Date.now();
  const diffMs = now - d.getTime();
  if (diffMs < 60_000) return 'just now';
  if (diffMs < 3_600_000) return `${Math.floor(diffMs / 60_000)}m ago`;
  if (diffMs < 86_400_000) return `${Math.floor(diffMs / 3_600_000)}h ago`;
  return d.toLocaleString('en-US', {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
}

export default ActivityFeed;
