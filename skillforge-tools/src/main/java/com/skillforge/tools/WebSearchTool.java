package com.skillforge.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import com.skillforge.tools.websearch.SearchHit;
import com.skillforge.tools.websearch.SearchOptions;
import com.skillforge.tools.websearch.SearchResult;
import com.skillforge.tools.websearch.WebSearchBackend;
import com.skillforge.tools.websearch.WebSearchBackendName;
import com.skillforge.tools.websearch.WebSearchConfig;
import com.skillforge.tools.websearch.WebSearchException;
import com.skillforge.tools.websearch.backend.DuckDuckGoHtmlBackend;
import com.skillforge.tools.websearch.backend.ExaBackend;
import com.skillforge.tools.websearch.backend.TavilyBackend;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Tool that searches the web and returns results from a configured priority chain.
 */
public class WebSearchTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(WebSearchTool.class);
    private static final int DEFAULT_MAX_RESULTS = 5;

    private final WebSearchConfig config;
    private final Map<WebSearchBackendName, WebSearchBackend> backends;
    private final ObjectMapper objectMapper;

    public WebSearchTool() {
        this(WebSearchConfig.defaultsFromEnv(), defaultHttpClient());
    }

    public WebSearchTool(WebSearchConfig config) {
        this(config, defaultHttpClient());
    }

    public WebSearchTool(WebSearchConfig config, OkHttpClient httpClient) {
        this.config = config;
        this.objectMapper = new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(SerializationFeature.INDENT_OUTPUT);
        this.backends = new EnumMap<>(WebSearchBackendName.class);
        backends.put(WebSearchBackendName.TAVILY, new TavilyBackend(config, httpClient, objectMapper));
        backends.put(WebSearchBackendName.EXA, new ExaBackend(config, httpClient, objectMapper));
        backends.put(WebSearchBackendName.DUCKDUCKGO, new DuckDuckGoHtmlBackend(config, httpClient));
    }

    private static OkHttpClient defaultHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build();
    }

    @Override
    public String getName() {
        return "WebSearch";
    }

    @Override
    public String getDescription() {
        return "Search the web and return titles, URLs, snippets, and the backend used. "
                + "Supports optional backend override, domain/date/topic filters, and JSON output.";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", Map.of(
                "type", "string",
                "description", "Search query"
        ));
        properties.put("maxResults", Map.of(
                "type", "integer",
                "description", "Maximum number of results to return (default 5)"
        ));
        properties.put("backend", Map.of(
                "type", "string",
                "enum", List.of("auto", "tavily", "exa", "duckduckgo"),
                "description", "Search backend to force, or auto for configured priority chain"
        ));
        properties.put("include_domains", Map.of(
                "type", "array",
                "items", Map.of("type", "string"),
                "description", "Only return results from these domains or subdomains"
        ));
        properties.put("exclude_domains", Map.of(
                "type", "array",
                "items", Map.of("type", "string"),
                "description", "Exclude results from these domains or subdomains"
        ));
        properties.put("time_range", Map.of(
                "type", "string",
                "enum", List.of("day", "week", "month", "year", "d", "w", "m", "y"),
                "description", "Relative publish-date freshness filter; Tavily native, Exa converted to startPublishedDate"
        ));
        properties.put("start_date", Map.of(
                "type", "string",
                "description", "Start publish date in YYYY-MM-DD format; Tavily start_date, Exa startPublishedDate"
        ));
        properties.put("end_date", Map.of(
                "type", "string",
                "description", "End publish date in YYYY-MM-DD format; Tavily end_date, Exa endPublishedDate"
        ));
        properties.put("topic", Map.of(
                "type", "string",
                "description", "Topic/category hint. Tavily supports general/news/finance; Exa maps this to category"
        ));
        properties.put("search_depth", Map.of(
                "type", "string",
                "enum", List.of("basic", "advanced"),
                "description", "Tavily search depth. Defaults to basic"
        ));
        properties.put("search_type", Map.of(
                "type", "string",
                "enum", List.of("instant", "fast", "auto", "deep-lite", "deep", "deep-reasoning"),
                "description", "Exa search type. Tavily and DuckDuckGo ignore this"
        ));
        properties.put("country", Map.of(
                "type", "string",
                "description", "Tavily country boost for general search, for example 'united states'"
        ));
        properties.put("user_location", Map.of(
                "type", "string",
                "description", "Exa two-letter user location country code, for example US"
        ));
        properties.put("output_format", Map.of(
                "type", "string",
                "enum", List.of("text", "json"),
                "description", "Return human-readable text or a JSON object string (default text)"
        ));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("query"));

        return new ToolSchema(getName(), getDescription(), schema);
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        try {
            String query = (String) input.get("query");
            if (query == null || query.isBlank()) {
                return SkillResult.error("query is required");
            }

            int maxResults = DEFAULT_MAX_RESULTS;
            Object maxResultsObj = input.get("maxResults");
            if (maxResultsObj instanceof Number) {
                maxResults = Math.max(1, ((Number) maxResultsObj).intValue());
            }
            OutputFormat outputFormat = OutputFormat.fromInput(stringInput(input, "output_format", "outputFormat"));
            WebSearchBackendName forcedBackend = forcedBackend(input);
            List<String> includeDomains = domainsInput(input, "include_domains", "includeDomains");
            List<String> excludeDomains = domainsInput(input, "exclude_domains", "excludeDomains");
            SearchOptions options = new SearchOptions(
                    maxResults,
                    includeDomains,
                    excludeDomains,
                    stringInput(input, "time_range", "timeRange"),
                    stringInput(input, "start_date", "startDate"),
                    stringInput(input, "end_date", "endDate"),
                    stringInput(input, "topic", "topic"),
                    stringInput(input, "search_depth", "searchDepth"),
                    stringInput(input, "search_type", "searchType"),
                    stringInput(input, "country", "country"),
                    stringInput(input, "user_location", "userLocation"));

            SearchResult result = applyDomainFilters(
                    search(query, options, forcedBackend), includeDomains, excludeDomains);
            if (outputFormat == OutputFormat.JSON) {
                return SkillResult.success(formatJson(query, result, includeDomains, excludeDomains));
            }
            if (result.hits().isEmpty()) {
                return SkillResult.success("Backend: " + result.backendName()
                        + "\n\nNo search results found for: \"" + query + "\"");
            }
            return SkillResult.success(formatResults(query, result));
        } catch (Exception e) {
            return SkillResult.error("Web search failed: " + e.getMessage());
        }
    }

    private SearchResult search(String query, SearchOptions options, WebSearchBackendName forcedBackend)
            throws WebSearchException {
        if (forcedBackend != null) {
            WebSearchBackend backend = backends.get(forcedBackend);
            if (backend == null) {
                throw new WebSearchException("Unsupported web search backend: " + forcedBackend.wireName());
            }
            if (!backend.isConfigured()) {
                throw new WebSearchException("WebSearch backend " + forcedBackend.wireName()
                        + " is not configured");
            }
            return backend.search(query, options);
        }
        return searchWithPriority(query, options);
    }

    private SearchResult searchWithPriority(String query, SearchOptions options) throws WebSearchException {
        WebSearchException lastError = null;
        for (WebSearchBackendName name : config.backendPriority()) {
            WebSearchBackend backend = backends.get(name);
            if (backend == null) {
                continue;
            }
            if (!backend.isConfigured()) {
                log.warn("WebSearch backend={} skipped because required API key is missing", name.wireName());
                continue;
            }
            try {
                return backend.search(query, options);
            } catch (WebSearchException e) {
                lastError = e;
                log.warn("WebSearch backend={} failed, trying next backend: {}", name.wireName(), e.getMessage());
            }
        }
        if (lastError != null) {
            throw lastError;
        }
        throw new WebSearchException("No configured web search backend is available");
    }

    private SearchResult applyDomainFilters(SearchResult result, List<String> includeDomains,
                                            List<String> excludeDomains) {
        if (includeDomains.isEmpty() && excludeDomains.isEmpty()) {
            return result;
        }
        List<SearchHit> filtered = new ArrayList<>();
        for (SearchHit hit : result.hits()) {
            String host = hostOf(hit.url());
            if (host.isBlank()) {
                continue;
            }
            boolean included = includeDomains.isEmpty()
                    || includeDomains.stream().anyMatch(domain -> hostMatches(host, domain));
            boolean excluded = excludeDomains.stream().anyMatch(domain -> hostMatches(host, domain));
            if (included && !excluded) {
                filtered.add(new SearchHit(hit.title(), hit.url(), hit.snippet(), filtered.size() + 1,
                        hit.publishedDate(), hit.score()));
            }
        }
        return new SearchResult(filtered, result.backendName(), result.latency());
    }

    private String formatResults(String query, SearchResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("Backend: ").append(result.backendName()).append("\n");
        sb.append("Web search results for: \"").append(query).append("\"\n\n");

        for (SearchHit hit : result.hits()) {
            sb.append(hit.rank()).append(". [").append(hit.title()).append("](").append(hit.url()).append(")\n");
            if (hit.snippet() != null && !hit.snippet().isBlank()) {
                sb.append("   ").append(hit.snippet()).append("\n");
            }
            sb.append("\n");
        }

        return sb.toString().trim();
    }

    private String formatJson(String query, SearchResult result, List<String> includeDomains,
                              List<String> excludeDomains) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("query", query);
        payload.put("backend", result.backendName());
        payload.put("latencyMs", result.latency() == null ? null : result.latency().toMillis());
        if (!includeDomains.isEmpty()) {
            payload.put("includeDomains", includeDomains);
        }
        if (!excludeDomains.isEmpty()) {
            payload.put("excludeDomains", excludeDomains);
        }

        List<Map<String, Object>> results = new ArrayList<>();
        for (SearchHit hit : result.hits()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rank", hit.rank());
            row.put("title", hit.title());
            row.put("url", hit.url());
            row.put("snippet", hit.snippet());
            if (hit.publishedDate() != null) {
                row.put("publishedDate", hit.publishedDate());
            }
            if (hit.score() != null) {
                row.put("score", hit.score());
            }
            results.add(row);
        }
        payload.put("results", results);
        return objectMapper.writeValueAsString(payload);
    }

    private static WebSearchBackendName forcedBackend(Map<String, Object> input) {
        String value = stringInput(input, "backend", "backend");
        if (value == null || value.isBlank() || "auto".equalsIgnoreCase(value.trim())) {
            return null;
        }
        return WebSearchBackendName.fromString(value);
    }

    private static List<String> domainsInput(Map<String, Object> input, String snakeKey, String camelKey) {
        Object value = input.get(snakeKey);
        if (value == null) {
            value = input.get(camelKey);
        }
        if (value == null) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                addDomain(values, item);
            }
        } else if (value instanceof String text) {
            for (String part : text.split(",")) {
                addDomain(values, part);
            }
        } else {
            addDomain(values, value);
        }
        return List.copyOf(values);
    }

    private static void addDomain(List<String> values, Object rawValue) {
        if (rawValue == null) {
            return;
        }
        String normalized = normalizeDomain(rawValue.toString());
        if (!normalized.isBlank() && !values.contains(normalized)) {
            values.add(normalized);
        }
    }

    private static String normalizeDomain(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return "";
        }
        String value = rawValue.trim().toLowerCase(Locale.ROOT);
        HttpUrl parsed = value.contains("://")
                ? HttpUrl.parse(value)
                : HttpUrl.parse("https://" + value);
        if (parsed != null) {
            return parsed.host();
        }
        int slash = value.indexOf('/');
        if (slash >= 0) {
            value = value.substring(0, slash);
        }
        int colon = value.indexOf(':');
        if (colon >= 0) {
            value = value.substring(0, colon);
        }
        return value;
    }

    private static String hostOf(String url) {
        HttpUrl parsed = HttpUrl.parse(url);
        return parsed == null ? "" : parsed.host().toLowerCase(Locale.ROOT);
    }

    private static boolean hostMatches(String host, String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }

    private static String stringInput(Map<String, Object> input, String snakeKey, String camelKey) {
        Object value = input.get(snakeKey);
        if (value == null) {
            value = input.get(camelKey);
        }
        return value == null ? null : value.toString();
    }

    private enum OutputFormat {
        TEXT,
        JSON;

        static OutputFormat fromInput(String value) {
            if (value == null || value.isBlank()) {
                return TEXT;
            }
            return "json".equals(value.trim().toLowerCase(Locale.ROOT)) ? JSON : TEXT;
        }
    }
}
