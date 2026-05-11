package com.skillforge.server.memory.llmsynth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.skillforge.core.llm.LlmProvider;
import com.skillforge.core.llm.LlmProviderFactory;
import com.skillforge.core.llm.LlmRequest;
import com.skillforge.core.llm.LlmResponse;
import com.skillforge.server.config.LlmProperties;
import com.skillforge.server.config.MemoryProperties;
import com.skillforge.server.entity.MemoryEntity;
import com.skillforge.server.entity.MemoryProposalEntity;
import com.skillforge.server.repository.MemoryProposalRepository;
import com.skillforge.server.repository.MemoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("LlmMemorySynthesizer")
class LlmMemorySynthesizerTest {

    @Mock private MemoryRepository memoryRepository;
    @Mock private MemoryProposalRepository proposalRepository;
    @Mock private LlmProviderFactory llmProviderFactory;
    @Mock private LlmProvider llmProvider;

    private LlmProperties llmProperties;
    private MemoryProperties memoryProperties;
    private MemoryClusterer clusterer;
    private ObjectMapper objectMapper;
    private LlmMemorySynthesizer synth;

    @BeforeEach
    void setUp() {
        llmProperties = new LlmProperties();
        llmProperties.setDefaultProvider("bailian");
        memoryProperties = new MemoryProperties();
        clusterer = new MemoryClusterer();
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        synth = new LlmMemorySynthesizer(memoryRepository, proposalRepository,
                llmProviderFactory, llmProperties, memoryProperties, clusterer, objectMapper);

        when(llmProviderFactory.getProvider(anyString())).thenReturn(llmProvider);
        when(proposalRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(proposalRepository.findReferencingMemoryId(anyLong(), anyString())).thenReturn(List.of());
    }

    private static MemoryEntity mem(long id, String title, String content, String tags) {
        MemoryEntity m = new MemoryEntity();
        m.setId(id);
        m.setUserId(7L);
        m.setType("knowledge");
        m.setTitle(title);
        m.setContent(content);
        m.setTags(tags);
        m.setImportance("medium");
        m.setStatus("ACTIVE");
        m.setLastScore(0.5);
        m.setUpdatedAt(LocalDateTime.now());
        return m;
    }

    private static List<MemoryEntity> makeCluster() {
        return new ArrayList<>(List.of(
                mem(101L, "Postgres tips", "Use jsonb for nested data", "java,db,postgres"),
                mem(102L, "JPA pitfalls", "@Transactional on private methods doesn't work", "java,db,jpa"),
                mem(103L, "JDBC perf", "Use prepared statements always", "java,db,jdbc")
        ));
    }

    private static LlmResponse llmResp(String content, int in, int out) {
        LlmResponse r = new LlmResponse();
        r.setContent(content);
        r.setUsage(new LlmResponse.Usage(in, out));
        return r;
    }

    @Test
    @DisplayName("happy path: dedup + reflection + optimize each produce a saved proposal")
    void synthesize_happy_emitsAllPhases() {
        when(memoryRepository.findTopActiveByUserId(eq(7L), any(Pageable.class)))
                .thenReturn(makeCluster());
        // 3 phases × 3 LLM calls (dedup once, reflection once, optimize 3 times)
        when(llmProvider.chat(any(LlmRequest.class)))
                .thenReturn(llmResp("{\"proposals\":[{\"type\":\"dedup\","
                        + "\"sourceMemoryIds\":[101,102],\"winnerMemoryId\":101,"
                        + "\"reasoning\":\"both about jpa\"}]}", 800, 100))
                .thenReturn(llmResp("{\"proposals\":[{\"type\":\"reflection\","
                        + "\"sourceMemoryIds\":[101,102,103],"
                        + "\"suggestedTitle\":\"user prefers Postgres+JPA\","
                        + "\"suggestedContent\":\"User builds Java backends on PostgreSQL via JPA.\","
                        + "\"suggestedImportance\":\"medium\","
                        + "\"reasoning\":\"pattern\"}]}", 800, 150))
                .thenReturn(llmResp("{\"proposals\":[{\"type\":\"optimize\","
                        + "\"sourceMemoryIds\":[101],"
                        + "\"suggestedContent\":\"prefer jsonb for nested data in PG\","
                        + "\"reasoning\":\"shorter\"}]}", 200, 50))
                .thenReturn(llmResp("{\"proposals\":[]}", 200, 50))
                .thenReturn(llmResp("{\"proposals\":[]}", 200, 50));

        SynthesisRunResult result = synth.synthesize(7L);

        assertThat(result.skipped()).isFalse();
        assertThat(result.dedupProposals()).isEqualTo(1);
        assertThat(result.reflectionProposals()).isEqualTo(1);
        assertThat(result.optimizeProposals()).isEqualTo(1);
        assertThat(result.inputTokens()).isGreaterThan(0);
        assertThat(result.estimatedUsd()).isGreaterThan(0);
        verify(proposalRepository, times(3)).save(any(MemoryProposalEntity.class));
    }

    @Test
    @DisplayName("not enough candidates: skips entirely")
    void synthesize_notEnough_skips() {
        when(memoryRepository.findTopActiveByUserId(eq(7L), any(Pageable.class)))
                .thenReturn(List.of(mem(101L, "a", "b", "x")));

        SynthesisRunResult result = synth.synthesize(7L);

        assertThat(result.skipped()).isTrue();
        assertThat(result.skipReason()).isEqualTo("not_enough_candidates");
        verify(llmProvider, never()).chat(any());
    }

    @Test
    @DisplayName("cluster produces no viable groups (all disjoint): skips")
    void synthesize_noViableCluster_skips() {
        // 3 candidates but disjoint tags + 60d apart → no cluster meets min size
        MemoryEntity m1 = mem(101L, "a", "x", "alpha");
        MemoryEntity m2 = mem(102L, "b", "y", "beta");
        m2.setUpdatedAt(LocalDateTime.now().minusDays(100));
        MemoryEntity m3 = mem(103L, "c", "z", "gamma");
        m3.setUpdatedAt(LocalDateTime.now().minusDays(200));
        when(memoryRepository.findTopActiveByUserId(eq(7L), any(Pageable.class)))
                .thenReturn(List.of(m1, m2, m3));

        SynthesisRunResult result = synth.synthesize(7L);

        assertThat(result.skipped()).isTrue();
        assertThat(result.skipReason()).isEqualTo("no_viable_cluster");
    }

    @Test
    @DisplayName("invalid JSON from LLM: phase silently emits 0 proposals, others continue")
    void synthesize_invalidJson_dropsCluster() {
        when(memoryRepository.findTopActiveByUserId(eq(7L), any(Pageable.class)))
                .thenReturn(makeCluster());
        when(llmProvider.chat(any(LlmRequest.class)))
                .thenReturn(llmResp("garbage not json", 100, 10))
                .thenReturn(llmResp("{\"proposals\":[]}", 100, 10))
                .thenReturn(llmResp("{\"proposals\":[]}", 100, 10))
                .thenReturn(llmResp("{\"proposals\":[]}", 100, 10))
                .thenReturn(llmResp("{\"proposals\":[]}", 100, 10));

        SynthesisRunResult result = synth.synthesize(7L);

        assertThat(result.skipped()).isFalse();
        assertThat(result.totalProposals()).isZero();
    }

    @Test
    @DisplayName("invalid proposal type: dropped")
    void synthesize_invalidType_dropped() {
        when(memoryRepository.findTopActiveByUserId(eq(7L), any(Pageable.class)))
                .thenReturn(makeCluster());
        when(llmProvider.chat(any(LlmRequest.class)))
                .thenReturn(llmResp("{\"proposals\":[{\"type\":\"delete\","
                        + "\"sourceMemoryIds\":[101,102],\"reasoning\":\"x\"}]}", 100, 10))
                .thenReturn(llmResp("{\"proposals\":[]}", 100, 10))
                .thenReturn(llmResp("{\"proposals\":[]}", 100, 10))
                .thenReturn(llmResp("{\"proposals\":[]}", 100, 10))
                .thenReturn(llmResp("{\"proposals\":[]}", 100, 10));

        SynthesisRunResult result = synth.synthesize(7L);
        assertThat(result.dedupProposals()).isZero();
        assertThat(result.contradictionProposals()).isZero();
    }

    @Test
    @DisplayName("sourceMemoryIds referencing non-cluster ids: filtered out")
    void synthesize_outOfBoundsSourceIds_filtered() {
        when(memoryRepository.findTopActiveByUserId(eq(7L), any(Pageable.class)))
                .thenReturn(makeCluster());
        // 999 is not in [101,102,103] → after sanitize size drops below 2 → drop
        when(llmProvider.chat(any(LlmRequest.class)))
                .thenReturn(llmResp("{\"proposals\":[{\"type\":\"dedup\","
                        + "\"sourceMemoryIds\":[999, 888],\"winnerMemoryId\":999,"
                        + "\"reasoning\":\"x\"}]}", 100, 10))
                .thenReturn(llmResp("{\"proposals\":[]}", 100, 10))
                .thenReturn(llmResp("{\"proposals\":[]}", 100, 10))
                .thenReturn(llmResp("{\"proposals\":[]}", 100, 10))
                .thenReturn(llmResp("{\"proposals\":[]}", 100, 10));

        SynthesisRunResult result = synth.synthesize(7L);
        assertThat(result.dedupProposals()).isZero();
    }

    @Test
    @DisplayName("dedup sourceIds > 5: dropped (mass-delete guard)")
    void synthesize_dedupTooManyIds_dropped() {
        // Build a 6-member cluster
        List<MemoryEntity> wide = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            wide.add(mem(100L + i, "t" + i, "c" + i, "java,jpa"));
        }
        when(memoryRepository.findTopActiveByUserId(eq(7L), any(Pageable.class))).thenReturn(wide);
        when(llmProvider.chat(any(LlmRequest.class)))
                .thenReturn(llmResp("{\"proposals\":[{\"type\":\"dedup\","
                        + "\"sourceMemoryIds\":[100,101,102,103,104,105],\"winnerMemoryId\":100,"
                        + "\"reasoning\":\"all dups\"}]}", 100, 10))
                .thenReturn(llmResp("{\"proposals\":[]}", 100, 10))
                .thenReturn(llmResp("{\"proposals\":[]}", 100, 10))
                .thenReturn(llmResp("{\"proposals\":[]}", 100, 10))
                .thenReturn(llmResp("{\"proposals\":[]}", 100, 10))
                .thenReturn(llmResp("{\"proposals\":[]}", 100, 10))
                .thenReturn(llmResp("{\"proposals\":[]}", 100, 10))
                .thenReturn(llmResp("{\"proposals\":[]}", 100, 10));

        SynthesisRunResult result = synth.synthesize(7L);
        assertThat(result.dedupProposals()).isZero();
    }

