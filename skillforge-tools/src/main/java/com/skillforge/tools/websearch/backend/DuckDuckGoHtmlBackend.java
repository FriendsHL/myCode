package com.skillforge.tools.websearch.backend;

import com.skillforge.tools.websearch.SearchHit;
import com.skillforge.tools.websearch.SearchOptions;
import com.skillforge.tools.websearch.SearchResult;
import com.skillforge.tools.websearch.WebSearchBackend;
import com.skillforge.tools.websearch.WebSearchBackendName;
import com.skillforge.tools.websearch.WebSearchConfig;
import com.skillforge.tools.websearch.WebSearchException;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Deprecated(forRemoval = false)
public class DuckDuckGoHtmlBackend implements WebSearchBackend {

    private static final String USER_AGENT = "SkillForge/1.0";

    private final WebSearchConfig config;
    private final OkHttpClient httpClient;

    public DuckDuckGoHtmlBackend(WebSearchConfig config, OkHttpClient httpClient) {
        this.config = config;
        this.httpClient = httpClient;
    }

    @Override
    public SearchResult search(String query, SearchOptions options) throws WebSearchException {
        Instant start = Instant.now();
        try {
            HttpUrl base = HttpUrl.get(config.duckDuckGoSearchUrl());
            HttpUrl searchUrl = base.newBuilder()
                    .addQueryParameter("q", query)
                    .build();
            Request request = new Request.Builder()
                    .url(searchUrl)
                    .header("User-Agent", USER_AGENT)
                    .get()
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                ResponseBody body = response.body();
                String html = body == null ? "" : body.string();
                if (!response.isSuccessful()) {
                    throw new WebSearchException("DuckDuckGo HTML returned HTTP " + response.code());
                }
                return new SearchResult(parseHits(html, options.maxResults()), name().wireName(),
                        Duration.between(start, Instant.now()));
            }
        } catch (WebSearchException e) {
            throw e;
        } catch (Exception e) {
            throw new WebSearchException("DuckDuckGo HTML search failed: " + e.getMessage(), e);
        }
    }

    @Override
    public WebSearchBackendName name() {
        return WebSearchBackendName.DUCKDUCKGO;
    }

    @Override
    public boolean isConfigured() {
        return true;
    }

    private List<SearchHit> parseHits(String html, int maxResults) {
        Document doc = Jsoup.parse(html);
        List<SearchHit> hits = new ArrayList<>();
        Elements resultElements = doc.select(".result__body");

        for (Element element : resultElements) {
            if (hits.size() >= maxResults) {
                break;
            }
            Element titleLink = element.selectFirst(".result__a");
            Element snippet = element.selectFirst(".result__snippet");
            if (titleLink == null) {
                continue;
            }
            String title = titleLink.text();
            String href = unwrapRedirect(titleLink.attr("href"));
            String snippetText = snippet != null ? snippet.text() : "";
            if (!title.isBlank() && !href.isBlank()) {
                hits.add(new SearchHit(title, href, snippetText, hits.size() + 1));
            }
        }
        return hits;
    }

    private static String unwrapRedirect(String href) {
        if (href == null) {
            return "";
        }
        if (!href.contains("uddg=")) {
            return href;
        }
        try {
            String decoded = URLDecoder.decode(
                    href.substring(href.indexOf("uddg=") + 5), StandardCharsets.UTF_8);
            int ampIdx = decoded.indexOf('&');
            return ampIdx > 0 ? decoded.substring(0, ampIdx) : decoded;
        } catch (Exception ignored) {
            return href;
        }
    }
}
