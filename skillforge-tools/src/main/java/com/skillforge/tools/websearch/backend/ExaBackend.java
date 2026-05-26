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

public class ExaBackend implements WebSearchBackend {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final WebSearchConfig config;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    public ExaBackend(WebSearchConfig config, OkHttpClient httpClient, ObjectMapper objectMapper) {
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
            requestBodyMap.put("numResults", options.maxResults());
            requestBodyMap.put("contents", Map.of("highlights", true));
            putIfPresent(requestBodyMap, "startPublishedDate", options.exaStartPublishedDate());
            putIfPresent(requestBodyMap, "endPublishedDate", options.exaEndPublishedDate());
            putIfPresent(requestBodyMap, "type", options.searchType());
            putIfPresent(requestBodyMap, "category", options.topic());
            putIfPresent(requestBodyMap, "userLocation", options.userLocation());
            if (!options.includeDomains().isEmpty()) {
                requestBodyMap.put("includeDomains", options.includeDomains());
            }
            if (!options.excludeDomains().isEmpty()) {
                requestBodyMap.put("excludeDomains", options.excludeDomains());
            }
            String bodyJson = objectMapper.writeValueAsString(requestBodyMap);
            Request request = new Request.Builder()
                    .url(config.exaSearchUrl())
                    .header("x-api-key", config.exaApiKey())
                    .post(RequestBody.create(bodyJson, JSON))
                    .build();
            try (Response response = httpClient.newCall(request).execute()) {
                ResponseBody responseBodyObj = response.body();
                String responseBody = responseBodyObj == null ? "" : responseBodyObj.string();
                if (!response.isSuccessful()) {
                    throw new WebSearchException("Exa returned HTTP " + response.code());
                }
                return new SearchResult(parseHits(responseBody, options.maxResults()), name().wireName(),
                        Duration.between(start, Instant.now()));
            }
        } catch (WebSearchException e) {
            throw e;
        } catch (Exception e) {
            throw new WebSearchException("Exa search failed: " + e.getMessage(), e);
        }
    }

    @Override
    public WebSearchBackendName name() {
        return WebSearchBackendName.EXA;
    }

    @Override
    public boolean isConfigured() {
        return config.hasExaApiKey();
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
            String snippet = highlightSnippet(map.get("highlights"));
            if (snippet.isBlank()) {
                snippet = firstNonBlank(stringValue(map.get("text")), stringValue(map.get("summary")));
            }
            String publishedDate = stringValue(map.get("publishedDate"));
            if (!title.isBlank() && !url.isBlank()) {
                hits.add(new SearchHit(title, url, snippet, hits.size() + 1,
                        blankToNull(publishedDate), null));
            }
        }
        return hits;
    }

    private static void putIfPresent(Map<String, Object> body, String key, String value) {
        if (value != null && !value.isBlank()) {
            body.put(key, value);
        }
    }

    private static String highlightSnippet(Object highlights) {
        if (!(highlights instanceof List<?> list) || list.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (Object item : list) {
            if (item != null && !item.toString().isBlank()) {
                parts.add(item.toString());
            }
        }
        return String.join(" … ", parts);
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
}
