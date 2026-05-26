package com.skillforge.tools.websearch;

import java.util.Locale;

public enum WebSearchBackendName {
    TAVILY("tavily"),
    EXA("exa"),
    DUCKDUCKGO("duckduckgo");

    private final String wireName;

    WebSearchBackendName(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static WebSearchBackendName fromString(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("backend name is required");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace("_", "-");
        return switch (normalized) {
            case "tavily" -> TAVILY;
            case "exa" -> EXA;
            case "duckduckgo", "duck-duck-go", "ddg", "ddg-html" -> DUCKDUCKGO;
            default -> throw new IllegalArgumentException("Unsupported web search backend: " + value);
        };
    }
}
