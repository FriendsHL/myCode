package com.skillforge.server.security.skill;

import java.util.List;

public record SkillScanResult(
        SkillScanDecision decision,
        SkillScanSeverity highestSeverity,
        List<SkillScanFinding> findings) {

    public SkillScanResult {
        findings = findings == null ? List.of() : List.copyOf(findings);
    }

    public static SkillScanResult allow() {
        return new SkillScanResult(SkillScanDecision.ALLOW, null, List.of());
    }
}
