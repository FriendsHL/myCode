import { useMemo, useState } from 'react';
import { Tag, Tooltip } from 'antd';
import type { SkillRow, SkillArtifactStatus } from './types';
import type { EvalHistoryEntry } from '../../api';
import { timeAgo } from './utils';
import { SparklineCell } from './SparklineCell';
import { formatScore, visualForScore } from './evalScore';

interface SkillTableProps {
  rows: SkillRow[];
  onOpenDetail: (s: SkillRow) => void;
  /**
   * SKILL-EVOLVE-LOOP Phase 6 — per-skill recent eval history rows.
   * Keyed by `SkillRow.id`. Map is empty until the parent's batch
   * useQueries resolves; cells render the "n/a" state in the meantime
   * (no skeletons — skeletons would create more visual churn on a
   * page where most skills genuinely have no history yet).
   */
  histories?: Map<number, EvalHistoryEntry[]>;
  /**
   * SKILL-DASHBOARD-POLISH §A — when true (default), rows with the same
   * `name` are folded into a primary row (`enabled` → most recent semver)
   * with disabled candidates collapsed under an expand toggle. System
   * skills are never aggregated (their names are globally unique by
   * convention). When the parent passes `false` we degrade to the legacy
   * flat layout — useful for tests and for the case where the operator
   * has filtered to disabled-only.
   */
  aggregate?: boolean;
}

/**
 * Color-code artifact lifecycle status (P1-D governance):
 * - active   → green (matches existing s-ok pill)
 * - missing  → orange (artifact dir gone)
 * - invalid  → red (SKILL.md / bundle corrupt)
 * - shadowed → gold (same-name conflict, deferred to a peer)
 *
 * Returns Ant Design Tag color tokens.
 */
function artifactStatusColor(status: SkillArtifactStatus): string {
  switch (status) {
    case 'active': return 'success';
    case 'missing': return 'orange';
    case 'invalid': return 'error';
    case 'shadowed': return 'gold';
  }
}

interface ArtifactStatusBadgeProps {
  status: SkillArtifactStatus;
  shadowedBy?: string;
}

function ArtifactStatusBadge({ status, shadowedBy }: ArtifactStatusBadgeProps) {
  const tag = (
    <Tag color={artifactStatusColor(status)} style={{ marginInlineEnd: 0, textTransform: 'lowercase' }}>
      {status}
    </Tag>
  );
  if (status === 'shadowed' && shadowedBy) {
    return <Tooltip title={`Shadowed by ${shadowedBy}`}>{tag}</Tooltip>;
  }
  return tag;
}

/**
 * Compare two semver strings ("1.0.0" / "0.9.3"). Returns negative if a<b,
 * positive if a>b, 0 if equal. Falls back to string compare when either
 * side is malformed (BE may emit "draft" / "v1" historical formats).
 */
function compareSemver(a?: string, b?: string): number {
  if (!a || !b) return (b ? 1 : 0) - (a ? 1 : 0);
  const pa = a.replace(/^v/, '').split('.').map(n => Number(n));
  const pb = b.replace(/^v/, '').split('.').map(n => Number(n));
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
    const xa = Number.isFinite(pa[i]) ? pa[i] : 0;
    const xb = Number.isFinite(pb[i]) ? pb[i] : 0;
    if (xa !== xb) return xa - xb;
  }
  return 0;
}

interface SkillGroup {
  /** Group key — either `${ownerId}::${name}` for user skills, or the
   *  raw row id for non-aggregable rows (system + groups of one). */
  key: string;
  /** The visible primary row. Always the enabled row when one exists,
   *  else the row with the highest semver. Self-check #1 (task brief): a
   *  group whose primary is `disabled` should never be jumped to a
   *  candidate — we instead fall back to the latest semver disabled row. */
  primary: SkillRow;
  /** Rows folded under the expand toggle. Sorted by semver desc so the
   *  newest disabled candidate sits on top. Empty array = no chevron. */
  candidates: SkillRow[];
}

/**
 * Group rows by `(ownerId ?? primarySkillId, name)`. System skills (`isSystem`)
 * pass through as singletons. The primary row preference order:
 *   1. enabled === true (the active version)
 *   2. highest semver
 *
 * Self-check #1: when no row in a group is enabled, we still pick a primary
 * so the operator can find the skill in the table — the chevron then surfaces
 * the rest. We never silently hide all rows.
 */