    @Test
    @DisplayName("reasoning > 200 chars hard-truncated before persist")
    void synthesize_reasoningOversized_truncates() {
        when(memoryRepository.findTopActiveByUserId(eq(7L), any(Pageable.class)))
                .thenReturn(makeCluster());
        String longReasoning = "x".repeat(500);
        when(llmProvider.chat(any(LlmRequest.class)))
                .thenReturn(llmResp("{\"proposals\":[{\"type\":\"dedup\","
                        + "\"sourceMemoryIds\":[101,102],\"winnerMemoryId\":101,"
                        + "\"reasoning\":\"" + longReasoning + "\"}]}", 100, 10))
                .thenReturn(llmResp("{\"proposals\":[]}", 100, 10))
                .thenReturn(llmResp("{\"proposals\":[]}", 100, 10))
                .thenReturn(llmResp("{\"proposals\":[]}", 100, 10))
                .thenReturn(llmResp("{\"proposals\":[]}", 100, 10));

        synth.synthesize(7L);
        ArgumentCaptor<MemoryProposalEntity> cap = ArgumentCaptor.forClass(MemoryProposalEntity.class);
        verify(proposalRepository).save(cap.capture());
        assertThat(cap.getValue().getReasoning()).hasSize(200);
    }

    @Test
    @DisplayName("dedup winner not in sourceIds: dropped")
    void synthesize_dedupWinnerNotInSources_dropped() {
        when(memoryRepository.findTopActiveByUserId(eq(7L), any(Pageable.class)))
                .thenReturn(makeCluster());
        when(llmProvider.chat(any(LlmRequest.class)))
                .thenReturn(llmResp("{\"proposals\":[{\"type\":\"dedup\","
                        + "\"sourceMemoryIds\":[101,102],\"winnerMemoryId\":999,"
                        + "\"reasoning\":\"x\"}]}", 100, 10))
                .thenReturn(llmResp("{\"proposals\":[]}", 100, 10))
                .thenReturn(llmResp("{\"proposals\":[]}", 100, 10))
                .thenReturn(llmResp("{\"proposals\":[]}", 100, 10))
                .thenReturn(llmResp("{\"proposals\":[]}", 100, 10));

        SynthesisRunResult result = synth.synthesize(7L);
        assertThat(result.dedupProposals()).isZero();
    }

