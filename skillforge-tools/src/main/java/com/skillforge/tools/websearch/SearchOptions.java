package com.skillforge.tools.websearch;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

public record SearchOptions(
        int maxResults,
        List<String> includeDomains,
        List<String> excludeDomains,
        String timeRange,
        String startDate,
        String endDate,
        String topic,
        String searchDepth,
        String searchType,
        String country,
        String userLocation
) {
    public static SearchOptions withMaxResults(int maxResults) {
        return new SearchOptions(maxResults, List.of(), List.of(), null, null, null,
                null, null, null, null, null);
    }

    public SearchOptions {
        maxResults = Math.max(1, maxResults);
        includeDomains = includeDomains == null ? List.of() : List.copyOf(includeDomains);
        excludeDomains = excludeDomains == null ? List.of() : List.copyOf(excludeDomains);
        timeRange = normalize(timeRange);
        startDate = normalize(startDate);
        endDate = normalize(endDate);
        topic = normalize(topic);
        searchDepth = normalize(searchDepth);
        searchType = normalize(searchType);
        country = normalize(country);
        userLocation = normalize(userLocation);
    }

    public String tavilyTimeRange() {
        return startDate != null || endDate != null ? null : timeRange;
    }

    public String tavilyCountry() {
        return topic == null || "general".equalsIgnoreCase(topic) ? country : null;
    }

    public String exaStartPublishedDate() {
        if (startDate != null) {
            return isoStartOfDay(startDate);
        }
        LocalDate start = startDateFromTimeRange();
        return start == null ? null : start + "T00:00:00.000Z";
    }

    public String exaEndPublishedDate() {
        return endDate == null ? null : isoEndOfDay(endDate);
    }

    private LocalDate startDateFromTimeRange() {
        if (timeRange == null) {
            return null;
        }
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        return switch (timeRange.toLowerCase(Locale.ROOT)) {
            case "day", "d" -> today.minusDays(1);
            case "week", "w" -> today.minusWeeks(1);
            case "month", "m" -> today.minusMonths(1);
            case "year", "y" -> today.minusYears(1);
            default -> null;
        };
    }

    private static String isoStartOfDay(String value) {
        return parseDate(value) + "T00:00:00.000Z";
    }

    private static String isoEndOfDay(String value) {
        return parseDate(value) + "T23:59:59.999Z";
    }

    private static LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Date must use YYYY-MM-DD format: " + value, e);
        }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
