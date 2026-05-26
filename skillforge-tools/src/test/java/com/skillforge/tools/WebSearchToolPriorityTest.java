package com.skillforge.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.tools.websearch.WebSearchBackendName;
import com.skillforge.tools.websearch.WebSearchConfig;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class WebSearchToolPriorityTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private MockWebServer tavily;
    private MockWebServer exa;
    private MockWebServer duck;

    @AfterEach
    void tearDown() throws Exception {
        if (tavily != null) tavily.shutdown();
        if (exa != null) exa.shutdown();
        if (duck != null) duck.shutdown();
    }

    @Test
    @DisplayName("priority chain uses Tavily first when Tavily API key is configured")
    void execute_tavilyConfigured_usesTavilyFirst() throws Exception {
        tavily = new MockWebServer();
        tavily.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"results":[{"title":"Tavily title","url":"https://t.example","content":"Tavily snippet"}]}
                        """));
        tavily.start();

        WebSearchTool tool = new WebSearchTool(config(
                List.of(WebSearchBackendName.TAVILY, WebSearchBackendName.EXA, WebSearchBackendName.DUCKDUCKGO),
                "tavily-key",
                "exa-key",
                tavily.url("/search").toString(),
                "http://127.0.0.1:1/search",
                "http://127.0.0.1:1/html/"), httpClient());

        SkillResult result = tool.execute(Map.of("query", "agent search"), null);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput())
                .contains("Backend: tavily")
                .contains("[Tavily title](https://t.example)")
                .contains("Tavily snippet");
        assertThat(tavily.takeRequest(1, TimeUnit.SECONDS).getHeader("Authorization"))
                .isEqualTo("Bearer tavily-key");
    }

    @Test
    @DisplayName("priority chain falls back to Exa when Tavily key is absent")
    void execute_tavilyMissing_fallsBackToExa() throws Exception {
        exa = new MockWebServer();
        exa.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"results":[{"title":"Exa title","url":"https://e.example","highlights":["Exa highlight"]}]}
                        """));
        exa.start();

        WebSearchTool tool = new WebSearchTool(config(
                List.of(WebSearchBackendName.TAVILY, WebSearchBackendName.EXA, WebSearchBackendName.DUCKDUCKGO),
                "",
                "exa-key",
                "http://127.0.0.1:1/search",
                exa.url("/search").toString(),
                "http://127.0.0.1:1/html/"), httpClient());

        SkillResult result = tool.execute(Map.of("query", "agent search"), null);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput())
                .contains("Backend: exa")
                .contains("[Exa title](https://e.example)")
                .contains("Exa highlight");
        assertThat(exa.takeRequest(1, TimeUnit.SECONDS).getHeader("x-api-key"))
                .isEqualTo("exa-key");
    }

    @Test
    @DisplayName("priority chain falls back to DuckDuckGo HTML when API backends are unavailable")
    void execute_apiBackendsUnavailable_fallsBackToDuckDuckGo() throws Exception {
        duck = new MockWebServer();
        duck.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/html")
                .setBody("""
                        <html><body>
                          <div class="result__body">
                            <a class="result__a" href="https://d.example">Duck title</a>
                            <a class="result__snippet">Duck snippet</a>
                            <span class="result__url">d.example</span>
                          </div>
                        </body></html>
                        """));
        duck.start();

        WebSearchTool tool = new WebSearchTool(config(
                List.of(WebSearchBackendName.TAVILY, WebSearchBackendName.EXA, WebSearchBackendName.DUCKDUCKGO),
                "",
                "",
                "http://127.0.0.1:1/search",
                "http://127.0.0.1:1/search",
                duck.url("/html/").toString()), httpClient());

        SkillResult result = tool.execute(Map.of("query", "agent search"), null);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput())
                .contains("Backend: duckduckgo")
                .contains("[Duck title](https://d.example)")
                .contains("Duck snippet");
    }

    @Test
    @DisplayName("backend override, domain filters, and JSON output are applied after provider search")
    @SuppressWarnings("unchecked")
    void execute_backendOverrideWithJsonOutput_returnsStructuredFilteredResults() throws Exception {
        exa = new MockWebServer();
        exa.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"results":[
                          {"title":"Allowed result","url":"https://docs.allowed.example/page","highlights":["Allowed highlight"]},
                          {"title":"Blocked result","url":"https://blocked.example/page","highlights":["Blocked highlight"]}
                        ]}
                        """));
        exa.start();

        tavily = new MockWebServer();
        tavily.start();

        WebSearchTool tool = new WebSearchTool(config(
                List.of(WebSearchBackendName.TAVILY, WebSearchBackendName.EXA, WebSearchBackendName.DUCKDUCKGO),
                "tavily-key",
                "exa-key",
                tavily.url("/search").toString(),
                exa.url("/search").toString(),
                "http://127.0.0.1:1/html/"), httpClient());

        SkillResult result = tool.execute(Map.of(
                "query", "agent search",
                "backend", "exa",
                "output_format", "json",
                "include_domains", List.of("allowed.example")), null);

        assertThat(result.isSuccess()).isTrue();
        Map<String, Object> payload = OBJECT_MAPPER.readValue(result.getOutput(), new TypeReference<>() {});
        assertThat(payload)
                .containsEntry("query", "agent search")
                .containsEntry("backend", "exa");
        List<Map<String, Object>> results = (List<Map<String, Object>>) payload.get("results");
        assertThat(results).hasSize(1);
        assertThat(results.get(0))
                .containsEntry("rank", 1)
                .containsEntry("title", "Allowed result")
                .containsEntry("url", "https://docs.allowed.example/page")
                .containsEntry("snippet", "Allowed highlight");
        assertThat(exa.getRequestCount()).isEqualTo(1);
        assertThat(tavily.getRequestCount()).isZero();
    }

    @Test
    @DisplayName("Tavily backend receives native filter parameters")
    void execute_tavilyBackend_passesNativeFilters() throws Exception {
        tavily = new MockWebServer();
        tavily.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"results":[{"title":"Tavily title","url":"https://docs.allowed.example/page","content":"Snippet"}]}
                        """));
        tavily.start();

        WebSearchTool tool = new WebSearchTool(config(
                List.of(WebSearchBackendName.TAVILY),
                "tavily-key",
                "",
                tavily.url("/search").toString(),
                "http://127.0.0.1:1/search",
                "http://127.0.0.1:1/html/"), httpClient());

        SkillResult result = tool.execute(Map.of(
                "query", "agent search",
                "time_range", "week",
                "topic", "general",
                "search_depth", "advanced",
                "country", "united states",
                "include_domains", List.of("allowed.example"),
                "exclude_domains", List.of("blocked.example")), null);

        assertThat(result.isSuccess()).isTrue();
        RecordedRequest request = tavily.takeRequest(1, TimeUnit.SECONDS);
        Map<String, Object> body = OBJECT_MAPPER.readValue(request.getBody().readUtf8(), new TypeReference<>() {});
        assertThat(body)
                .containsEntry("query", "agent search")
                .containsEntry("time_range", "week")
                .containsEntry("topic", "general")
                .containsEntry("search_depth", "advanced")
                .containsEntry("country", "united states")
                .containsEntry("include_domains", List.of("allowed.example"))
                .containsEntry("exclude_domains", List.of("blocked.example"));
    }

    @Test
    @DisplayName("Exa backend receives native date, type, category, and domain parameters")
    void execute_exaBackend_passesNativeFilters() throws Exception {
        exa = new MockWebServer();
        exa.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"results":[{"title":"Exa title","url":"https://docs.allowed.example/page","highlights":["Snippet"]}]}
                        """));
        exa.start();

        WebSearchTool tool = new WebSearchTool(config(
                List.of(WebSearchBackendName.EXA),
                "",
                "exa-key",
                "http://127.0.0.1:1/search",
                exa.url("/search").toString(),
                "http://127.0.0.1:1/html/"), httpClient());

        SkillResult result = tool.execute(Map.of(
                "query", "agent search",
                "start_date", "2026-01-01",
                "end_date", "2026-01-31",
                "search_type", "fast",
                "topic", "news",
                "user_location", "US",
                "include_domains", List.of("allowed.example"),
                "exclude_domains", List.of("blocked.example")), null);

        assertThat(result.isSuccess()).isTrue();
        RecordedRequest request = exa.takeRequest(1, TimeUnit.SECONDS);
        Map<String, Object> body = OBJECT_MAPPER.readValue(request.getBody().readUtf8(), new TypeReference<>() {});
        assertThat(body)
                .containsEntry("query", "agent search")
                .containsEntry("numResults", 5)
                .containsEntry("startPublishedDate", "2026-01-01T00:00:00.000Z")
                .containsEntry("endPublishedDate", "2026-01-31T23:59:59.999Z")
                .containsEntry("type", "fast")
                .containsEntry("category", "news")
                .containsEntry("userLocation", "US")
                .containsEntry("includeDomains", List.of("allowed.example"))
                .containsEntry("excludeDomains", List.of("blocked.example"));
    }

    private static WebSearchConfig config(List<WebSearchBackendName> priority,
                                          String tavilyKey,
                                          String exaKey,
                                          String tavilyBaseUrl,
                                          String exaBaseUrl,
                                          String duckDuckGoBaseUrl) {
        return new WebSearchConfig(priority, tavilyKey, exaKey,
                tavilyBaseUrl, exaBaseUrl, duckDuckGoBaseUrl);
    }

    private static OkHttpClient httpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .writeTimeout(2, TimeUnit.SECONDS)
                .build();
    }
}
