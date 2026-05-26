package com.skillforge.tools.websearch.backend;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.tools.websearch.SearchHit;
import com.skillforge.tools.websearch.SearchOptions;
import com.skillforge.tools.websearch.SearchResult;
import com.skillforge.tools.websearch.WebSearchBackend;
import com.skillforge.tools.websearch.WebSearchBackendName;
import com.skillforge.tools.websearch.WebSearchConfig;
import com.skillforge.tools.websearch.WebSearchException;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TavilyBackend implements WebSearchBackend {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final WebSearchConfig config;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    public TavilyBackend(WebSearchConfig config, OkHttpClient httpClient, ObjectMapper objectMapper) {
        this.config = config;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public SearchResult search(String query, SearchOptions options) throws WebSearchException {
        Instant start = Instant.now();
        try {
            Map<String, Object> requestBodyMap = new LinkedHashMap<>();
            requestBodyMap.put("query", query);
            requestBodyMap.put("max_results", options.maxResults());
            requestBodyMap.put("search_depth", options.searchDepth() == null ? "basic" : options.searchDepth());
            putIfPresent(requestBodyMap, "topic", options.topic());
            putIfPresent(requestBodyMap, "time_range", options.tavilyTimeRange());
            putIfPresent(requestBodyMap, "start_date", options.startDate());
            putIfPresent(requestBodyMap, "end_date", options.endDate());
            putIfPresent(requestBodyMap, "country", options.tavilyCountry());
            if (!options.includeDomains().isEmpty()) {
                requestBodyMap.put("include_domains", options.includeDomains());
            }
            if (!options.excludeDomains().isEmpty()) {
                requestBodyMap.put("exclude_domains", options.excludeDomains());
            }
            String bodyJson = objectMapper.writeValueAsString(requestBodyMap);
            Request request = new Request.Builder()
                    .url(config.tavilySearchUrl())
                    .header("Authorization", "Bearer " + config.tavilyApiKey())
                    .post(RequestBody.create(bodyJson, JSON))
                    .build();
            try (Response response = httpClient.newCall(request).execute()) {
                ResponseBody responseBodyObj = response.body();
                String responseBody = responseBodyObj == null ? "" : responseBodyObj.string();
                if (!response.isSuccessful()) {
                    throw new WebSearchException("Tavily returned HTTP " + response.code());
                }
                return new SearchResult(parseHits(responseBody, options.maxResults()), name().wireName(),
                        Duration.between(start, Instant.now()));
            }
        } catch (WebSearchException e) {
            throw e;
        } catch (Exception e) {
            throw new WebSearchException("Tavily search failed: " + e.getMessage(), e);
        }
    }

    @Override
    public WebSearchBackendName name() {
        return WebSearchBackendName.TAVILY;
    }

    @Override
    public boolean isConfigured() {
        return config.hasTavilyApiKey();
    }

    private List<SearchHit> parseHits(String json, int maxResults) throws Exception {
        Map<String, Object> root = objectMapper.readValue(json, new TypeReference<>() {});
        Object rawResults = root.get("results");
        if (!(rawResults instanceof List<?> rows)) {
            return List.of();
        }
        List<SearchHit> hits = new ArrayList<>();
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> map)) continue;
            if (hits.size() >= maxResults) break;
            String title = stringValue(map.get("title"));
            String url = stringValue(map.get("url"));
            String snippet = firstNonBlank(stringValue(map.get("content")), stringValue(map.get("snippet")));
            String publishedDate = stringValue(map.get("published_date"));
            Double score = doubleValue(map.get("score"));
            if (!title.isBlank() && !url.isBlank()) {
                hits.add(new SearchHit(title, url, snippet, hits.size() + 1,
                        blankToNull(publishedDate), score));
            }
        }
        return hits;
    }

    private static void putIfPresent(Map<String, Object> body, String key, String value) {
        if (value != null && !value.isBlank()) {
            body.put(key, value);
        }
    }

    private static String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? (second == null ? "" : second) : first;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static Double doubleValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
