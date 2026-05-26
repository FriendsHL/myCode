package com.skillforge.tools.websearch;

import java.util.List;

public record WebSearchConfig(
        List<WebSearchBackendName> backendPriority,
        String tavilyApiKey,
        String exaApiKey,
        String tavilySearchUrl,
        String exaSearchUrl,
        String duckDuckGoSearchUrl
) {
    private static final String TAVILY_DEFAULT_URL = "https://api.tavily.com/search";
    private static final String EXA_DEFAULT_URL = "https://api.exa.ai/search";
    private static final String DUCKDUCKGO_DEFAULT_URL = "https://html.duckduckgo.com/html/";

    public static WebSearchConfig defaultsFromEnv() {
        return new WebSearchConfig(
                List.of(WebSearchBackendName.TAVILY, WebSearchBackendName.EXA, WebSearchBackendName.DUCKDUCKGO),
                System.getenv("TAVILY_API_KEY"),
                System.getenv("EXA_API_KEY"),
                TAVILY_DEFAULT_URL,
                EXA_DEFAULT_URL,
                DUCKDUCKGO_DEFAULT_URL);
    }

    public static WebSearchConfig fromPriorityCsv(String backendPriority,
                                                   String tavilyApiKey,
                                                   String exaApiKey) {
        return new WebSearchConfig(
                parseBackendPriority(backendPriority),
                tavilyApiKey,
                exaApiKey,
                TAVILY_DEFAULT_URL,
                EXA_DEFAULT_URL,
                DUCKDUCKGO_DEFAULT_URL);
    }

    public static WebSearchConfig fromBackendPriorityNames(List<String> backendPriority,
                                                            String tavilyApiKey,
                                                            String exaApiKey) {
        return new WebSearchConfig(
                parseBackendPriority(backendPriority),
                tavilyApiKey,
                exaApiKey,
                TAVILY_DEFAULT_URL,
                EXA_DEFAULT_URL,
                DUCKDUCKGO_DEFAULT_URL);
    }

    private static List<WebSearchBackendName> parseBackendPriority(String backendPriority) {
        if (backendPriority == null || backendPriority.isBlank()) {
            return List.of(WebSearchBackendName.TAVILY, WebSearchBackendName.EXA, WebSearchBackendName.DUCKDUCKGO);
        }
        return java.util.Arrays.stream(backendPriority.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(WebSearchBackendName::fromString)
                .distinct()
                .toList();
    }

    private static List<WebSearchBackendName> parseBackendPriority(List<String> backendPriority) {
        if (backendPriority == null || backendPriority.isEmpty()) {
            return List.of(WebSearchBackendName.TAVILY, WebSearchBackendName.EXA, WebSearchBackendName.DUCKDUCKGO);
        }
        return backendPriority.stream()
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(WebSearchBackendName::fromString)
                .distinct()
                .toList();
    }

    public WebSearchConfig {
        backendPriority = backendPriority == null || backendPriority.isEmpty()
                ? List.of(WebSearchBackendName.TAVILY, WebSearchBackendName.EXA, WebSearchBackendName.DUCKDUCKGO)
                : List.copyOf(backendPriority);
        tavilyApiKey = normalize(tavilyApiKey);
        exaApiKey = normalize(exaApiKey);
        tavilySearchUrl = defaultIfBlank(tavilySearchUrl, TAVILY_DEFAULT_URL);
        exaSearchUrl = defaultIfBlank(exaSearchUrl, EXA_DEFAULT_URL);
        duckDuckGoSearchUrl = defaultIfBlank(duckDuckGoSearchUrl, DUCKDUCKGO_DEFAULT_URL);
    }

    public boolean hasTavilyApiKey() {
        return tavilyApiKey != null && !tavilyApiKey.isBlank();
    }

    public boolean hasExaApiKey() {
        return exaApiKey != null && !exaApiKey.isBlank();
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
