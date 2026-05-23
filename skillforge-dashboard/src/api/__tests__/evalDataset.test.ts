/**
 * EVAL-DATASET-LAYER V1 — evalDataset API client unit tests.
 *
 * Covers:
 *   - request shapes (URL + params + body) for the 7 new endpoints
 *   - helpers: formatBaselinePassRate / pickBaselineDisplay
 *
 * The BE-side contract (Controller javadoc) is the authoritative outer-
 * envelope source; these tests mock the axios layer so they catch FE
 * client regressions only. Real-activity smoke (raw JSON shape) lives
 * under `skillforge-server` IT tests + Phase Final `curl` sweep.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../index', () => {
  const get = vi.fn();
  const post = vi.fn();
  return {
    default: { get, post, put: vi.fn(), delete: vi.fn() },
    extractList: <T,>(res: { data: T[] | { data: T[] } | unknown }): T[] =>
      Array.isArray(res.data) ? (res.data as T[]) : [],
  };
});

import api from '../index';
import {
  listEvalScenarios,
  listDatasets,
  getDataset,
  createDataset,
  listVersions,
  publishVersion,
  getDatasetVersion,
  getDatasetVersionHealth,
  formatBaselinePassRate,
  pickBaselineDisplay,
  type EvalDataset,
  type EvalDatasetSummary,
  type EvalDatasetVersion,
  type EvalDatasetVersionDetail,
  type EvalScenarioDto,
  type DatasetHealthAssessment,
} from '../evalDataset';

const mockedGet = (api as unknown as { get: ReturnType<typeof vi.fn> }).get;
const mockedPost = (api as unknown as { post: ReturnType<typeof vi.fn> }).post;

describe('evalDataset API client — request shapes', () => {
  beforeEach(() => {
    mockedGet.mockReset();
    mockedPost.mockReset();
  });

  it('listEvalScenarios passes sourceType / purpose / sourceRef as query params', async () => {
    mockedGet.mockResolvedValueOnce({ data: [] satisfies EvalScenarioDto[] });
    await listEvalScenarios({ sourceType: 'benchmark', purpose: 'baseline_anchor' });
    expect(mockedGet).toHaveBeenCalledWith('/eval/scenarios', {
      params: { sourceType: 'benchmark', purpose: 'baseline_anchor' },
    });
  });

  it('listDatasets passes ownerId / agentId as query params (bare list response)', async () => {
    const summary: EvalDatasetSummary[] = [];
    mockedGet.mockResolvedValueOnce({ data: summary });
    await listDatasets({ ownerId: 1, agentId: '3' });
    expect(mockedGet).toHaveBeenCalledWith('/eval/datasets', {
      params: { ownerId: 1, agentId: '3' },
    });
  });

  it('getDataset uses /eval/datasets/{id} (single object response)', async () => {
    const ds: EvalDataset = {
      id: 'abc', name: 'baseline-v1', ownerId: 1, isPublic: false,
      createdAt: '2026-05-24T00:00:00Z', updatedAt: '2026-05-24T00:00:00Z',
    };
    mockedGet.mockResolvedValueOnce({ data: ds });
    const r = await getDataset('abc');
    expect(mockedGet).toHaveBeenCalledWith('/eval/datasets/abc');
    expect(r.data).toEqual(ds);
  });

  it('createDataset POSTs to /eval/datasets with body shape (no isPublic default flip)', async () => {
    mockedPost.mockResolvedValueOnce({
      data: {
        id: 'abc', name: 'x', ownerId: 1, isPublic: false,
        createdAt: '', updatedAt: '',
      } satisfies EvalDataset,
    });
    await createDataset({
      name: 'main-assistant-baseline-v1',
      description: 'GAIA + τ-bench mix',
      ownerId: 1,
      agentId: null,
      tags: ['gaia', 'baseline'],
      isPublic: false,
    });
    expect(mockedPost).toHaveBeenCalledWith('/eval/datasets', {
      name: 'main-assistant-baseline-v1',
      description: 'GAIA + τ-bench mix',
      ownerId: 1,
      agentId: null,
      tags: ['gaia', 'baseline'],
      isPublic: false,
    });
  });

  it('listVersions hits /eval/datasets/{id}/versions', async () => {
    mockedGet.mockResolvedValueOnce({ data: [] satisfies EvalDatasetVersion[] });
    await listVersions('ds-1');
    expect(mockedGet).toHaveBeenCalledWith('/eval/datasets/ds-1/versions');
  });

  it('publishVersion POSTs scenarioIds to /eval/datasets/{id}/versions', async () => {
    mockedPost.mockResolvedValueOnce({
      data: {
        id: 'v1', datasetId: 'ds-1', versionNumber: 1, createdAt: '',
      } satisfies EvalDatasetVersion,
    });
    await publishVersion('ds-1', { scenarioIds: ['s1', 's2'], createdBy: 1 });
    expect(mockedPost).toHaveBeenCalledWith('/eval/datasets/ds-1/versions', {
      scenarioIds: ['s1', 's2'],
      createdBy: 1,
    });
  });

  it('getDatasetVersion returns the 3-field envelope (version + scenarioIds + scenarios)', async () => {
    // BE-Dev confirmed shape (EvalDatasetController.getVersionWithScenarios):
    //   { version: {...}, scenarioIds: ["s1"], scenarios: [briefMap] }
    const envelope: EvalDatasetVersionDetail = {
      version: {
        id: 'v1', datasetId: 'ds-1', versionNumber: 1, createdAt: '',
      },
      scenarioIds: ['s1'],
      scenarios: [{
        id: 's1', name: 'GAIA-Lv1-001', agentId: null,
        sourceType: 'benchmark', sourceRef: 'gaia/lv1#001',
        purpose: 'baseline_anchor', oracleType: 'exact_match',
        status: 'active',
      }],
    };
    mockedGet.mockResolvedValueOnce({ data: envelope });
    const r = await getDatasetVersion('v1');
    expect(mockedGet).toHaveBeenCalledWith('/eval/dataset-versions/v1');
    expect(r.data.version.versionNumber).toBe(1);
    expect(r.data.scenarioIds).toEqual(['s1']);
    expect(r.data.scenarios[0].sourceType).toBe('benchmark');
  });

  it('getDatasetVersionHealth returns {isHealthy, warnings: string[]} (plain strings, not structured)', async () => {
    // BE-Dev confirmed shape (EvalDatasetService.DatasetHealthAssessment record):
    //   record(boolean isHealthy, List<String> warnings)
    const health: DatasetHealthAssessment = {
      isHealthy: false,
      warnings: ['dataset 100% session_derived, baseline likely 0% pass rate'],
    };
    mockedGet.mockResolvedValueOnce({ data: health });
    const r = await getDatasetVersionHealth('v1');
    expect(mockedGet).toHaveBeenCalledWith('/eval/dataset-versions/v1/health');
    expect(r.data.warnings).toEqual(['dataset 100% session_derived, baseline likely 0% pass rate']);
    expect(r.data.isHealthy).toBe(false);
  });
});

describe('evalDataset helpers', () => {
  it('formatBaselinePassRate renders dash for null / undefined / NaN', () => {
    expect(formatBaselinePassRate(null)).toBe('—');
    expect(formatBaselinePassRate(undefined)).toBe('—');
    expect(formatBaselinePassRate(NaN)).toBe('—');
  });

  it('formatBaselinePassRate renders rounded percentage with optional suffix', () => {
    expect(formatBaselinePassRate(0.327)).toBe('33%');
    expect(formatBaselinePassRate(0.5, 'actual')).toBe('50% actual');
    expect(formatBaselinePassRate(0)).toBe('0%');
    expect(formatBaselinePassRate(1)).toBe('100%');
  });

  it('pickBaselineDisplay prefers actual over expected', () => {
    expect(pickBaselineDisplay(0.4, 0.3)).toEqual({ value: 0.4, kind: 'actual' });
    expect(pickBaselineDisplay(0, 0.3)).toEqual({ value: 0, kind: 'actual' });
  });

  it('pickBaselineDisplay falls back to expected when actual is null', () => {
    expect(pickBaselineDisplay(null, 0.3)).toEqual({ value: 0.3, kind: 'expected' });
    expect(pickBaselineDisplay(undefined, 0.3)).toEqual({ value: 0.3, kind: 'expected' });
  });

  it('pickBaselineDisplay returns kind="none" when both are null', () => {
    expect(pickBaselineDisplay(null, null)).toEqual({ value: null, kind: 'none' });
    expect(pickBaselineDisplay(undefined, undefined)).toEqual({ value: null, kind: 'none' });
    expect(pickBaselineDisplay(NaN, NaN)).toEqual({ value: null, kind: 'none' });
  });
});
