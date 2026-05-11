package com.skillforge.server.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.dto.MemoryProposalDto;
import com.skillforge.server.entity.MemoryProposalEntity;
import com.skillforge.server.memory.llmsynth.LlmMemorySynthesisScheduler;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.repository.MemoryRepository;
import com.skillforge.server.repository.ScheduledTaskRepository;
import com.skillforge.server.service.MemoryProposalService;
import com.skillforge.server.service.scheduling.ScheduledTaskExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * E2E-1 regression coverage: {@code GET /api/admin/memory/proposals} must return a raw
 * JSON array, not an envelope object. The previous {@code {ok, proposals, count}} shape
 * broke the FE TanStack Query consumer that did {@code proposals.map(...)} directly.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdminMemoryLlmSynthesisController#listProposals (E2E-1 shape)")
class AdminMemoryLlmSynthesisControllerListProposalsTest {

    @Mock private LlmMemorySynthesisScheduler scheduler;
    @Mock private MemoryProposalService proposalService;
    @Mock private MemoryRepository memoryRepository;
    @Mock private AgentRepository agentRepository;
    @Mock private ScheduledTaskRepository scheduledTaskRepository;
    @Mock private ScheduledTaskExecutor scheduledTaskExecutor;

    private ObjectMapper objectMapper;
    private AdminMemoryLlmSynthesisController controller;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        controller = new AdminMemoryLlmSynthesisController(
                scheduler, proposalService, memoryRepository, objectMapper,
                agentRepository, scheduledTaskRepository, scheduledTaskExecutor);
    }

    private static MemoryProposalEntity proposal(long id) {
        MemoryProposalEntity p = new MemoryProposalEntity();
        p.setId(id);
        p.setUserId(1L);
        p.setSynthesisRunId("r-" + id);
        p.setProposalType(MemoryProposalEntity.TYPE_DEDUP);
        p.setSourceMemoryIds("[101,102]");
        p.setWinnerMemoryId(101L);
        p.setStatus(MemoryProposalEntity.STATUS_PROPOSED);
        p.setCreatedAt(Instant.now());
        return p;
    }

    @Test
    @DisplayName("E2E-1: response body is a raw List<MemoryProposalDto>, not an envelope map")
    void listProposals_returnsRawArray_notEnvelope() {
        when(proposalService.list(any(), any(), anyInt()))
                .thenReturn(List.of(proposal(10L), proposal(11L)));
        when(memoryRepository.findAllById(any())).thenReturn(List.of());

        ResponseEntity<List<MemoryProposalDto>> resp =
                controller.listProposals(null, null, 50);

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        List<MemoryProposalDto> body = resp.getBody();
        assertThat(body).isNotNull();
        // The critical assertion: the body is a List (iterable), not a Map envelope.
        assertThat(body).isInstanceOf(List.class).hasSize(2);
        assertThat(body.get(0).id()).isEqualTo(10L);
        assertThat(body.get(1).id()).isEqualTo(11L);
        // sourceMemoryIds is parsed (R2-2 + E2E-1 compose correctly).
        assertThat(body.get(0).sourceMemoryIds()).containsExactly(101L, 102L);
    }

    @Test
    @DisplayName("listProposals returns empty array when service returns no rows")
    void listProposals_empty_returnsEmptyArray() {
        when(proposalService.list(any(), any(), anyInt())).thenReturn(List.of());

        ResponseEntity<List<MemoryProposalDto>> resp =
                controller.listProposals("proposed", 7L, 50);

        assertThat(resp.getBody()).isNotNull().isInstanceOf(List.class).isEmpty();
    }
}
