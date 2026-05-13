package com.skillforge.server.security.skill;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "skillforge.skill-security-scan")
public class SkillSecurityScanProperties {

    private boolean enabled = true;
    private boolean allowMediumRiskByDefault = false;
    private long maxFileBytes = 262_144;
    private long maxTotalBytes = 1_048_576;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isAllowMediumRiskByDefault() {
        return allowMediumRiskByDefault;
    }

    public void setAllowMediumRiskByDefault(boolean allowMediumRiskByDefault) {
        this.allowMediumRiskByDefault = allowMediumRiskByDefault;
    }

    public long getMaxFileBytes() {
        return maxFileBytes;
    }

    public void setMaxFileBytes(long maxFileBytes) {
        this.maxFileBytes = maxFileBytes;
    }

    public long getMaxTotalBytes() {
        return maxTotalBytes;
    }

    public void setMaxTotalBytes(long maxTotalBytes) {
        this.maxTotalBytes = maxTotalBytes;
    }
}
