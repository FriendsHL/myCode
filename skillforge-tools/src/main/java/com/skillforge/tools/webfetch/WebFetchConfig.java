package com.skillforge.tools.webfetch;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

public record WebFetchConfig(
        Duration fetchCacheTtl,
        long fetchCacheMaxEntries,
        Duration robotsCacheTtl,
        long robotsCacheMaxEntries,
        Set<String> robotsHostAllowlist
) {
    public static WebFetchConfig defaults() {
        return new WebFetchConfig(
                Duration.ofMinutes(15),
                1000,
                Duration.ofHours(1),
                500,
                Set.of("localhost", "127.0.0.1"));
    }

    public static WebFetchConfig fromAllowlistCsv(String hostAllowlist) {
        Set<String> hosts = hostAllowlist == null || hostAllowlist.isBlank()
                ? Set.of("localhost", "127.0.0.1")
                : Arrays.stream(hostAllowlist.split(","))
                        .map(String::trim)
                        .filter(value -> !value.isBlank())
                        .map(value -> value.toLowerCase(Locale.ROOT))
                        .collect(Collectors.toSet());
        WebFetchConfig defaults = defaults();
        return new WebFetchConfig(
                defaults.fetchCacheTtl(),
                defaults.fetchCacheMaxEntries(),
                defaults.robotsCacheTtl(),
                defaults.robotsCacheMaxEntries(),
                hosts);
    }

    public static WebFetchConfig fromHostAllowlist(Collection<String> hostAllowlist) {
        Set<String> hosts = hostAllowlist == null || hostAllowlist.isEmpty()
                ? Set.of("localhost", "127.0.0.1")
                : hostAllowlist.stream()
                        .map(String::trim)
                        .filter(value -> !value.isBlank())
                        .map(value -> value.toLowerCase(Locale.ROOT))
                        .collect(Collectors.toSet());
        WebFetchConfig defaults = defaults();
        return new WebFetchConfig(
                defaults.fetchCacheTtl(),
                defaults.fetchCacheMaxEntries(),
                defaults.robotsCacheTtl(),
                defaults.robotsCacheMaxEntries(),
                hosts);
    }

    public WebFetchConfig {
        fetchCacheTtl = positiveOrDefault(fetchCacheTtl, Duration.ofMinutes(15));
        fetchCacheMaxEntries = fetchCacheMaxEntries > 0 ? fetchCacheMaxEntries : 1000;
        robotsCacheTtl = positiveOrDefault(robotsCacheTtl, Duration.ofHours(1));
        robotsCacheMaxEntries = robotsCacheMaxEntries > 0 ? robotsCacheMaxEntries : 500;
        robotsHostAllowlist = robotsHostAllowlist == null ? Set.of() : Set.copyOf(robotsHostAllowlist);
    }

    private static Duration positiveOrDefault(Duration value, Duration fallback) {
        return value != null && !value.isNegative() && !value.isZero() ? value : fallback;
    }
}
