import React, { useMemo, useState } from 'react';
import { Select, Tag, Tooltip } from 'antd';
import { useQuery } from '@tanstack/react-query';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import {
  getSkillEvolutions, getSkillMd, getSkillAbTest,
  type SkillEvolutionRun, type SkillAbRun,
} from '../../api';
import { SkillMdDiff } from './SkillMdDiff';
import { AbScenarioResultsTable } from './AbScenarioResultsTable';
import { timeAgo } from './utils';

interface EvolutionDetailPanelProps {
  /** Persisted skill id; the panel only renders for numeric ids (synthesized
   *  string ids from the legacy code path can't have evolutions). */
  skillId: number;
  /** Owner / current user id; required by the new `/skill-md` endpoint to
   *  authorize the read. SkillDrawer sources this from the AuthContext. */
  currentUserId?: number;
}

function statusTagColor(status: SkillEvolutionRun['status']): string {
  switch (status) {
    case 'COMPLETED': return 'success';
    case 'RUNNING': return 'processing';
    case 'PENDING': return 'default';
    case 'PARTIAL': return 'gold';
    case 'FAILED': return 'error';
  }
}

/**
 * SKILL-DASHBOARD-POLISH §B — drawer tab that surfaces "what changed and why".
 *
 *   1. Reasoning  — markdown-rendered LLM trace from `evolution_reasoning`
 *   2. Diff       — side-by-side parent SKILL.md (BE GET /skill-md) vs.
 *                   candidate `improved_skill_md`
 *   3. Per-scenario A/B table — parsed from `t_skill_ab_run.ab_scenario_results_json`
 *
 * Self-check #2: dropdown defaults to the latest run; when no runs exist we
 * render an explicit empty state instead of a half-broken UI with empty panes.
 */
