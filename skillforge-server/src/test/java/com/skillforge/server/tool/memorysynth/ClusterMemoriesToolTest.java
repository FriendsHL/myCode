package com.skillforge.server.tool.memorysynth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.server.entity.MemoryEntity;
import com.skillforge.server.memory.llmsynth.MemoryClusterer;
import com.skillforge.server.repository.MemoryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ClusterMemoriesTool")
class ClusterMemoriesToolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    // Real clusterer — exercise the actual union-find algorithm, not a mock, so the test
    // also validates the wire-up rather than restating the algorithm in mocks.
    private final MemoryClusterer clusterer = new MemoryClusterer();

    @Mock
    private MemoryRepository memoryRepository;

    private static MemoryEntity mem(long id, String tags, LocalDateTime updated) {
        MemoryEntity m = new MemoryEntity();
        m.setId(id);
        m.setUserId(42L);
        m.setType("knowledge");
        m.setTitle("t-" + id);
        m.setContent("c-" + id);
        m.setTags(tags);
        m.setImportance("medium");
        m.setUpdatedAt(updated);
        m.setLastScore(0.5);
        return m;
    }

    @Test
    @DisplayName("memories sharing tags above Jaccard threshold form one cluster")
    void execute_clusterByTagOverlap() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        List<MemoryEntity> rows = List.of(
                mem(1L, "java,jpa,spring", now),
                mem(2L, "java,jpa,hibernate", now),
                mem(3L, "java,jpa,postgres", now),
                mem(4L, "python,django,celery", now));
        when(memoryRepository.findAllById(anyIterable())).thenReturn(rows);

        ClusterMemoriesTool tool = new ClusterMemoriesTool(clusterer, memoryRepository, objectMapper);

        SkillResult result = tool.execute(
                Map.of("memoryIds", List.of(1L, 2L, 3L, 4L)),
                new SkillContext(null, "s1", 42L));

        assertThat(result.isSuccess()).isTrue();
        JsonNode root = objectMapper.readTree(result.getOutput());
        assertThat(root.path("clusterCount").asInt()).isEqualTo(1);
        assertThat(root.path("resolvedCount").asInt()).isEqualTo(4);
        JsonNode firstCluster = root.path("clusters").get(0);
        // The python row (4) is dropped — only the 3 java/jpa rows survive.
        assertThat(firstCluster.path("size").asInt()).isEqualTo(3);
        List<Long> memberIds = new ArrayList<>();
        firstCluster.path("memberIds").forEach(n -> memberIds.add(n.asLong()));
        assertThat(memberIds).containsExactlyInAnyOrder(1L, 2L, 3L);
    }

    @Test
    @DisplayName("cluster of size < 3 is silently dropped (min cluster size)")
    void execute_undersized_clusterDropped() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        // Only 2 java rows — below MIN_CLUSTER_SIZE=3 → no cluster.
        List<MemoryEntity> rows = List.of(
                mem(1L, "java,jpa", now),
                mem(2L, "java,jpa", now),
                mem(3L, "python,django,celery", now),
                mem(4L, "rust,async", now));
        when(memoryRepository.findAllById(anyIterable())).thenReturn(rows);

        ClusterMemoriesTool tool = new ClusterMemoriesTool(clusterer, memoryRepository, objectMapper);

        SkillResult result = tool.execute(
                Map.of("memoryIds", List.of(1L, 2L, 3L, 4L)),
                new SkillContext(null, "s1", 42L));

        assertThat(result.isSuccess()).isTrue();
        JsonNode root = objectMapper.readTree(result.getOutput());
        assertThat(root.path("clusterCount").asInt()).isEqualTo(0);
        assertThat(root.path("resolvedCount").asInt()).isEqualTo(4);
    }

    @Test
    @DisplayName("untagged memories within 7-day window also cluster (no-tag fallback)")
    void execute_noTagWithin7Days_clusters() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        List<MemoryEntity> rows = List.of(
                mem(1L, "", now),
                mem(2L, "", now.minusDays(2)),
                mem(3L, "", now.minusDays(5)),
                // Stale row outside 7d → outlier.
                mem(4L, "", now.minusDays(30)));
        when(memoryRepository.findAllById(anyIterable())).thenReturn(rows);

        ClusterMemoriesTool tool = new ClusterMemoriesTool(clusterer, memoryRepository, objectMapper);

        SkillResult result = tool.execute(
                Map.of("memoryIds", List.of(1L, 2L, 3L, 4L)),
                new SkillContext(null, "s1", 42L));

        JsonNode root = objectMapper.readTree(result.getOutput());
        assertThat(root.path("clusterCount").asInt()).isEqualTo(1);
        JsonNode c = root.path("clusters").get(0);
        List<Long> memberIds = new ArrayList<>();
        c.path("memberIds").forEach(n -> memberIds.add(n.asLong()));
        assertThat(memberIds).containsExactlyInAnyOrder(1L, 2L, 3L);
    }

    @Test
    @DisplayName("over-cap cluster is truncated to 15 members")
    void execute_overCapCluster_truncatedTo15() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        List<MemoryEntity> rows = new ArrayList<>();
        // 20 memories all sharing tag "java,jpa" → single big cluster.
        for (long i = 1; i <= 20; i++) {
            MemoryEntity m = mem(i, "java,jpa", now);
            // Increase lastScore monotonically so top-15 are deterministic for assertion.
            m.setLastScore((double) i);
            rows.add(m);
        }
        when(memoryRepository.findAllById(anyIterable())).thenReturn(rows);

        ClusterMemoriesTool tool = new ClusterMemoriesTool(clusterer, memoryRepository, objectMapper);

        List<Long> ids = new ArrayList<>();
        for (long i = 1; i <= 20; i++) ids.add(i);
        SkillResult result = tool.execute(
                Map.of("memoryIds", ids),
                new SkillContext(null, "s1", 42L));

        JsonNode root = objectMapper.readTree(result.getOutput());
        assertThat(root.path("clusterCount").asInt()).isEqualTo(1);
        assertThat(root.path("clusters").get(0).path("size").asInt())
                .isEqualTo(MemoryClusterer.MAX_CLUSTER_SIZE);
    }

    @Test
    @DisplayName("missing memoryIds returns validation error without DB hit")
    void execute_missingMemoryIds_validationError() {
        ClusterMemoriesTool tool = new ClusterMemoriesTool(clusterer, memoryRepository, objectMapper);

        SkillResult result = tool.execute(Map.of(), new SkillContext(null, "s1", 42L));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorType()).isEqualTo(SkillResult.ErrorType.VALIDATION);
        assertThat(result.getError()).contains("memoryIds");
        org.mockito.Mockito.verifyNoInteractions(memoryRepository);
    }
}
