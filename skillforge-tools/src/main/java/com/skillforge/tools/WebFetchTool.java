package com.skillforge.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.Tool;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.tools.webfetch.RobotsRuleService;
import com.skillforge.tools.webfetch.WebFetchConfig;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter;
import com.vladsch.flexmark.util.data.MutableDataSet;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Tool that fetches a URL and returns its content as text.
 * Converts HTML to readable Markdown by default.
 */
public class WebFetchTool implements Tool {

    private static final int DEFAULT_MAX_LENGTH = 20_000;
    private static final int MIN_TIMEOUT_MS = 100;
    private static final int MAX_TIMEOUT_MS = 60_000;
    private static final String USER_AGENT = "SkillForge/1.0";
    private static final FlexmarkHtmlConverter HTML_CONVERTER = FlexmarkHtmlConverter.builder(
            new MutableDataSet().set(FlexmarkHtmlConverter.SETEXT_HEADINGS, false)
    ).build();

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final RobotsRuleService robotsRuleService;
    private final Cache<String, FetchResult> fetchCache;

    public WebFetchTool() {
        this(WebFetchConfig.defaults(), defaultHttpClient());
    }

    public WebFetchTool(WebFetchConfig config) {
        this(config, defaultHttpClient());
    }

    public WebFetchTool(WebFetchConfig config, OkHttpClient httpClient) {
        this.httpClient = httpClient;
        this.objectMapper = new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(SerializationFeature.INDENT_OUTPUT);
        this.robotsRuleService = new RobotsRuleService(config, httpClient);
        this.fetchCache = Caffeine.newBuilder()
                .expireAfterWrite(config.fetchCacheTtl())
                .maximumSize(config.fetchCacheMaxEntries())
                .recordStats()
                .build();
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
        return "WebFetch";
    }

    @Override
    public String getDescription() {
        return "Fetch a URL and return page content as Markdown by default. "
                + "Can return plain text, raw content, or structured JSON on request. "
                + "Useful for reading web pages, APIs, and documentation.";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("url", Map.of(
                "type", "string",
                "description", "The URL to fetch"
        ));
        properties.put("maxLength", Map.of(
                "type", "integer",
                "description", "Maximum characters to return (default 20000)"
        ));
        properties.put("bypass_cache", Map.of(
                "type", "boolean",
                "description", "Bypass the 15 minute in-memory cache for this request"
        ));
        properties.put("content_format", Map.of(
                "type", "string",
                "enum", List.of("markdown", "text", "raw"),
                "description", "How to convert the fetched response body (default markdown)"
        ));
        properties.put("output_format", Map.of(
                "type", "string",
                "enum", List.of("text", "json"),
                "description", "Return human-readable text or a JSON object string (default text)"
        ));
        properties.put("timeout_ms", Map.of(
                "type", "integer",
                "description", "Per-request fetch timeout in milliseconds, clamped to 100-60000"
        ));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("url"));

