package com.skillforge.server.optreport.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * OPT-REPORT-V1.2 (2026-05-23): one structured issue extracted from
 * {@code t_opt_report.summary_json.topIssues[i]}.
 *
 * <p>V1.0/V1.1 left {@code topIssues} free-form prose. V1.2 fixes the
 * schema (see V102 migration) so:
 * <ul>
 *   <li>FE can render a "Convert to Event" button per issue (idempotency
 *       keyed on {@link #id}).</li>
 *   <li>{@code OptReportToEventBridge.convertIssueToEvent} can pluck the
 *       fields it needs to build a new {@code t_optimization_event} row.</li>
 * </ul>
 *
 * <p>Validation lives in {@link OptReportSummaryParser} — not in the
 * record itself — so the LLM's raw output can be inspected by the
 * parser before any {@code new OptReportIssueDto(...)} is constructed.
 * That keeps the DTO a plain transport object (suits {@code Record} +
 * Jackson) and avoids the "constructor throws from inside readValue"
 * footgun where Jackson rethrows as {@code MismatchedInputException}.
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)}: defensive for
 * forward-compat — older callers might serialize with extra fields the
 * LLM started adding (e.g. {@code "topFailingTool"}); we don't want
 * round-tripping to fail.
 *
 * @param id                 Stable id, conventionally {@code "issue-1"} / {@code "issue-2"} /...
 *                           Required, non-blank.
 * @param title              Human-readable headline. Required, non-blank.
 * @param severity           One of {@code "high"} / {@code "medium"} / {@code "low"}.
 * @param sessionCount       Count of sessions the LLM identified as exhibiting
 *                           this issue. Required, ≥ 1.
 * @param exampleSessionIds  At least one real {@code t_session.id}. Required, non-empty.
 * @param suspectSurface     **根因 surface** — agent 出错时 *在做什么 surface*。
 *                           One of {@code "skill"} / {@code "prompt"} /
 *                           {@code "behavior_rule"} / {@code "other"} /
 *                           {@code "unclear"}.
 * @param fixSurface         V1.3+: **修复落点 surface** — 修这个 issue 应该
 *                           改哪个 surface。可以跟 {@link #suspectSurface}
 *                           不同（例如：agent 调 Bash 反复失败 → 根因 surface=skill，
 *                           但修复在 behavior_rule 层加"连续 N 次同款失败后停"
 *                           的规则）。Optional — 旧报告或 LLM 没区分时为 null，
 *                           下游 (bridge + FE) 自动 fallback 到 suspectSurface。
 * @param confidence         Self-rated probability ∈ [0.0, 1.0].
 * @param suggestion         One-line improvement direction. Required, non-blank.
 * @param expectedImpact     Optional, may be null.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OptReportIssueDto(
        String id,
        String title,
        String severity,
        int sessionCount,
        List<String> exampleSessionIds,
        String suspectSurface,
        String fixSurface,
        double confidence,
        String suggestion,
        String expectedImpact
) {
    /**
     * V1.3+ accessor: returns {@link #fixSurface} when set, else falls back
     * to {@link #suspectSurface}. Callers (bridge / enrichTopIssues) should
     * use this rather than reading {@code fixSurface()} directly.
     */
    public String effectiveSurface() {
        return (fixSurface != null && !fixSurface.isBlank()) ? fixSurface : suspectSurface;
    }
    // Allowed enum vocabularies — public so OptReportSummaryParser /
    // OptReportToEventBridge / tests can share the same definition.
    public static final java.util.Set<String> SEVERITIES =
            java.util.Set.of("high", "medium", "low");

    public static final java.util.Set<String> SURFACES =
            java.util.Set.of("skill", "prompt", "behavior_rule", "other", "unclear");

    /**
     * Surfaces that {@code OptReportToEventBridge.convertIssueToEvent} will
     * accept. {@code "other"} / {@code "unclear"} are rejected with a 400
     * — operator should write a manual OptEvent for those rather than
     * pretending the report had a surface call.
     */
    public static final java.util.Set<String> CONVERTIBLE_SURFACES =
            java.util.Set.of("skill", "prompt", "behavior_rule");
}
