package com.skillforge.tools.websearch;

import java.time.Duration;
import java.util.List;

public record SearchResult(List<SearchHit> hits, String backendName, Duration latency) {
}