    @Test
    @DisplayName("contradiction with null winner is allowed (user picks later)")
    void synthesize_contradictionNullWinner_persisted() {
        when(memoryRepository.findTopActiveByUserId(eq(7L), any(Pageable.class)))
                .thenReturn(makeCluster());
        when(llmProvider.chat(any(LlmRequest.class)))
                .thenReturn(llmResp("{\"proposals\":[{\"type\":\"contradiction\","
                        + "\"sourceMemoryIds\":[101,102],\"reasoning\":\"facts disagree\"}]}", 100, 10))
                .thenReturn(llmResp("{\"proposals\":[]}", 100, 10))
                .thenReturn(llmResp("{\"proposals\":[]}", 100, 10))
                .thenReturn(llmResp("{\"proposals\":[]}", 100, 10))
                .thenReturn(llmResp("{\"proposals\":[]}", 100, 10));

        SynthesisRunResult result = synth.synthesize(7L);
        assertThat(result.contradictionProposals()).isEqualTo(1);
    }

    @Test
    @DisplayName("lastScore all null fallback: still picks candidates by updatedAt")
    void synthesize_allLastScoreNull_stillRuns() {
        List<MemoryEntity> cluster = makeCluster();
        for (MemoryEntity m : cluster) m.setLastScore(null);
        when(memoryRepository.findTopActiveByUserId(eq(7L), any(Pageable.class)))
                .thenReturn(cluster);
        when(llmProvider.chat(any(LlmRequest.class)))
                .thenReturn(llmResp("{\"proposals\":[]}", 100, 10))
                .thenReturn(llmResp("{\"proposals\":[]}", 100, 10))
                .thenReturn(llmResp("{\"proposals\":[]}", 100, 10))
                .thenReturn(llmResp("{\"proposals\":[]}", 100, 10))
                .thenReturn(llmResp("{\"proposals\":[]}", 100, 10));

        SynthesisRunResult result = synth.synthesize(7L);
        assertThat(result.skipped()).isFalse();
    }

