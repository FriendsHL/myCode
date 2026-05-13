package com.skillforge.server.security.skill;

public record SkillScanFinding(
        SkillScanSeverity severity,
        String ruleId,
        String file,
        int line,
        String message,
        String excerpt) {
}
