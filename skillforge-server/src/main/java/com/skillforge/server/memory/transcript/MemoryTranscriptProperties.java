package com.skillforge.server.memory.transcript;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "skillforge.memory.transcript")
public class MemoryTranscriptProperties {

    private int defaultLookbackDays = 7;
    private int defaultMaxSessions = 5;
    private int defaultMaxCharsPerSession = 6000;
    private int maxLookbackDays = 30;
    private int maxSessions = 20;
    private int maxCharsPerSession = 12000;

    public int getDefaultLookbackDays() {
        return defaultLookbackDays;
    }

    public void setDefaultLookbackDays(int defaultLookbackDays) {
        this.defaultLookbackDays = defaultLookbackDays;
    }

    public int getDefaultMaxSessions() {
        return defaultMaxSessions;
    }

    public void setDefaultMaxSessions(int defaultMaxSessions) {
        this.defaultMaxSessions = defaultMaxSessions;
    }

    public int getDefaultMaxCharsPerSession() {
        return defaultMaxCharsPerSession;
    }

    public void setDefaultMaxCharsPerSession(int defaultMaxCharsPerSession) {
        this.defaultMaxCharsPerSession = defaultMaxCharsPerSession;
    }

    public int getMaxLookbackDays() {
        return maxLookbackDays;
    }

    public void setMaxLookbackDays(int maxLookbackDays) {
        this.maxLookbackDays = maxLookbackDays;
    }

    public int getMaxSessions() {
        return maxSessions;
    }

    public void setMaxSessions(int maxSessions) {
        this.maxSessions = maxSessions;
    }

    public int getMaxCharsPerSession() {
        return maxCharsPerSession;
    }

    public void setMaxCharsPerSession(int maxCharsPerSession) {
        this.maxCharsPerSession = maxCharsPerSession;
    }
}
