package com.skillforge.server.optreport.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * OPT-REPORT-V1.2 (2026-05-23): structured view of
 * {@code t_opt_report.summary_json}.
 *
 * <p>V1.2 only fixes the {@code topIssues} array shape (see
 * {@link OptReportIssueDto} javadoc + V102 migration). Other outer
 * fields ({@code totalSessions} / {@code successCount} / etc.) remain
 * free-form because they're consumed by
 * {@code OptReportService.extractSummaryHighlight} via untyped
 * {@code JsonNode} reads — no parser dependency.
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)} discards every
 * non-topIssues field; callers wanting the outer summary still go
 * through {@code OptReportEntity.getSummaryJson()} raw string.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OptReportSummaryJson(
        List<OptReportIssueDto> topIssues
) {
}
