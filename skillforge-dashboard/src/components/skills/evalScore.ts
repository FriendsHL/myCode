/**
 * SKILL-EVOLVE-LOOP Phase 6 — shared helpers for rendering eval scores
 * on the Skill list table and detail drawer. Centralised so the
 * green/yellow/red thresholds match across SparklineCell, LatestScoreTag,
 * and EvalHistoryChart legend.
 *
 * Thresholds (from prd.md / task #3):
 *   compositeScore >= 80  → green  (healthy)
 *   60 <= score < 80      → yellow (watch)
 *   score < 60            → red    (eligible for self-improve)
 *   N/A                   → grey   (never evaluated)
 */

export type ScoreBucket = 'good' | 'warn' | 'bad' | 'na';

export interface ScoreVisual {
  /** Ant Design Tag color token. */
  tagColor: string;
  /** Hex stroke for the inline sparkline. */
  stroke: string;
  /** Display label beside the score number. */
  label: string;
}

const BUCKETS: Record<ScoreBucket, ScoreVisual> = {
  good: { tagColor: 'success', stroke: '#36b37e', label: 'good' },
  warn: { tagColor: 'gold', stroke: '#ff9f43', label: 'watch' },
  bad: { tagColor: 'error', stroke: '#f0616d', label: 'low' },
  na: { tagColor: 'default', stroke: '#8a8a93', label: 'n/a' },
};

export function bucketForScore(score: number | null | undefined): ScoreBucket {
  if (score == null || !Number.isFinite(score)) return 'na';
  if (score >= 80) return 'good';
  if (score >= 60) return 'warn';
  return 'bad';
}

export function visualForScore(score: number | null | undefined): ScoreVisual {
  return BUCKETS[bucketForScore(score)];
}

export function formatScore(score: number | null | undefined): string {
  if (score == null || !Number.isFinite(score)) return '—';
  return Math.round(score).toString();
}
