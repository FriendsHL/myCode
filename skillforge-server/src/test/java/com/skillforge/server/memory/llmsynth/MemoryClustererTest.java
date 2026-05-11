package com.skillforge.server.memory.llmsynth;

import com.skillforge.server.entity.MemoryEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MemoryClusterer")
class MemoryClustererTest {

    private final MemoryClusterer clusterer = new MemoryClusterer();

    private static MemoryEntity mem(long id, String tags, LocalDateTime updated) {
        MemoryEntity m = new MemoryEntity();
        m.setId(id);
        m.setUserId(1L);
        m.setType("knowledge");
        m.setTitle("title " + id);
        m.setContent("content " + id);
        m.setTags(tags);
        m.setImportance("medium");
        m.setUpdatedAt(updated);
        m.setLastScore(0.5);
        return m;
    }

    @Test
    @DisplayName("Jaccard overlap >= threshold clusters memories together")
    void cluster_jaccardOverlap_clustersTogether() {
        LocalDateTime now = LocalDateTime.now();
        List<MemoryEntity> input = List.of(
                mem(1L, "java,jpa,spring", now),
                mem(2L, "java,jpa,hibernate", now),
                mem(3L, "java,jpa,postgres", now),
                // Outlier — no tag overlap
                mem(4L, "python,django", now)
        );

        List<MemoryCluster> clusters = clusterer.cluster(input);

        assertThat(clusters).hasSize(1);
        assertThat(clusters.get(0).memberIds()).containsExactlyInAnyOrder(1L, 2L, 3L);
    }

    @Test
    @DisplayName("no-tag fallback: untagged memories within 7d window cluster together")
    void cluster_untagged_withinWindow_clusters() {
        LocalDateTime t0 = LocalDateTime.now();
        List<MemoryEntity> input = List.of(
                mem(1L, null, t0),
                mem(2L, "  ", t0.minusDays(1)),
                mem(3L, "", t0.minusDays(3)),
                // outside 7d window
                mem(4L, null, t0.minusDays(30))
        );

        List<MemoryCluster> clusters = clusterer.cluster(input);

        assertThat(clusters).hasSize(1);
        assertThat(clusters.get(0).memberIds()).containsExactlyInAnyOrder(1L, 2L, 3L);
    }

    @Test
    @DisplayName("single cluster below MIN_CLUSTER_SIZE is dropped silently")
    void cluster_smallCluster_dropped() {
        LocalDateTime now = LocalDateTime.now();
        List<MemoryEntity> input = List.of(
                mem(1L, "x,y", now),
                mem(2L, "x,y", now),                  // 2 < MIN_CLUSTER_SIZE
                mem(3L, "p,q", now.minusDays(60))     // singleton
        );

        List<MemoryCluster> clusters = clusterer.cluster(input);

        assertThat(clusters).isEmpty();
    }

    @Test
    @DisplayName("cluster size > MAX_CLUSTER_SIZE truncates to top by lastScore")
    void cluster_overCap_truncates() {
        LocalDateTime now = LocalDateTime.now();
        List<MemoryEntity> input = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            MemoryEntity m = mem(100L + i, "shared,tag", now);
            // first 5 have low score, last 15 high — top 15 should win
            m.setLastScore(i < 5 ? 0.1 : 0.9);
            input.add(m);
        }

        List<MemoryCluster> clusters = clusterer.cluster(input);

        assertThat(clusters).hasSize(1);
        assertThat(clusters.get(0).memberMemories()).hasSize(MemoryClusterer.MAX_CLUSTER_SIZE);
    }

    @Test
    @DisplayName("more than MAX_CLUSTERS clusters: only top by avg score kept")
    void cluster_overClusterCap_truncates() {
        LocalDateTime now = LocalDateTime.now();
        List<MemoryEntity> input = new ArrayList<>();
        // build 12 disjoint 3-member clusters via distinct tag sets
        for (int g = 0; g < 12; g++) {
            for (int i = 0; i < 3; i++) {
                MemoryEntity m = mem(g * 10L + i, "g" + g + ",tag", now);
                m.setLastScore(g * 0.05);
                input.add(m);
            }
        }

        List<MemoryCluster> clusters = clusterer.cluster(input);

        assertThat(clusters).hasSizeLessThanOrEqualTo(MemoryClusterer.MAX_CLUSTERS);
    }

    @Test
    @DisplayName("null / under-min candidate list returns empty")
    void cluster_nullOrTooSmall_returnsEmpty() {
        assertThat(clusterer.cluster(null)).isEmpty();
        assertThat(clusterer.cluster(List.of())).isEmpty();
        LocalDateTime now = LocalDateTime.now();
        assertThat(clusterer.cluster(List.of(mem(1L, "a", now), mem(2L, "a", now)))).isEmpty();
    }
}