export const EvolutionDetailPanel: React.FC<EvolutionDetailPanelProps> = ({ skillId, currentUserId }) => {
  const { data: runs, isLoading, isError } = useQuery<SkillEvolutionRun[]>({
    queryKey: ['skill-evolution-runs', skillId],
    queryFn: () => getSkillEvolutions(skillId).then(r => r.data),
  });

  const sortedRuns = useMemo(() => {
    if (!runs) return [];
    return [...runs].sort((a, b) => {
      const ta = a.createdAt ? new Date(a.createdAt).getTime() : 0;
      const tb = b.createdAt ? new Date(b.createdAt).getTime() : 0;
      return tb - ta;
    });
  }, [runs]);

  // User-chosen selection (undefined = follow the latest run). Persisting
  // the value across refetches lets background polling not trample the
  // operator's choice. Self-check #2: the dropdown defaults to the latest
  // run when no user selection is active, and the empty-state branch
  // below handles the no-runs case before this resolves.
  const [userSelectedId, setUserSelectedId] = useState<string | undefined>();

  const selectedId = useMemo(() => {
    if (userSelectedId && sortedRuns.some(r => r.id === userSelectedId)) {
      return userSelectedId;
    }
    return sortedRuns[0]?.id;
  }, [sortedRuns, userSelectedId]);

  const selected = useMemo(
    () => sortedRuns.find(r => r.id === selectedId),
    [sortedRuns, selectedId],
  );

  // Parent SKILL.md — fetched only once we have a selected run + a userId.
  // Without userId the BE rejects with 401 (auth interceptor would redirect),
  // so we gate the request through `enabled`.
  const { data: parentMd, isLoading: parentLoading, isError: parentError } = useQuery({
    queryKey: ['skill-md', skillId, currentUserId],
    queryFn: () => getSkillMd(skillId, currentUserId!).then(r => r.data),
    enabled: !!selected && !!currentUserId,
  });

  // The A/B run carries per-scenario JSON — only fetch once the evolution row
  // links one. Evolution-without-A/B (failed mid-flight) skips this branch.
  const abRunId = selected?.abRunId;
  const { data: abRun } = useQuery<SkillAbRun>({
    queryKey: ['skill-ab-test', abRunId],
    queryFn: () => getSkillAbTest(abRunId!).then(r => r.data),
    enabled: !!abRunId,
  });

  if (isLoading) {
    return <div className="sf-empty-state">Loading evolution runs…</div>;
  }
  if (isError) {
    return (
      <div className="sf-empty-state" style={{ color: 'var(--color-err, #f0616d)' }}>
        Failed to load evolution runs.
      </div>
    );
  }
  if (sortedRuns.length === 0) {
    return (
      <div className="sf-empty-state">
        No evolution runs yet. Trigger an evolution from the drawer header to
        generate an improved candidate.
      </div>
    );
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
        <span style={{ fontSize: 11, color: 'var(--fg-4, #8a8a93)' }}>Run</span>
        <Select<string>
          size="small"
          style={{ minWidth: 280 }}
          value={selectedId}
          onChange={(v) => setUserSelectedId(v)}
          options={sortedRuns.map(r => ({
            label: `#${r.id.slice(0, 8)} · ${r.status.toLowerCase()} · ${timeAgo(r.createdAt)}`,
            value: r.id,
          }))}
          data-testid="evolution-run-select"
        />
        {selected && (
          <Tag color={statusTagColor(selected.status)} style={{ marginInlineEnd: 0 }}>
            {selected.status.toLowerCase()}
          </Tag>
        )}
        {selected?.forkedSkillId != null && (
          <Tooltip title={`Candidate is skill #${selected.forkedSkillId}`}>
            <span style={{ fontSize: 11, color: 'var(--fg-4, #8a8a93)' }}>
              candidate #{selected.forkedSkillId}
            </span>
          </Tooltip>
        )}
      </div>

      {/* Reasoning */}
      <Section title="Reasoning">
        {selected?.evolutionReasoning ? (
          <div
            data-testid="evolution-reasoning"
            style={{
              fontSize: 12.5,
              lineHeight: 1.55,
              color: 'var(--fg-2, #cccccc)',
              padding: 12,
              border: '1px solid var(--border-subtle, #2a2a31)',
              borderRadius: 6,
              background: 'var(--bg-secondary, #15151a)',
            }}
          >
            <ReactMarkdown remarkPlugins={[remarkGfm]}>
              {selected.evolutionReasoning}
            </ReactMarkdown>
          </div>
        ) : (
          <div className="sf-empty-state" style={{ padding: '10px 8px', fontSize: 12 }}>
            No reasoning trace recorded for this run.
          </div>
        )}
      </Section>

      {/* Diff */}
      <Section title="SKILL.md diff">
        {parentLoading ? (
          <div className="sf-empty-state">Loading parent SKILL.md…</div>
        ) : parentError ? (
          <div className="sf-empty-state" style={{ color: 'var(--color-err, #f0616d)' }}>
            Failed to load parent SKILL.md.
          </div>
        ) : selected?.improvedSkillMd ? (
          <SkillMdDiff
            parent={parentMd?.content ?? ''}
            candidate={selected.improvedSkillMd}
          />
        ) : (
          <div className="sf-empty-state" style={{ padding: '10px 8px', fontSize: 12 }}>
            No improved SKILL.md captured for this run.
          </div>
        )}
      </Section>

      {/* Per-scenario A/B */}
      <Section title="Per-scenario A/B results">
        {selected?.abRunId ? (
          <AbScenarioResultsTable rawJson={abRun?.abScenarioResultsJson} />
        ) : (
          <div className="sf-empty-state" style={{ padding: '10px 8px', fontSize: 12 }}>
            A/B run not yet started or skipped for this evolution.
          </div>
        )}
      </Section>

      {selected?.failureReason && (
        <Section title="Failure reason">
          <div
            style={{
              fontSize: 12,
              padding: 10,
              border: '1px solid rgba(240,97,109,0.4)',
              borderRadius: 6,
              background: 'rgba(240,97,109,0.06)',
              color: '#f0616d',
              fontFamily: 'var(--font-mono, monospace)',
            }}
          >
            {selected.failureReason}
          </div>
        </Section>
      )}
    </div>
  );
};

const Section: React.FC<{ title: string; children: React.ReactNode }> = ({ title, children }) => (
  <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
    <div
      style={{
        fontSize: 11,
        fontWeight: 600,
        letterSpacing: 0.08,
        textTransform: 'uppercase',
        color: 'var(--fg-3, #a8a8b1)',
      }}
    >
      {title}
    </div>
    {children}
  </div>
);
