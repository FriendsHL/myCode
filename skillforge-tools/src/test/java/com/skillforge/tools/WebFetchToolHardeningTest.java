package com.skillforge.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.tools.webfetch.WebFetchConfig;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class WebFetchToolHardeningTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private MockWebServer server;

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) {
            server.shutdown();
        }
    }

    @Test
    @DisplayName("HTML responses are converted to Markdown instead of flattened text")
    void execute_htmlResponse_returnsMarkdownStructure() throws Exception {
        server = new MockWebServer();
        server.enqueue(new MockResponse().setResponseCode(404));
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/html; charset=utf-8")
                .setBody("""
                        <html><body>
                          <h1>Main Title</h1>
                          <p>Read the <a href="/docs">docs</a>.</p>
                          <ul><li>First</li><li>Second</li></ul>
                          <pre><code>echo hello</code></pre>
                        </body></html>
                        """));
        server.start();

        WebFetchTool tool = new WebFetchTool(testConfig(Set.of()), httpClient());

        SkillResult result = tool.execute(Map.of("url", server.url("/page").toString()), null);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput())
                .contains("Cache: miss")
                .contains("Robots: allow")
                .contains("# Main Title")
                .contains("[docs](")
                .contains("First")
                .contains("echo hello");
    }

    @Test
    @DisplayName("repeated URL fetch uses cache after first network request")
    void execute_sameUrlTwice_returnsCachedSecondResponse() throws Exception {
        server = new MockWebServer();
        server.enqueue(new MockResponse().setResponseCode(404));
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/plain")
                .setBody("fresh body"));
        server.start();

        WebFetchTool tool = new WebFetchTool(testConfig(Set.of()), httpClient());
        String url = server.url("/cached").toString();

        SkillResult first = tool.execute(Map.of("url", url), null);
        SkillResult second = tool.execute(Map.of("url", url), null);

        assertThat(first.isSuccess()).isTrue();
        assertThat(second.isSuccess()).isTrue();
        assertThat(first.getOutput()).contains("Cache: miss").contains("fresh body");
        assertThat(second.getOutput()).contains("Cache: hit").contains("fresh body");
        assertThat(server.getRequestCount()).isEqualTo(2);
        assertThat(server.takeRequest(1, TimeUnit.SECONDS).getPath()).isEqualTo("/robots.txt");
        assertThat(server.takeRequest(1, TimeUnit.SECONDS).getPath()).isEqualTo("/cached");
    }

    @Test
    @DisplayName("robots.txt disallow blocks fetch unless host is allowlisted")
    void execute_robotsDisallow_blocksNonAllowlistedHost() throws Exception {
        server = new MockWebServer();
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/plain")
                .setBody("User-agent: *\nDisallow: /blocked\n"));
        server.start();

        WebFetchTool tool = new WebFetchTool(testConfig(Set.of()), httpClient());

        SkillResult result = tool.execute(Map.of("url", server.url("/blocked/page").toString()), null);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("blocked by robots.txt");
        assertThat(server.getRequestCount()).isEqualTo(1);
        RecordedRequest robotsRequest = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(robotsRequest.getPath()).isEqualTo("/robots.txt");
    }

    @Test
    @DisplayName("JSON output returns structured fetch metadata and content")
    void execute_jsonOutput_returnsStructuredFetchPayload() throws Exception {
        server = new MockWebServer();
        server.enqueue(new MockResponse().setResponseCode(404));
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/html; charset=utf-8")
                .setBody("""
                        <html><body>
                          <h1>Main Title</h1>
                          <p>Read the docs.</p>
                        </body></html>
                        """));
        server.start();

        WebFetchTool tool = new WebFetchTool(testConfig(Set.of()), httpClient());

        SkillResult result = tool.execute(Map.of(
                "url", server.url("/json-page").toString(),
                "output_format", "json",
                "content_format", "text"), null);

        assertThat(result.isSuccess()).isTrue();
        Map<String, Object> payload = OBJECT_MAPPER.readValue(result.getOutput(), new TypeReference<>() {});
        assertThat(payload)
                .containsEntry("statusCode", 200)
                .containsEntry("cache", "miss")
                .containsEntry("robots", "allow")
                .containsEntry("contentFormat", "text")
                .containsEntry("truncated", false);
        assertThat(payload.get("content")).isEqualTo("Main Title Read the docs.");
    }

    private static WebFetchConfig testConfig(Set<String> allowlist) {
        return new WebFetchConfig(
                Duration.ofMinutes(15),
                1000,
                Duration.ofHours(1),
                500,
                allowlist);
    }

    private static OkHttpClient httpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .writeTimeout(2, TimeUnit.SECONDS)
                .build();
    }
}
