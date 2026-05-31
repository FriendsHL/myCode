/**
 * AUTOEVOLVE-AGENT-FLYWHEEL Module D — evolve API client unit tests.
 *
 * Key assertions:
 *   - listEvolveRuns reads r.data.items  (enveloped list)
 *   - getEvolveRun   reads r.data directly (single object, NOT enveloped)
 *   - URL patterns match BE contract exactly
 *   - limit param is forwarded
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../index', () => {
  const get = vi.fn();
  return { default: { get } };
});

import api from '../index';
import {
  listEvolveRuns,
  getEvolveRun,
  type EvolveRunSummary,
  type EvolveRunDetail,
  type EvolveIteration,
} from '../evolve';

const mockedGet = (api as unknown as { get: ReturnType<typeof vi.fn> }).get;

// ──────────────────────── fixture helpers ────────────────────────────────────

function makeIteration(overrides: Partial<EvolveIteration> = {}): EvolveIteration {
  return {
    iteration: 1,
    surface: 'prompt',
    changeDesc: 'Tightened instruction phrasing',
    candidateId: 'cand-001',
    baselineScore: 0.72,
    candidateScore: 0.78,
    delta: 0.06,
    kept: true,
    abRunId: 'ab-run-001',
    createdAt: '2026-05-31T10:00:00Z',
    ...overrides,
  };
}

function makeSummary(overrides: Partial<EvolveRunSummary> = {}): EvolveRunSummary {
  return {
    evolveRunId: 'run-abc',
    status: 'completed',
    createdAt: '2026-05-31T09:00:00Z',
    updatedAt: '2026-05-31T09:45:00Z',
    iterationCount: 5,
    finalDelta: 0.12,
    ...overrides,
  };
}

function makeDetail(overrides: Partial<EvolveRunDetail> = {}): EvolveRunDetail {
  return {
    evolveRunId: 'run-abc',
    agentId: 42,
    agentName: 'main-assistant',
    status: 'completed',
    createdAt: '2026-05-31T09:00:00Z',
    updatedAt: '2026-05-31T09:45:00Z',
    iterations: [makeIteration()],
    ...overrides,
  };
}

// ─────────────────────────── tests ───────────────────────────────────────────

describe('evolve API — envelope contract', () => {
  beforeEach(() => {
    mockedGet.mockReset();
  });

  // ── listEvolveRuns ────────────────────────────────────────────────────────

  it('listEvolveRuns calls /evolve/agents/{agentId}/runs with limit param', async () => {
    const envelope = { items: [makeSummary()] };
    mockedGet.mockResolvedValueOnce({ data: envelope });

    await listEvolveRuns(42, 10);

    expect(mockedGet).toHaveBeenCalledWith('/evolve/agents/42/runs', {
      params: { limit: 10 },
    });
  });

  it('listEvolveRuns uses default limit=20 when not provided', async () => {
    mockedGet.mockResolvedValueOnce({ data: { items: [] } });
    await listEvolveRuns(7);
    expect(mockedGet).toHaveBeenCalledWith('/evolve/agents/7/runs', {
      params: { limit: 20 },
    });
  });

  it('listEvolveRuns — caller reads r.data.items (enveloped list shape)', async () => {
    const summary = makeSummary({ evolveRunId: 'run-xyz', iterationCount: 3 });
    mockedGet.mockResolvedValueOnce({ data: { items: [summary] } });

    const res = await listEvolveRuns(42);
    // FE must read r.data.items — not r.data directly
    expect(res.data.items).toHaveLength(1);
    expect(res.data.items[0].evolveRunId).toBe('run-xyz');
    expect(res.data.items[0].iterationCount).toBe(3);
  });

  it('listEvolveRuns — finalDelta may be null (no iterations yet)', async () => {
    const summary = makeSummary({ finalDelta: null, iterationCount: 0 });
    mockedGet.mockResolvedValueOnce({ data: { items: [summary] } });

    const res = await listEvolveRuns(42);
    expect(res.data.items[0].finalDelta).toBeNull();
  });

  // ── getEvolveRun ──────────────────────────────────────────────────────────

  it('getEvolveRun calls /evolve/runs/{evolveRunId} (correct URL)', async () => {
    mockedGet.mockResolvedValueOnce({ data: makeDetail() });
    await getEvolveRun('run-abc');
    expect(mockedGet).toHaveBeenCalledWith('/evolve/runs/run-abc');
  });

  it('getEvolveRun — caller reads r.data directly (NOT enveloped)', async () => {
    const detail = makeDetail({ evolveRunId: 'run-abc', agentId: 99 });
    mockedGet.mockResolvedValueOnce({ data: detail });

    const res = await getEvolveRun('run-abc');
    // FE reads r.data directly (NOT r.data.items)
    expect(res.data.evolveRunId).toBe('run-abc');
    expect(res.data.agentId).toBe(99);
    expect(res.data.agentName).toBe('main-assistant');
  });

  it('getEvolveRun — iterations array contains expected fields', async () => {
    const iter = makeIteration({
      iteration: 2,
      surface: 'skill',
      changeDesc: 'Rewrote bash tool description',
      candidateScore: 0.85,
      delta: 0.13,
      kept: false,
    });
    mockedGet.mockResolvedValueOnce({ data: makeDetail({ iterations: [iter] }) });

    const res = await getEvolveRun('run-abc');
    const it0 = res.data.iterations[0];
    expect(it0.iteration).toBe(2);
    expect(it0.surface).toBe('skill');
    expect(it0.candidateScore).toBe(0.85);
    expect(it0.delta).toBe(0.13);
    expect(it0.kept).toBe(false);
  });

  it('getEvolveRun — candidateScore may be null (eval not completed)', async () => {
    const iter = makeIteration({ candidateScore: null, baselineScore: null });
    mockedGet.mockResolvedValueOnce({ data: makeDetail({ iterations: [iter] }) });

    const res = await getEvolveRun('run-abc');
    expect(res.data.iterations[0].candidateScore).toBeNull();
    expect(res.data.iterations[0].baselineScore).toBeNull();
  });
});