function groupRows(rows: SkillRow[]): SkillGroup[] {
  // Each name-bucket: split into enabled + disabled
  const bucket = new Map<string, SkillRow[]>();
  const singletons: SkillRow[] = [];

  for (const r of rows) {
    if (r.isSystem) {
      // System skills never aggregate — their names are globally unique by
      // convention and a "candidates" bucket would be misleading.
      singletons.push(r);
      continue;
    }
    // ownerId is BE-side only for now; fall back to a synthetic key when
    // BE hasn't exposed it yet (current state) so name-only grouping
    // works without merging across owners as long as the userId filter
    // keeps the listing scoped per-user.
    const key = `${r.ownerId ?? '_'}::${r.name}`;
    const arr = bucket.get(key) ?? [];
    arr.push(r);
    bucket.set(key, arr);
  }

  const out: SkillGroup[] = [];

  for (const [key, arr] of bucket.entries()) {
    if (arr.length === 1) {
      out.push({ key, primary: arr[0], candidates: [] });
      continue;
    }
    const sorted = [...arr].sort((a, b) => {
      // enabled first, then semver desc, then createdAt desc
      if (a.enabled !== b.enabled) return a.enabled ? -1 : 1;
      const sv = compareSemver(b.semver, a.semver);
      if (sv !== 0) return sv;
      const ta = a.createdAt ? new Date(a.createdAt).getTime() : 0;
      const tb = b.createdAt ? new Date(b.createdAt).getTime() : 0;
      return tb - ta;
    });
    const [primary, ...rest] = sorted;
    out.push({ key, primary, candidates: rest });
  }

  for (const s of singletons) {
    out.push({ key: String(s.id), primary: s, candidates: [] });
  }

  return out;
}

