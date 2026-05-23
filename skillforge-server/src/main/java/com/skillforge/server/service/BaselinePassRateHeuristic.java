package com.skillforge.server.service;

import com.skillforge.server.entity.EvalScenarioEntity;

import java.util.List;

/**
 * EVAL-DATASET-LAYER V1 (★ r4 D4 fix ★): strategy interface for estimating the
 * baseline pass rate of a candidate dataset version.
 *
 * <p>Why abstract a strategy: V1 uses a hand-tuned per-source-type table
 * (see {@link StaticSourceTypeHeuristic}). V2 backlog: replace with a
 * fit-from-history heuristic that observes the last N completed A/B runs.
 * Keeping the interface decoupled lets V2 swap implementations without
 * touching {@link EvalDatasetService}.
 *
 * <p>The estimated value drives {@code composition_stats.expected_baseline_pass_rate}
 * in the FE; once an actual A/B has run against the version, the
 * {@code actualBaselinePassRate} column (back-written by
 * {@link com.skillforge.server.improve.AbEvalPipeline}) takes priority.
 */
public interface BaselinePassRateHeuristic {

    /**
     * Estimate the baseline pass rate (range {@code [0.0, 1.0]}) for the
     * given scenario set. Returns {@code 0.0} for an empty list (caller
     * defends against that — see
     * {@link EvalDatasetService#publishVersion}).
     */
    double estimate(List<EvalScenarioEntity> scenarios);
}
