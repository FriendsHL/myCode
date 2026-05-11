package com.skillforge.server.memory.llmsynth;

import com.skillforge.server.entity.MemoryEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * MEMORY-LLM-SYNTHESIS (V68): rule-based clustering (Phase 0, zero token).
 *
 * <p>F-N3 nit follow-up: union-find over candidates. Two memories merge into the same
 * cluster when:
 * <ul>
 *   <li>tag Jaccard overlap ≥ {@link #JACCARD_THRESHOLD}; OR</li>
 *   <li>both lack tags AND {@code updatedAt} within {@link #WINDOW_DAYS} of each other
 *       (no-tag fallback so untagged-but-fresh sessions still cluster).</li>
 * </ul>
 *
 * <p>Output constraints:
 * <ul>
 *   <li>at most {@link #MAX_CLUSTERS} clusters returned (highest-quality first by avg lastScore)</li>
 *   <li>cluster size ≥ {@link #MIN_CLUSTER_SIZE} or dropped silently</li>
 *   <li>cluster size ≤ {@link #MAX_CLUSTER_SIZE}; over-cap clusters truncated to top by lastScore</li>
 * </ul>
 */
@Component
public class MemoryClusterer {

    private static final Logger log = LoggerFactory.getLogger(MemoryClusterer.class);

    public static final double JACCARD_THRESHOLD = 0.3;
    public static final int WINDOW_DAYS = 7;
    public static final int MAX_CLUSTERS = 10;
    public static final int MIN_CLUSTER_SIZE = 3;
    public static final int MAX_CLUSTER_SIZE = 15;

    public List<MemoryCluster> cluster(List<MemoryEntity> candidates) {
        if (candidates == null || candidates.size() < MIN_CLUSTER_SIZE) {
            return List.of();
        }
        int n = candidates.size();

        // Pre-parse tag sets so we don't reparse in the O(N^2) loop.
        List<Set<String>> tagSets = new ArrayList<>(n);
        List<Instant> updatedInstants = new ArrayList<>(n);
        for (MemoryEntity m : candidates) {
            tagSets.add(parseTagSet(m.getTags()));
            updatedInstants.add(toInstant(m.getUpdatedAt()));
        }

        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;

        long windowMillis = Duration.ofDays(WINDOW_DAYS).toMillis();
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (shouldMerge(tagSets.get(i), tagSets.get(j),
                                updatedInstants.get(i), updatedInstants.get(j), windowMillis)) {
                    union(parent, i, j);
                }
            }
        }

        // Group indices by root.
        Map<Integer, List<Integer>> groups = new HashMap<>();
        for (int i = 0; i < n; i++) {
            groups.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(i);
        }

        List<MemoryCluster> clusters = new ArrayList<>();
        for (Map.Entry<Integer, List<Integer>> entry : groups.entrySet()) {
            List<Integer> idxList = entry.getValue();
            if (idxList.size() < MIN_CLUSTER_SIZE) {
                continue;
            }
            // Sort within cluster by lastScore desc (nulls last) for deterministic top-k truncation.
            List<MemoryEntity> members = new ArrayList<>(idxList.size());
            for (int idx : idxList) {
                members.add(candidates.get(idx));
            }
            members.sort(Comparator
                    .comparing(MemoryEntity::getLastScore, Comparator.nullsLast(Comparator.reverseOrder())));
            if (members.size() > MAX_CLUSTER_SIZE) {
                members = new ArrayList<>(members.subList(0, MAX_CLUSTER_SIZE));
            }
            Set<Long> memberIds = new LinkedHashSet<>();
            for (MemoryEntity m : members) memberIds.add(m.getId());

            // Aggregate dominant type + shared tags for info / logging.
            Map<String, Integer> typeCounts = new HashMap<>();
            for (MemoryEntity m : members) {
                if (m.getType() != null) {
                    typeCounts.merge(m.getType(), 1, Integer::sum);
                }
            }
            String dominantType = typeCounts.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(null);

            Set<String> shared = null;
            for (MemoryEntity m : members) {
                Set<String> tags = parseTagSet(m.getTags());
                if (shared == null) {
                    shared = new HashSet<>(tags);
                } else {
                    shared.retainAll(tags);
                }
            }
            if (shared == null) shared = Set.of();

            clusters.add(new MemoryCluster(
                    "cluster-" + UUID.randomUUID(),
                    List.copyOf(members),
                    Set.copyOf(memberIds),
                    dominantType,
                    Set.copyOf(shared)));
        }

        // Take at most MAX_CLUSTERS, prefer the densest / highest-quality.
        clusters.sort(Comparator
                .comparingDouble((MemoryCluster c) -> -avgScore(c.memberMemories()))
                .thenComparingInt(c -> -c.memberMemories().size()));
        if (clusters.size() > MAX_CLUSTERS) {
            clusters = new ArrayList<>(clusters.subList(0, MAX_CLUSTERS));
        }
        if (log.isDebugEnabled()) {
            log.debug("MemoryClusterer: n={} clusters={} sizes={}",
                    n, clusters.size(),
                    clusters.stream().map(c -> c.memberMemories().size()).toList());
        }
        return clusters;
    }

    private static boolean shouldMerge(Set<String> a, Set<String> b,
                                       Instant aT, Instant bT, long windowMillis) {
        boolean tagsEmpty = a.isEmpty() && b.isEmpty();
        if (!tagsEmpty) {
            return jaccard(a, b) >= JACCARD_THRESHOLD;
        }
        // No-tag fallback: both untagged → cluster if within 7d window.
        if (aT == null || bT == null) {
            return false;
        }
        return Math.abs(aT.toEpochMilli() - bT.toEpochMilli()) <= windowMillis;
    }

    static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) return 0.0;
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        if (intersection.isEmpty()) return 0.0;
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) intersection.size() / union.size();
    }

    static Set<String> parseTagSet(String tags) {
        if (tags == null || tags.isBlank()) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        for (String t : Arrays.asList(tags.split(","))) {
            String trimmed = t.trim().toLowerCase();
            if (trimmed.isEmpty()) continue;
            // Skip noise tokens emitted by the rule extractor that don't carry topic signal.
            if (trimmed.equals("auto-extract") || trimmed.equals("llm")
                    || trimmed.startsWith("importance:")) {
                continue;
            }
            out.add(trimmed);
        }
        return out;
    }

    /**
     * r2 fix R2-3 (W-1): pin conversion to UTC so the 7d window doesn't drift between
     * dev/prod environments running in different OS time zones. Server runtime assumes
     * UTC (see application.yml spring.jackson.time-zone=UTC + JVM user.timezone=UTC).
     */
    private static Instant toInstant(LocalDateTime ldt) {
        if (ldt == null) return null;
        return ldt.toInstant(ZoneOffset.UTC);
    }

    private static double avgScore(List<MemoryEntity> members) {
        double sum = 0;
        int cnt = 0;
        for (MemoryEntity m : members) {
            if (m.getLastScore() != null) {
                sum += m.getLastScore();
                cnt++;
            }
        }
        return cnt == 0 ? 0.0 : sum / cnt;
    }

    private static int find(int[] parent, int x) {
        while (parent[x] != x) {
            parent[x] = parent[parent[x]];   // path compression
            x = parent[x];
        }
        return x;
    }

    private static void union(int[] parent, int a, int b) {
        int ra = find(parent, a);
        int rb = find(parent, b);
        if (ra != rb) parent[ra] = rb;
    }
}