export function SkillTable({ rows, onOpenDetail, histories, aggregate = true }: SkillTableProps) {
  const [expanded, setExpanded] = useState<Set<string>>(new Set());

  const groups = useMemo<SkillGroup[]>(() => {
    if (!aggregate) {
      return rows.map((r): SkillGroup => ({ key: String(r.id), primary: r, candidates: [] }));
    }
    return groupRows(rows);
  }, [rows, aggregate]);

  const toggle = (key: string) => {
    setExpanded(prev => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  };

  return (
    <table className="skills-table-sf">
      <thead>
        <tr>
          <th style={{ width: 28 }} aria-label="Expand" />
          <th>Name</th>
          <th>Version</th>
          <th>Description</th>
          <th>Source</th>
          <th>Status</th>
          <th>Score</th>
          <th>Trend</th>
          <th>Updated</th>
          <th>Last scanned</th>
        </tr>
      </thead>
      <tbody>
        {groups.flatMap(g => {
          const isOpen = expanded.has(g.key);
          const candCount = g.candidates.length;
          return [
            <SkillRowView
              key={`${g.key}-primary`}
              row={g.primary}
              candidateCount={candCount}
              expanded={isOpen}
              onToggle={candCount > 0 ? () => toggle(g.key) : undefined}
              onOpenDetail={onOpenDetail}
              histories={histories}
              isCandidate={false}
            />,
            ...(isOpen
              ? g.candidates.map(c => (
                  <SkillRowView
                    key={`${g.key}-cand-${c.id}`}
                    row={c}
                    candidateCount={0}
                    expanded={false}
                    onToggle={undefined}
                    onOpenDetail={onOpenDetail}
                    histories={histories}
                    isCandidate
                  />
                ))
              : []),
          ];
        })}
      </tbody>
    </table>
  );
}

interface SkillRowViewProps {
  row: SkillRow;
  candidateCount: number;
  expanded: boolean;
  onToggle?: () => void;
  onOpenDetail: (s: SkillRow) => void;
  histories?: Map<number, EvalHistoryEntry[]>;
  /** Folded under the parent — render with reduced emphasis & inset chevron. */
  isCandidate: boolean;
}

function SkillRowView({
  row, candidateCount, expanded, onToggle, onOpenDetail, histories, isCandidate,
}: SkillRowViewProps) {
  const s = row;
  const artifactStatus = s.artifactStatus ?? 'active';
  const isMissing = artifactStatus === 'missing';
  const rowStyle: React.CSSProperties = {
    ...(isMissing ? { opacity: 0.55 } : {}),
    ...(isCandidate
      ? {
          background: 'var(--bg-secondary, #15151a)',
          // Visually inset so candidates read as children of the primary row.
          fontSize: 'var(--font-size-sm)',
        }
      : {}),
  };
  const sourceLabel = s.originSource ?? s.source;
  const skillIdNum = typeof s.id === 'number' ? s.id : undefined;
  const history = skillIdNum != null ? histories?.get(skillIdNum) : undefined;
  const latestScore = history && history.length > 0 ? history[0].compositeScore : undefined;
  const latestVisual = visualForScore(latestScore);
  return (
    <tr
      onClick={() => onOpenDetail(s)}
      style={rowStyle}
      data-testid={isCandidate ? 'skill-row-candidate' : 'skill-row-primary'}
    >
      <td
        onClick={(e) => {
          if (onToggle) {
            e.stopPropagation();
            onToggle();
          }
        }}
        style={{
          width: 28,
          textAlign: 'center',
          color: 'var(--fg-4, #8a8a93)',
          cursor: onToggle ? 'pointer' : 'default',
          userSelect: 'none',
        }}
        aria-label={onToggle ? (expanded ? 'Collapse versions' : 'Expand versions') : undefined}
      >
        {onToggle && (
          <span
            data-testid="expand-toggle"
            style={{
              display: 'inline-block',
              transform: expanded ? 'rotate(90deg)' : 'rotate(0deg)',
              transition: 'transform 0.15s ease',
              fontSize: 10,
            }}
          >
            ▶
          </span>
        )}
        {isCandidate && (
          <span
            aria-hidden
            style={{
              display: 'inline-block',
              width: 8,
              height: 8,
              borderLeft: '1px solid var(--border-subtle, #2a2a31)',
              borderBottom: '1px solid var(--border-subtle, #2a2a31)',
              marginInlineStart: 8,
            }}
          />
        )}
      </td>
      <td>
        <div className="t-name-sf">
          <span className={`skill-lang-sf lang-${s.lang}`}>{s.lang}</span>
          {isCandidate ? <span style={{ color: 'var(--fg-3, #a8a8b1)' }}>{s.name}</span> : <b>{s.name}</b>}
          {candidateCount > 0 && !isCandidate && (
            <Tooltip title={`${candidateCount} other version${candidateCount === 1 ? '' : 's'}`}>
              <span
                data-testid="versions-badge"
                style={{
                  fontSize: 10,
                  padding: '1px 6px',
                  borderRadius: 10,
                  background: 'rgba(99,102,241,0.12)',
                  color: '#8b8df5',
                  fontFamily: 'var(--font-mono, monospace)',
                  marginLeft: 6,
                  fontWeight: 600,
                }}
              >
                +{candidateCount}
              </span>
            </Tooltip>
          )}
          {s.isSystem && (
            <Tag
              color="blue"
              style={{ marginInlineStart: 6, textTransform: 'lowercase' }}
              data-testid="system-tag"
            >
              system
            </Tag>
          )}
        </div>
      </td>
      <td>
        {s.semver ? (
          <span
            style={{
              fontSize: 11,
              fontFamily: 'var(--font-mono, monospace)',
              color: 'var(--fg-3, #a8a8b1)',
            }}
          >
            {s.semver}
          </span>
        ) : (
          <span style={{ color: 'var(--fg-4, #8a8a93)' }}>—</span>
        )}
      </td>
      <td>{s.description || '—'}</td>
      <td><span className={`skill-source-sf src-${s.source}`}>{sourceLabel}</span></td>
      <td>
        <div style={{ display: 'inline-flex', gap: 6, alignItems: 'center' }}>
          <ArtifactStatusBadge status={artifactStatus} shadowedBy={s.shadowedBy} />
          {!s.enabled && (
            <span className="status-pill-sf s-draft">
              <span className="status-dot-sf" /> disabled
            </span>
          )}
        </div>
      </td>
      <td>
        <Tag
          color={latestVisual.tagColor}
          style={{
            marginInlineEnd: 0,
            fontFamily: 'var(--font-mono, monospace)',
            fontSize: 11,
            minWidth: 32,
            textAlign: 'center',
          }}
          data-testid="latest-score-tag"
        >
          {formatScore(latestScore)}
        </Tag>
      </td>
      <td>
        <SparklineCell history={history} />
      </td>
      <td style={{ fontFamily: 'var(--font-mono)', fontSize: 11, color: 'var(--fg-4)' }}>{timeAgo(s.createdAt)}</td>
      <td style={{ fontFamily: 'var(--font-mono)', fontSize: 11, color: 'var(--fg-4)' }}>{timeAgo(s.lastScannedAt)}</td>
    </tr>
  );
}
