import React, { useMemo, useState } from 'react';
import { Tag, Tooltip, Alert, Empty } from 'antd';
import { useQuery } from '@tanstack/react-query';
import {
  listBehaviorRuleVersions,
  type BehaviorRuleVersionResponse,
} from '../../api/behaviorRules';
import { CanaryPanel } from '../canary/CanaryPanel';

/**
 * MULTI-SURFACE-FLYWHEEL V4 Phase 1.4 — behavior_rule canary management
 * surface. Composes:
 *
 * <ol>
 *   <li>Top — active vs candidate version chips with versionNumber / source /
 *       createdAt / collapsible rulesJson preview.</li>
 *   <li>Middle — embedded {@link CanaryPanel} configured with
 *       {@code surfaceType="behavior_rule"} and {@code entityNoun="behavior
 *       rule version"} so the start/step-up/publish/rollback flow reuses the
 *       V2 surface verbatim. The BE column {@code baseline_skill_name}
 *       (VARCHAR(64), legacy name) stores the baseline version id; Phase 1.4
 *       BE-2 lifts the surface guard so {@code behavior_rule} is accepted.</li>
 *   <li>(Action buttons are owned by CanaryPanel — Start / Step-up / Publish /
 *       Rollback — so we don't duplicate the lifecycle controls here.)</li>
 * </ol>
 *
 * <p>Graceful degradation: if BE has not yet exposed
 * {@code GET /api/behavior-rules/versions}, the version-list query rejects;
 * the panel surfaces a non-blocking banner ("BE endpoint not wired up yet")
 * and still renders the CanaryPanel underneath so canary state is visible
 * once BE-2 lands.
 */

export interface BehaviorRuleEvolutionPanelProps {
  /**
   * Stringified agent id. BE entity column is VARCHAR(36); FE
   * {@code AgentDto.id} is a {@code number}, so the page-level component
   * stringifies before passing through.
   */
  agentId: string;
  /** Numeric agent id (passed straight through to CanaryPanel). */
  agentNumericId: number;
}

function statusColor(status: BehaviorRuleVersionResponse['status']): string {
  switch (status) {
    case 'active':
      return 'green';
    case 'candidate':
      return 'blue';
    case 'retired':
      return 'default';
    case 'rejected':
      return 'red';
    default:
      return 'default';
  }
}

function formatTimestamp(iso: string | null): string {
  if (!iso) return '—';
  try {
    return new Date(iso).toLocaleString();
  } catch {
    return iso;
  }
}

interface VersionCardProps {
  /** Role label rendered at the card head: "Baseline" / "Candidate". */
  label: string;
  version: BehaviorRuleVersionResponse | null;
  /** Color hint for the label chip. */
  accent: 'green' | 'blue';
}

const VersionCard: React.FC<VersionCardProps> = ({ label, version, accent }) => {
  // All hooks must run unconditionally on every render (rules-of-hooks);
  // branching on `version` happens after the hook calls.
  const [open, setOpen] = useState(false);
  const accentColor = accent === 'green' ? '#36b37e' : '#6366f1';
  const prettyRules = useMemo(() => {
    if (!version) return '';
    try {
      return JSON.stringify(JSON.parse(version.rulesJson), null, 2);
    } catch {
      // BE column stores arbitrary JSON; if it ever lands as non-JSON text
      // (shouldn't, but defensive) show the raw blob rather than crashing.
      return version.rulesJson;
    }
  }, [version]);

  if (!version) {
    return (
      <div
        style={{
          flex: 1,
          minWidth: 240,
          padding: '12px 14px',
          border: '1px dashed var(--border-subtle, #2a2a31)',
          borderRadius: 6,
          color: 'var(--fg-4)',
          fontSize: 12,
        }}
      >
        <div style={{ fontSize: 10, color: 'var(--fg-3)', marginBottom: 4 }}>
          {label.toUpperCase()}
        </div>
        <span>None — no version of this status persisted yet.</span>
      </div>
    );
  }

  return (
    <div
      style={{
        flex: 1,
        minWidth: 240,
        padding: '12px 14px',
        border: '1px solid var(--border-subtle, #2a2a31)',
        borderRadius: 6,
        background: 'var(--bg-base, transparent)',
      }}
    >
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 6,
          marginBottom: 8,
        }}
      >
        <span
          style={{
            fontSize: 10,
            color: 'var(--fg-3)',
            letterSpacing: 0.5,
          }}
        >
          {label.toUpperCase()}
        </span>
        <Tag color={statusColor(version.status)} style={{ marginRight: 0 }}>
          v{version.versionNumber}
        </Tag>
        <Tag color={statusColor(version.status)} style={{ marginRight: 0 }}>
          {version.status}
        </Tag>
        <Tooltip title={`Source: ${version.source}`}>
          <Tag style={{ marginLeft: 'auto', marginRight: 0 }}>{version.source}</Tag>
        </Tooltip>
      </div>
      <div
        style={{
          fontSize: 11,
          color: 'var(--fg-4)',
          fontFamily: 'var(--font-mono, monospace)',
          marginBottom: 6,
          wordBreak: 'break-all',
        }}
      >
        id: {version.id}
      </div>
      <div style={{ fontSize: 11, color: 'var(--fg-4)', marginBottom: 6 }}>
        Created: {formatTimestamp(version.createdAt)}
        {version.promotedAt && (
          <>
            {' '}· Promoted: {formatTimestamp(version.promotedAt)}
          </>
        )}
      </div>
      {version.improvementRationale && (
        <div
          style={{
            fontSize: 11.5,
            color: 'var(--fg-2)',
            marginBottom: 8,
            padding: '6px 8px',
            background: 'var(--bg-hover, rgba(99,102,241,0.06))',
            borderLeft: `2px solid ${accentColor}`,
            lineHeight: 1.5,
          }}
        >
          {version.improvementRationale}
        </div>
      )}
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        style={{
          fontSize: 11,
          padding: '2px 8px',
          background: 'transparent',
          color: 'var(--fg-3)',
          border: '1px solid var(--border-subtle, #2a2a31)',
          borderRadius: 4,
          cursor: 'pointer',
        }}
      >
        {open ? '▾ Hide rules JSON' : '▸ Show rules JSON'}
      </button>
      {open && (
        <pre
          style={{
            marginTop: 6,
            padding: 8,
            background: 'var(--bg-hover, #1d1d22)',
            borderRadius: 4,
            fontSize: 11,
            lineHeight: 1.45,
            maxHeight: 220,
            overflow: 'auto',
            color: 'var(--fg-2)',
          }}
        >
          {prettyRules}
        </pre>
      )}
    </div>
  );
};