    @Test
    @DisplayName("markdown fenced JSON is unwrapped correctly")
    void synthesize_markdownFencedJson_unwrapped() {
        when(memoryRepository.findTopActiveByUserId(eq(7L), any(Pageable.class)))
                .thenReturn(makeCluster());
        when(llmProvider.chat(any(LlmRequest.class)))
                .thenReturn(llmResp("```json\n{\"proposals\":[{\"type\":\"dedup\","
                        + "\"sourceMemoryIds\":[101,102],\"winnerMemoryId\":101,"
                        + "\"reasoning\":\"x\"}]}\n```", 100, 10))
                .thenReturn(llmResp("{\"proposals\":[]}", 100, 10))
                .thenReturn(llmResp("{\"proposals\":[]}", 100, 10))
                .thenReturn(llmResp("{\"proposals\":[]}", 100, 10))
                .thenReturn(llmResp("{\"proposals\":[]}", 100, 10));

        SynthesisRunResult result = synth.synthesize(7L);
        assertThat(result.dedupProposals()).isEqualTo(1);
    }

    @Test
    @DisplayName("phase exception: other phases continue (failure isolation)")
    void synthesize_phaseFails_othersContinue() {
        when(memoryRepository.findTopActiveByUserId(eq(7L), any(Pageable.class)))
                .thenReturn(makeCluster());
        // Dedup call throws; reflection + optimize must still run
        when(llmProvider.chat(any(LlmRequest.class)))
                .thenThrow(new RuntimeException("provider failed"))
                .thenReturn(llmResp("{\"proposals\":[{\"type\":\"reflection\","
                        + "\"sourceMemoryIds\":[101,102,103],"
                        + "\"suggestedContent\":\"insight here\","
                        + "\"reasoning\":\"x\"}]}", 100, 10))
                .thenReturn(llmResp("{\"proposals\":[]}", 100, 10))
                .thenReturn(llmResp("{\"proposals\":[]}", 100, 10))
                .thenReturn(llmResp("{\"proposals\":[]}", 100, 10));

        SynthesisRunResult result = synth.synthesize(7L);
        assertThat(result.dedupProposals()).isZero();
        assertThat(result.reflectionProposals()).isEqualTo(1);
    }

    @Test
    @DisplayName("null user id returns skipped")
    void synthesize_nullUserId_skipped() {
        SynthesisRunResult result = synth.synthesize(null);
        assertThat(result.skipped()).isTrue();
    }
}
