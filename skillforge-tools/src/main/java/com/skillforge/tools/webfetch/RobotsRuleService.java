package com.skillforge.tools.webfetch;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import crawlercommons.robots.BaseRobotRules;
import crawlercommons.robots.SimpleRobotRulesParser;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Set;

public class RobotsRuleService {

    private static final Logger log = LoggerFactory.getLogger(RobotsRuleService.class);
    private static final String USER_AGENT = "SkillForge";

    private final OkHttpClient httpClient;
    private final Set<String> hostAllowlist;
    private final Cache<String, BaseRobotRules> robotsCache;
    private final SimpleRobotRulesParser parser = new SimpleRobotRulesParser();

    public RobotsRuleService(WebFetchConfig config, OkHttpClient httpClient) {
        this.httpClient = httpClient;
        this.hostAllowlist = config.robotsHostAllowlist();
        this.robotsCache = Caffeine.newBuilder()
                .expireAfterWrite(config.robotsCacheTtl())
                .maximumSize(config.robotsCacheMaxEntries())
                .build();
    }

    public RobotsDecision check(HttpUrl targetUrl) {
        String host = targetUrl.host().toLowerCase(Locale.ROOT);
        if (hostAllowlist.contains(host)) {
            return RobotsDecision.allowed("allowlist");
        }
        HttpUrl robotsUrl = targetUrl.newBuilder()
                .encodedPath("/robots.txt")
                .encodedQuery(null)
                .fragment(null)
                .build();
        try {
            BaseRobotRules rules = robotsCache.get(robotsUrl.toString(), this::fetchRules);
            if (rules == null || rules.isAllowAll()) {
                return RobotsDecision.allowed("allow");
            }
            boolean allowed = rules.isAllowed(targetUrl.toString());
            return allowed
                    ? RobotsDecision.allowed("allow")
                    : RobotsDecision.blocked("blocked by robots.txt: " + robotsUrl);
        } catch (Exception e) {
            log.warn("Robots check failed for {}: {}", targetUrl, e.getMessage());
            return RobotsDecision.allowed("fail-open");
        }
    }

    private BaseRobotRules fetchRules(String robotsUrl) {
        Request request = new Request.Builder()
                .url(robotsUrl)
                .header("User-Agent", "SkillForge/1.0")
                .get()
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            int code = response.code();
            if (code == 404 || code >= 500) {
                return parser.failedFetch(code);
            }
            ResponseBody body = response.body();
            byte[] bytes = body == null ? new byte[0] : body.bytes();
            return parser.parseContent(robotsUrl, bytes, "text/plain", USER_AGENT);
        } catch (Exception e) {
            log.warn("Fetching robots.txt failed for {}: {}", robotsUrl, e.getMessage());
            return parser.parseContent(robotsUrl, new byte[0], "text/plain", USER_AGENT);
        }
    }

    public record RobotsDecision(boolean allowed, String status, String errorMessage) {
        public static RobotsDecision allowed(String status) {
            return new RobotsDecision(true, status, null);
        }

        public static RobotsDecision blocked(String message) {
            return new RobotsDecision(false, "blocked", message);
        }
    }
}
