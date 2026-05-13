package com.skillforge.server.security.skill;

public class SkillSecurityException extends RuntimeException {

    private final SkillScanResult scanResult;

    private SkillSecurityException(String message, SkillScanResult scanResult) {
        super(message);
        this.scanResult = scanResult;
    }

    public static SkillSecurityException blocked(SkillScanResult scanResult, boolean mediumOverrideAvailable) {
        return new SkillSecurityException(formatMessage(scanResult, mediumOverrideAvailable), scanResult);
    }

    public SkillScanResult getScanResult() {
        return scanResult;
    }

    private static String formatMessage(SkillScanResult result, boolean mediumOverrideAvailable) {
        StringBuilder sb = new StringBuilder();
        if (result.highestSeverity() == SkillScanSeverity.MEDIUM && mediumOverrideAvailable) {
            sb.append("Skill import requires explicit approval due to medium-risk findings.\n\n");
        } else {
            sb.append("Skill import blocked by security scan.\n\n");
        }
        sb.append("Decision: ").append(result.decision()).append('\n');
        sb.append("Highest severity: ").append(result.highestSeverity()).append("\n\n");
        sb.append("Findings:\n");
        for (SkillScanFinding finding : result.findings()) {
            sb.append("- [").append(finding.severity()).append("] ")
                    .append(finding.ruleId()).append(" at ")
                    .append(finding.file()).append(':').append(finding.line()).append('\n')
                    .append("  ").append(finding.message()).append('\n');
            if (finding.excerpt() != null && !finding.excerpt().isBlank()) {
                sb.append("  Excerpt: ").append(finding.excerpt()).append('\n');
            }
        }
        sb.append('\n').append("The skill was not imported, copied, persisted, or registered.");
        if (result.highestSeverity() == SkillScanSeverity.MEDIUM && mediumOverrideAvailable) {
            sb.append(" If the user accepts the risk, retry ImportSkill with allowMediumRisk=true.");
        }
        return sb.toString();
    }
}
