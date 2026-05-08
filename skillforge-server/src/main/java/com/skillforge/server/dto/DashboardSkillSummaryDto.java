package com.skillforge.server.dto;

/**
 * SKILL-DASHBOARD-POLISH-V2 §G — aggregated skill metrics for the dashboard
 * SkillSummaryCard.
 *
 * @param autoUpgradedThisWeek    count of system-triggered (triggered_by_user_id=0)
 *                                AB runs with {@code promoted=true} created in the last 7 days
 * @param pendingDraftsCount      drafts with {@code status='draft'} for this owner
 * @param failedEvolveThisWeek    {@code SkillEvolutionRun} with {@code status='FAILED'}
 *                                for skills owned by this user, created in the last 7 days
 * @param totalEnabledSkills      enabled, non-system skills owned by this user
 * @param lowScoreSkillsCount     enabled skills whose latest eval-history composite_score &lt; 60
 */
public record DashboardSkillSummaryDto(
        long autoUpgradedThisWeek,
        long pendingDraftsCount,
        long failedEvolveThisWeek,
        long totalEnabledSkills,
        long lowScoreSkillsCount
) {
}