        return new ToolSchema(getName(), getDescription(), schema);
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        try {
            String url = (String) input.get("url");
            if (url == null || url.isBlank()) {
                return SkillResult.error("url is required");
            }

            int maxLength = Math.max(1, intInput(input, "maxLength", DEFAULT_MAX_LENGTH));
            boolean bypassCache = Boolean.TRUE.equals(input.get("bypass_cache"));
            ContentFormat contentFormat = ContentFormat.fromInput(stringInput(input, "content_format", "contentFormat"));
            OutputFormat outputFormat = OutputFormat.fromInput(stringInput(input, "output_format", "outputFormat"));
            int timeoutMs = clamp(intInput(input, "timeout_ms", 0), MIN_TIMEOUT_MS, MAX_TIMEOUT_MS);
            HttpUrl parsedUrl = HttpUrl.get(url);

            RobotsRuleService.RobotsDecision robotsDecision = robotsRuleService.check(parsedUrl);
            if (!robotsDecision.allowed()) {
                return SkillResult.error(robotsDecision.errorMessage());
            }

            String cacheKey = sha256(url + "\ncontent_format=" + contentFormat.wireName());
            FetchResult fetched;
            String cacheStatus;
            if (bypassCache) {
                fetched = fetch(url, contentFormat, timeoutMs);
                fetchCache.put(cacheKey, fetched);
                cacheStatus = "bypass";
            } else {
                FetchResult cached = fetchCache.getIfPresent(cacheKey);
                if (cached != null) {
                    fetched = cached;
                    cacheStatus = "hit";
                } else {
                    fetched = fetch(url, contentFormat, timeoutMs);
                    fetchCache.put(cacheKey, fetched);
                    cacheStatus = "miss";
                }
            }

            String content = fetched.content();
            boolean truncated = false;
            if (content.length() > maxLength) {
                content = content.substring(0, maxLength) + "\n\n... [truncated at " + maxLength + " chars]";
                truncated = true;
            }

            if (outputFormat == OutputFormat.JSON) {
                return SkillResult.success(formatJson(
                        url, fetched.statusCode(), fetched.contentType(), cacheStatus,
                        robotsDecision.status(), contentFormat, content, truncated, maxLength));
            }

            return SkillResult.success(formatHeader(
                    url, fetched.statusCode(), fetched.contentType(), cacheStatus, robotsDecision.status())
                    + "\n\n" + content);
        } catch (Exception e) {
            return SkillResult.error("Failed to fetch URL: " + e.getMessage());
        }
    }

    private FetchResult fetch(String url, ContentFormat contentFormat, int timeoutMs) throws Exception {
        OkHttpClient client = timeoutMs > 0
                ? httpClient.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()
                : httpClient;
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .get()
                .build();

        try (Response response = client.newCall(request).execute()) {
            int statusCode = response.code();
            String contentType = response.header("Content-Type", "");

            ResponseBody body = response.body();
            if (body == null) {
                return new FetchResult(statusCode, contentType, "[Empty response body]");
            }

            String rawContent = body.string();
            String content;

            if (contentFormat == ContentFormat.RAW) {
                content = rawContent;
            } else if (isHtml(contentType)) {
                content = contentFormat == ContentFormat.TEXT
                        ? htmlToPlainText(rawContent, url)
                        : htmlToMarkdown(rawContent, url);
            } else if (contentFormat == ContentFormat.TEXT) {
                content = rawContent;
            } else if (contentType.contains("json")) {
                content = prettyPrintJson(rawContent);
            } else {
                content = rawContent;
            }
            return new FetchResult(statusCode, contentType, content);
        }
    }

    private static boolean isHtml(String contentType) {
        return contentType.contains("text/html") || contentType.contains("application/xhtml");
    }

    private String formatHeader(String url, int statusCode, String contentType,
                                String cacheStatus, String robotsStatus) {
        return "URL: " + url
                + "\nStatus: " + statusCode
                + "\nContent-Type: " + contentType
                + "\nCache: " + cacheStatus
                + "\nRobots: " + robotsStatus;
    }

    private String formatJson(String url, int statusCode, String contentType,
                              String cacheStatus, String robotsStatus, ContentFormat contentFormat,
                              String content, boolean truncated, int maxLength) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("url", url);
        payload.put("statusCode", statusCode);
        payload.put("contentType", contentType);
        payload.put("cache", cacheStatus);
        payload.put("robots", robotsStatus);
        payload.put("contentFormat", contentFormat.wireName());
        payload.put("truncated", truncated);
        payload.put("maxLength", maxLength);
        payload.put("content", content);
        return objectMapper.writeValueAsString(payload);
    }

    private String htmlToPlainText(String html, String baseUrl) {
        Document doc = Jsoup.parse(html, baseUrl);
        doc.select("script, style, nav, footer, header").remove();
        return doc.body() != null ? doc.body().text() : doc.text();
    }

    private String htmlToMarkdown(String html, String baseUrl) {
        Document doc = Jsoup.parse(html, baseUrl);
        doc.select("script, style, nav, footer, header").remove();
        return HTML_CONVERTER.convert(doc.body() != null ? doc.body() : doc).trim();
    }

    private String prettyPrintJson(String json) {
        try {
            Object parsed = objectMapper.readValue(json, Object.class);
            return objectMapper.writeValueAsString(parsed);
        } catch (Exception e) {
            // Not valid JSON, return as-is
            return json;
        }
    }

    private static String sha256(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static String stringInput(Map<String, Object> input, String snakeKey, String camelKey) {
        Object value = input.get(snakeKey);
        if (value == null) {
            value = input.get(camelKey);
        }
        return value == null ? null : value.toString();
    }

    private static int intInput(Map<String, Object> input, String key, int defaultValue) {
        Object value = input.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private static int clamp(int value, int min, int max) {
        if (value <= 0) {
            return 0;
        }
        return Math.min(Math.max(value, min), max);
    }

    private enum ContentFormat {
        MARKDOWN("markdown"),
        TEXT("text"),
        RAW("raw");

        private final String wireName;

        ContentFormat(String wireName) {
            this.wireName = wireName;
        }

        String wireName() {
            return wireName;
        }

        static ContentFormat fromInput(String value) {
            if (value == null || value.isBlank()) {
                return MARKDOWN;
            }
            return switch (value.trim().toLowerCase(Locale.ROOT).replace("-", "_")) {
                case "markdown", "md" -> MARKDOWN;
                case "text", "plain_text", "plaintext" -> TEXT;
                case "raw", "html" -> RAW;
                default -> MARKDOWN;
            };
        }
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

    private record FetchResult(int statusCode, String contentType, String content) {}
}
