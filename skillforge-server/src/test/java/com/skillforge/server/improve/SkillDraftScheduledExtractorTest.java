package com.skillforge.server.improve;

import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.exception.AgentNotFoundException;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.service.AgentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SkillDraftScheduledExtractor")
class SkillDraftScheduledExtractorTest {

    @Mock private SessionRepository sessionRepository;
    @Mock private AgentService agentService;
    @Mock private SkillDraftService skillDraftService;

    private SkillDraftScheduledExtractor newExtractor(boolean enabled) {
        return new SkillDraftScheduledExtractor(
                sessionRepository, agentService, skillDraftService, enabled);
    }

    @Test
    @DisplayName("happy path: extracts for each eligible agent")
    void runOnce_happy_extractsForEach() {
        when(sessionRepository.findDistinctAgentIdsWithRecentUserMessage(any(Instant.class)))
                .thenReturn(List.of(101L, 102L));
        AgentEntity a1 = new AgentEntity();
        a1.setId(101L);
        a1.setOwnerId(7L);
        AgentEntity a2 = new AgentEntity();
        a2.setId(102L);
        a2.setOwnerId(8L);
        when(agentService.getAgent(101L)).thenReturn(a1);
        when(agentService.getAgent(102L)).thenReturn(a2);
        when(skillDraftService.hasPendingDrafts(7L)).thenReturn(false);
        when(skillDraftService.hasPendingDrafts(8L)).thenReturn(false);
        when(skillDraftService.extractFromRecentSessions(101L, 7L)).thenReturn(2);
        when(skillDraftService.extractFromRecentSessions(102L, 8L)).thenReturn(0);

        newExtractor(true).runOnce();

        verify(skillDraftService).extractFromRecentSessions(101L, 7L);
        verify(skillDraftService).extractFromRecentSessions(102L, 8L);
    }

    @Test
    @DisplayName("yaml off: short-circuits without query")
    void runOnce_yamlOff_skipsAll() {
        newExtractor(false).runOnce();

        verify(sessionRepository, never()).findDistinctAgentIdsWithRecentUserMessage(any());
        verify(skillDraftService, never()).extractFromRecentSessions(anyLong(), anyLong());
    }

    @Test
    @DisplayName("0 eligible agents: no extraction happens")
    void runOnce_noEligibleAgents_noop() {
        when(sessionRepository.findDistinctAgentIdsWithRecentUserMessage(any(Instant.class)))
                .thenReturn(List.of());

        newExtractor(true).runOnce();

        verify(skillDraftService, never()).extractFromRecentSessions(anyLong(), anyLong());
    }

    @Test
    @DisplayName("single agent failure logs warn, continues to next agent")
    void runOnce_oneAgentFails_othersContinue() {
        when(sessionRepository.findDistinctAgentIdsWithRecentUserMessage(any(Instant.class)))
                .thenReturn(List.of(101L, 102L));
        AgentEntity a1 = new AgentEntity();
        a1.setId(101L);
        a1.setOwnerId(7L);
        AgentEntity a2 = new AgentEntity();
        a2.setId(102L);
        a2.setOwnerId(8L);
        when(agentService.getAgent(101L)).thenReturn(a1);
        when(agentService.getAgent(102L)).thenReturn(a2);
        when(skillDraftService.hasPendingDrafts(7L)).thenReturn(false);
        when(skillDraftService.hasPendingDrafts(8L)).thenReturn(false);
        when(skillDraftService.extractFromRecentSessions(101L, 7L))
                .thenThrow(new RuntimeException("LLM provider down"));
        when(skillDraftService.extractFromRecentSessions(102L, 8L)).thenReturn(1);

        newExtractor(true).runOnce();

        // Both agents attempted, second succeeds despite first throwing.
        verify(skillDraftService).extractFromRecentSessions(101L, 7L);
        verify(skillDraftService).extractFromRecentSessions(102L, 8L);
    }

    @Test
    @DisplayName("agent has pending drafts: extraction skipped")
    void runOnce_pendingDrafts_skipsAgent() {
        when(sessionRepository.findDistinctAgentIdsWithRecentUserMessage(any(Instant.class)))
                .thenReturn(List.of(101L));
        AgentEntity a1 = new AgentEntity();
        a1.setId(101L);
        a1.setOwnerId(7L);
        when(agentService.getAgent(101L)).thenReturn(a1);
        when(skillDraftService.hasPendingDrafts(7L)).thenReturn(true);

        newExtractor(true).runOnce();

        verify(skillDraftService, never()).extractFromRecentSessions(anyLong(), anyLong());
    }

    @Test
    @DisplayName("agent missing (deleted): skipped without throwing")
    void runOnce_agentMissing_skipsThatAgent() {
        when(sessionRepository.findDistinctAgentIdsWithRecentUserMessage(any(Instant.class)))
                .thenReturn(List.of(101L));
        when(agentService.getAgent(101L)).thenThrow(new AgentNotFoundException(101L));

        newExtractor(true).runOnce();

        verify(skillDraftService, never()).extractFromRecentSessions(anyLong(), eq(7L));
    }
}
