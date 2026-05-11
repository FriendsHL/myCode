package com.skillforge.server.eval;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * EVAL-V2 M4: versioned multi-dimensional score aggregation.
 *
 * <p>{@code M4_V2} (eval-latency-not-measured): when a scenario lacks a
 * {@code performanceThresholdMs} (null or {@code <= 0}), latency cannot be
 * scored and is recorded as <b>not_measured</b> instead of silently scoring
 * 100. Composite is then re-normalised across the remaining 3 measured
 * dimensions (quality + efficiency + cost, total weight {@code 0.85}) so a
 * missing latency budget no longer inflates the composite by +15 points.
 *
 * <p>The {@code dimensionStatus} map on {@link Result} surfaces per-dimension
 * measured/not_measured state to UI consumers (A/B compare chips). Cost retains
 * the legacy "null-threshold → 100" semantics because cost always has a global
 * fallback threshold ({@code 0.01 USD}) — its {@code BigDecimal} overload of
 * {@link #normalizeThresholdMetric} is intentionally left untouched.
 */
public final class EvalScoreFormula {

    public static final String FORMULA_VERSION = "M4_V2";
    public static final double PASS_THRESHOLD = 40.0;
    public static final double QUALITY_FLOOR_THRESHOLD = 30.0;
    public static final double QUALITY_FLOOR_CAP = 39.0;

    public static final String DIM_STATUS_MEASURED = "measured";
    public static final String DIM_STATUS_NOT_MEASURED = "not_measured";

    private static final double QUALITY_WEIGHT = 0.55;
    private static final double EFFICIENCY_WEIGHT = 0.20;
    private static final double LATENCY_WEIGHT = 0.15;
    private static final double COST_WEIGHT = 0.10;

    /**
     * Sum of weights for measured dimensions when latency is "not_measured".
     * Pre-computed so the hot path (one call per scenario row) avoids the
     * runtime FP addition and the intent is explicit at the use site.
     * Compile-time {@code static final} permits constant folding regardless.
     */
    private static final double LATENCY_NOT_MEASURED_REWEIGHT_DIVISOR =
            QUALITY_WEIGHT + EFFICIENCY_WEIGHT + COST_WEIGHT;

    private EvalScoreFormula() {
    }

    public static Result calculate(double qualityScore,
                                   double efficiencyScore,
                                   Long latencyMs,
                                   Long latencyThresholdMs,
                                   BigDecimal costUsd,
                                   BigDecimal costThresholdUsd,
                                   Integer loopCount,
                                   Integer toolCallCount) {
        double normalizedQuality = clampScore(qualityScore);
        double normalizedEfficiency = clampScore(efficiencyScore);
        Double latencyScore = latencyScoreOrNull(latencyMs, latencyThresholdMs);
        double costScore = normalizeThresholdMetric(costUsd, costThresholdUsd);

        boolean latencyMeasured = latencyScore != null;

        double rawComposite;
        if (latencyMeasured) {
            rawComposite = normalizedQuality * QUALITY_WEIGHT
                    + normalizedEfficiency * EFFICIENCY_WEIGHT
                    + latencyScore * LATENCY_WEIGHT
                    + costScore * COST_WEIGHT;
        } else {
            // Re-normalise across measured dimensions (quality + efficiency + cost),
            // total weight 0.85, so omitting latency neither inflates nor deflates
            // the composite. Quality-floor cap is applied AFTER re-normalisation so
            // a low-quality "not_measured" latency case still gets capped at 39.
            double measuredWeightedSum = normalizedQuality * QUALITY_WEIGHT
                    + normalizedEfficiency * EFFICIENCY_WEIGHT
                    + costScore * COST_WEIGHT;
            rawComposite = measuredWeightedSum / LATENCY_NOT_MEASURED_REWEIGHT_DIVISOR;
        }

        boolean qualityFloorApplied = normalizedQuality < QUALITY_FLOOR_THRESHOLD
                && rawComposite > QUALITY_FLOOR_CAP;
        double finalComposite = qualityFloorApplied ? QUALITY_FLOOR_CAP : rawComposite;

        Map<String, String> dimensionStatus = new LinkedHashMap<>();
        dimensionStatus.put("quality", DIM_STATUS_MEASURED);
        dimensionStatus.put("efficiency", DIM_STATUS_MEASURED);
        dimensionStatus.put("latency", latencyMeasured ? DIM_STATUS_MEASURED : DIM_STATUS_NOT_MEASURED);
        dimensionStatus.put("cost", DIM_STATUS_MEASURED);

        String breakdownJson = buildBreakdownJson(
                latencyMs,
                costUsd,
                loopCount,
                toolCallCount,
                normalizedQuality,
                normalizedEfficiency,
                latencyScore,
                costScore,
                qualityFloorApplied,
                dimensionStatus
        );

        Double roundedLatency = latencyMeasured ? round(latencyScore) : null;

        return new Result(
                FORMULA_VERSION,
                round(normalizedQuality),
                round(normalizedEfficiency),
                roundedLatency,
                round(costScore),
                round(finalComposite),
                breakdownJson,
                Collections.unmodifiableMap(dimensionStatus)
        );
    }

    /**
     * Latency-specific score: returns {@code null} when the scenario does not
     * declare a {@code performanceThresholdMs} (i.e. cannot be measured), and
     * a 0–100 score otherwise. Distinct from the cost overload which keeps the
     * legacy "no threshold → 100" semantics.
     */
    static Double latencyScoreOrNull(Long observed, Long threshold) {
        if (threshold == null || threshold <= 0) {
            return null;
        }
        if (observed == null || observed < 0) {
            return 0.0;
        }
        if (observed <= threshold) {
            return 100.0;
        }
        if (observed >= threshold * 2) {
            return 0.0;
        }
        return 100.0 * ((threshold * 2.0) - observed) / threshold;
    }

    /**
     * Cost-side threshold normalisation. <b>Intentionally unchanged in M4_V2.</b>
     * Cost has a global fallback threshold ({@code 0.01 USD}) configured at the
     * orchestrator layer, so the "null threshold → 100" branch is unreachable
     * in production. Changing this path would silently shift cost scoring for
     * every legacy historical run.
     */
    static double normalizeThresholdMetric(BigDecimal observed, BigDecimal threshold) {
        if (observed == null || observed.signum() < 0) {
            return 0.0;
        }
        if (threshold == null || threshold.signum() <= 0) {
            return 100.0;
        }
        if (observed.compareTo(threshold) <= 0) {
            return 100.0;
        }
        BigDecimal doubledThreshold = threshold.multiply(BigDecimal.valueOf(2));
        if (observed.compareTo(doubledThreshold) >= 0) {
            return 0.0;
        }
        BigDecimal numerator = doubledThreshold.subtract(observed).multiply(BigDecimal.valueOf(100));
        return numerator.divide(threshold, 6, RoundingMode.HALF_UP).doubleValue();
    }

    private static double clampScore(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(100.0, value));
    }

    private static double round(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private static String buildBreakdownJson(Long latencyMs,
                                             BigDecimal costUsd,
                                             Integer loopCount,
                                             Integer toolCallCount,
                                             double qualityScore,
                                             double efficiencyScore,
                                             Double latencyScore,
                                             double costScore,
                                             boolean qualityFloorApplied,
                                             Map<String, String> dimensionStatus) {
        String costLiteral = costUsd == null ? "null" : costUsd.stripTrailingZeros().toPlainString();
        String latencyScoreLiteral = latencyScore == null ? "null" : Double.toString(round(latencyScore));

        StringBuilder dimStatusJson = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : dimensionStatus.entrySet()) {
            if (!first) {
                dimStatusJson.append(",");
            }
            dimStatusJson.append("\"").append(entry.getKey()).append("\":\"")
                    .append(entry.getValue()).append("\"");
            first = false;
        }
        dimStatusJson.append("}");

        return "{"
                + "\"formulaVersion\":\"" + FORMULA_VERSION + "\","
                + "\"weights\":{\"quality\":" + QUALITY_WEIGHT
                + ",\"efficiency\":" + EFFICIENCY_WEIGHT
                + ",\"latency\":" + LATENCY_WEIGHT
                + ",\"cost\":" + COST_WEIGHT + "},"
                + "\"raw\":{\"latencyMs\":" + (latencyMs == null ? "null" : latencyMs)
                + ",\"costUsd\":" + costLiteral
                + ",\"loopCount\":" + (loopCount == null ? "null" : loopCount)
                + ",\"toolCallCount\":" + (toolCallCount == null ? "null" : toolCallCount) + "},"
                + "\"scores\":{\"quality\":" + round(qualityScore)
                + ",\"efficiency\":" + round(efficiencyScore)
                + ",\"latency\":" + latencyScoreLiteral
                + ",\"cost\":" + round(costScore) + "},"
                + "\"caps\":{\"qualityFloorApplied\":" + qualityFloorApplied + "},"
                + "\"dimensionStatus\":" + dimStatusJson
                + "}";
    }

    /**
     * M4_V2: {@code latencyScore} is nullable — {@code null} encodes
     * "not_measured" (scenario lacked {@code performanceThresholdMs}).
     * {@code dimensionStatus} mirrors the same signal for each dimension to
     * keep UI rendering decoupled from null-checking the score field directly.
     */
    public record Result(
            String formulaVersion,
            double qualityScore,
            double efficiencyScore,
            Double latencyScore,
            double costScore,
            double compositeScore,
            String breakdownJson,
            Map<String, String> dimensionStatus
    ) {
    }
}
