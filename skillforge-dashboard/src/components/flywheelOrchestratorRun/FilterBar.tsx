/**
 * OPT-LOOP-FRAMEWORK Sprint 4 — Filter bar for the `/flywheel-runs` page.
 *
 * Three dropdowns (loopKind / agentId / status) + a manual refresh button.
 * Filter changes propagate to the parent via `onChange`; the parent maps
 * them into the list / detail query keys.
 *
 * loopKind options are an open set on the BE (any value in `t_flywheel_run.
 * loop_kind` works), but the BE today emits `opt_report`, `memory_curation`,
 * `attribution`, `subagent_dispatch_test`, ... We expose the commonly-seen
 * values plus a free-form catch-all only when the URL preloads an unknown
 * value (graceful).
 */
import React from 'react';
import { Button, Input, Select, Tooltip } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { LOOP_KIND_OPTIONS, STATUS_OPTIONS } from './filterOptions';

export interface FilterBarValue {
  loopKind: string | null;
  agentId: number | null;
  status: string | null;
}

export interface FilterBarProps {
  value: FilterBarValue;
  onChange: (next: FilterBarValue) => void;
  /** Manual refresh — invalidates both list + detail queries upstream. */
  onRefresh: () => void;
  /** Optional total count shown next to the title (e.g. "13 runs"). */
  total?: number | null;
  /** Show a tiny spinner-ish state on refresh. */
  isFetching?: boolean;
}

const FilterBar: React.FC<FilterBarProps> = ({
  value,
  onChange,
  onRefresh,
  total,
  isFetching,
}) => {
  return (
    <div
      data-testid="flywheel-orch-filter-bar"
      style={{
        display: 'flex',
        flexDirection: 'column',
        gap: 10,
        padding: 16,
        borderBottom: '1px solid var(--border-subtle, var(--border-1, #e0dbcf))',
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8 }}>
        <span
          style={{
            fontFamily: 'var(--font-serif)',
            fontSize: 16,
            fontWeight: 500,
            color: 'var(--fg-1)',
          }}
        >
          Filters
        </span>
        <Tooltip title="Refresh list">
          <Button
            size="small"
            icon={<ReloadOutlined />}
            onClick={onRefresh}
            loading={isFetching}
            data-testid="flywheel-orch-refresh-btn"
          />
        </Tooltip>
      </div>

      <label style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
        <span style={{ fontSize: 11, color: 'var(--fg-3)' }}>Loop kind</span>
        <Select
          allowClear
          size="small"
          placeholder="Any loop kind"
          value={value.loopKind ?? undefined}
          options={LOOP_KIND_OPTIONS}
          onChange={(v) => onChange({ ...value, loopKind: v ?? null })}
          data-testid="flywheel-orch-filter-loopkind"
          style={{ width: '100%' }}
        />
      </label>

      <label style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
        <span style={{ fontSize: 11, color: 'var(--fg-3)' }}>Agent id</span>
        <Input
          allowClear
          size="small"
          placeholder="Any agent"
          type="number"
          value={value.agentId == null ? '' : String(value.agentId)}
          onChange={(e) => {
            const raw = e.target.value.trim();
            const n = raw === '' ? null : Number(raw);
            onChange({ ...value, agentId: n == null || Number.isNaN(n) ? null : n });
          }}
          data-testid="flywheel-orch-filter-agentid"
        />
      </label>

      <label style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
        <span style={{ fontSize: 11, color: 'var(--fg-3)' }}>Status</span>
        <Select
          allowClear
          size="small"
          placeholder="Any status"
          value={value.status ?? undefined}
          options={STATUS_OPTIONS}
          onChange={(v) => onChange({ ...value, status: v ?? null })}
          data-testid="flywheel-orch-filter-status"
          style={{ width: '100%' }}
        />
      </label>

      {total != null && (
        <div style={{ fontSize: 11, color: 'var(--fg-3)' }} data-testid="flywheel-orch-total">
          {total} run{total === 1 ? '' : 's'} matching
        </div>
      )}
    </div>
  );
};

export default FilterBar;
