/**
 * FLYWHEEL-PER-RUN — types.ts unit tests.
 *
 * Focused on the data-shape invariants the FlywheelFlowchart relies on:
 *  - STAGE_TO_STEP returns undefined for unmapped BE stages (5 known
 *    transient/post-MVP values per the doc comment in types.ts) so
 *    consumers can safely fall through to the "no current step" branch.
 *  - PRE_OPTEVENT_CONTEXT_STEPS membership matches the team-lead spec.
 *  - RUN_STAGE_ORDER preserves the canonical pipeline order so completion
 *    derivation in FlywheelFlowchart stays correct.
 */
import { describe, expect, it } from 'vitest';
import {
  ERROR_STAGES,
  PRE_OPTEVENT_CONTEXT_STEPS,
  RUN_STAGE_ORDER,
  STAGE_TO_STEP,
} from '../types';

describe('STAGE_TO_STEP', () => {
  it('returns undefined for unmapped BE stages — no crash, no misleading highlight', () => {
    const UNMAPPED_STAGES = [
      'proposal_approved',
      'candidate_created',
      'canary_started',
      'rolled_back',
      'verified',
    ];
    for (const stage of UNMAPPED_STAGES) {
      // Object lookup must return undefined (fall-through to pending-for-run
      // decoration in FlywheelNode); Map.get would also return undefined.
      expect(STAGE_TO_STEP[stage]).toBeUndefined();
    }
    // Unknown / garbage stage values also return undefined — defensive
    // against forward-compat additions in BE that aren't yet known to FE.
    expect(STAGE_TO_STEP['unknown_future_stage']).toBeUndefined();
    expect(STAGE_TO_STEP['']).toBeUndefined();
  });

  it('covers the 11 MVP BE stages we DO highlight', () => {
    const MVP_MAPPED_STAGES = [
      'dispatch_initiated',
      'proposal_pending',
      'proposal_rejected',
      'candidate_generating',
      'candidate_ready',
      'candidate_failed',
      'ab_running',
      'ab_passed',
      'ab_failed',
      'promoted',
      'discarded',
    ];
    for (const stage of MVP_MAPPED_STAGES) {
      const step = STAGE_TO_STEP[stage];
      expect(step).toBeDefined();
      expect(typeof step).toBe('string');
    }
  });

  it('maps error stages to the auto step that failed (operator sees WHERE it broke)', () => {
    expect(STAGE_TO_STEP['candidate_failed']).toBe('step4-candidate');
    expect(STAGE_TO_STEP['ab_failed']).toBe('step5-abtest');
    // proposal_rejected localizes on G1 — operator rejection happens there.
    expect(STAGE_TO_STEP['proposal_rejected']).toBe('G1-approve-event');
  });

  it('terminal stages localize on G3 (final visible position)', () => {
    expect(STAGE_TO_STEP['promoted']).toBe('G3-promote-decision');
    expect(STAGE_TO_STEP['discarded']).toBe('G3-promote-decision');
  });
});

describe('PRE_OPTEVENT_CONTEXT_STEPS', () => {
  it('includes all 4 entries + step1/2/3 per team-lead spec', () => {
    const expected = [
      'E1-user-chat',
      'E2-upload-skill',
      'E3-extract-skill',
      'E4-write-prompt',
      'step1-annotate',
      'step2-cluster',
      'step3-attribute',
    ];
    for (const id of expected) {
      expect(PRE_OPTEVENT_CONTEXT_STEPS.has(id)).toBe(true);
    }
    // Pipeline steps after step3 must NOT be context (they're part of the
    // OptEvent journey).
    expect(PRE_OPTEVENT_CONTEXT_STEPS.has('G1-approve-event')).toBe(false);
    expect(PRE_OPTEVENT_CONTEXT_STEPS.has('step4-candidate')).toBe(false);
    expect(PRE_OPTEVENT_CONTEXT_STEPS.has('step5-abtest')).toBe(false);
  });
});

describe('RUN_STAGE_ORDER', () => {
  it('preserves canonical pipeline order — step3 → G1 → step4 → G2 → step5 → step6 → G3', () => {
    expect(RUN_STAGE_ORDER).toEqual([
      'step3-attribute',
      'G1-approve-event',
      'step4-candidate',
      'G2-review-draft',
      'step5-abtest',
      'step6-gate',
      'G3-promote-decision',
    ]);
  });

  it('completion derivation: step at lower index < current step idx is "completed for run"', () => {
    // Given current step = step5-abtest (idx 4), step4-candidate (idx 2)
    // is completed; step6-gate (idx 5) is pending.
    const currentIdx = RUN_STAGE_ORDER.indexOf('step5-abtest');
    expect(currentIdx).toBe(4);
    expect(RUN_STAGE_ORDER.indexOf('step4-candidate')).toBeLessThan(currentIdx);
    expect(RUN_STAGE_ORDER.indexOf('step6-gate')).toBeGreaterThan(currentIdx);
  });
});

describe('ERROR_STAGES', () => {
  it('matches the 3 *_failed / *_rejected BE values that trigger red highlight', () => {
    expect(ERROR_STAGES.has('proposal_rejected')).toBe(true);
    expect(ERROR_STAGES.has('candidate_failed')).toBe(true);
    expect(ERROR_STAGES.has('ab_failed')).toBe(true);
    // Terminal-success stages are NOT errors.
    expect(ERROR_STAGES.has('promoted')).toBe(false);
    expect(ERROR_STAGES.has('discarded')).toBe(false);
    // Unmapped stages also aren't errors.
    expect(ERROR_STAGES.has('proposal_approved')).toBe(false);
  });
});