export const BehaviorRuleEvolutionPanel: React.FC<BehaviorRuleEvolutionPanelProps> = ({
  agentId,
  agentNumericId,
}) => {
  // List ALL statuses; pick the first active + first candidate by versionNumber
  // DESC (BE-expected ordering). Two queries would cost an extra round-trip
  // for negligible payload; a single list is simpler and cache-friendly.
  const versionsQuery = useQuery<BehaviorRuleVersionResponse[]>({
    queryKey: ['behavior-rule-versions', agentId],
    queryFn: () => listBehaviorRuleVersions({ agentId }).then((r) => r.data ?? []),
    // Versions change on promote/rollback only; 30s staleTime balances
    // freshness vs unnecessary refetch when navigating tabs.
    staleTime: 30_000,
    // Retry once so a transient blip doesn't fall through to the empty banner
    // immediately. 404 / 4xx still surfaces after one failed retry.
    retry: 1,
  });

  const { activeVersion, candidateVersion } = useMemo(() => {
    const all = versionsQuery.data ?? [];
    const sorted = [...all].sort((a, b) => b.versionNumber - a.versionNumber);
    const active = sorted.find((v) => v.status === 'active') ?? null;
    // Prefer the newest candidate (highest versionNumber).
    const candidate = sorted.find((v) => v.status === 'candidate') ?? null;
    return { activeVersion: active, candidateVersion: candidate };
  }, [versionsQuery.data]);

  const isError = versionsQuery.isError;
  const isLoading = versionsQuery.isLoading;
  const noVersions = !isLoading && !isError && (versionsQuery.data?.length ?? 0) === 0;

  return (
    <div
      style={{
        padding: '14px 16px',
        border: '1px solid var(--border-subtle, #2a2a31)',
        borderRadius: 8,
        background: 'var(--bg-base, transparent)',
      }}
    >
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 8,
          marginBottom: 12,
        }}
      >
        <span style={{ fontWeight: 600, fontSize: 13, color: 'var(--fg-1)' }}>
          Behavior Rule Evolution
        </span>
        <Tag color="purple">behavior_rule</Tag>
        <span
          style={{
            marginLeft: 'auto',
            fontSize: 11,
            color: 'var(--fg-4)',
            fontFamily: 'var(--font-mono, monospace)',
          }}
        >
          agent #{agentId}
        </span>
      </div>

      {isError && (
        // BE endpoint missing or transient failure: keep the canary subpanel
        // visible underneath rather than blocking the whole surface — Phase
        // 1.4 BE-2 is in parallel; the operator may still want to look at the
        // canary state once it's wired up.
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 12 }}
          message="Could not load behavior rule versions."
          description="Backend endpoint /api/behavior-rules/versions may not be exposed yet, or the request failed. Canary state below is still queryable."
        />
      )}

      {isLoading && (
        <div
          style={{
            padding: '14px 0',
            fontSize: 11,
            color: 'var(--fg-4)',
            marginBottom: 12,
          }}
        >
          Loading versions…
        </div>
      )}

      {noVersions && (
        <Empty
          image={Empty.PRESENTED_IMAGE_SIMPLE}
          description={
            <span style={{ fontSize: 12, color: 'var(--fg-3)' }}>
              No persisted behavior_rule versions yet for this agent — the agent is using the
              startup BehaviorRuleRegistry baseline. New versions appear here once an attribution
              proposal or auto_improve run produces a candidate.
            </span>
          }
          style={{ marginBottom: 12 }}
        />
      )}

      {!isLoading && !isError && !noVersions && (
        <div
          style={{
            display: 'flex',
            gap: 12,
            flexWrap: 'wrap',
            marginBottom: 14,
          }}
        >
          <VersionCard label="Baseline (active)" version={activeVersion} accent="green" />
          <VersionCard label="Candidate" version={candidateVersion} accent="blue" />
        </div>
      )}

      {/* CanaryPanel owns the lifecycle controls (Start / Step-up / Publish /
          Rollback). We pass the version IDs as the baseline/candidate identity
          strings — BE column is VARCHAR(64), version IDs are 36-char UUIDs so
          they fit. Without a candidate version we still mount the panel so the
          operator can see an in-flight or terminal rollout (CanaryPanel's
          Start button stays disabled until a candidate exists). */}
      <CanaryPanel
        agentId={agentNumericId}
        surfaceType="behavior_rule"
        entityNoun="behavior rule version"
        parentSkillName={activeVersion?.id ?? ''}
        candidateSkillName={candidateVersion?.id ?? null}
      />
    </div>
  );
};

export default BehaviorRuleEvolutionPanel;
