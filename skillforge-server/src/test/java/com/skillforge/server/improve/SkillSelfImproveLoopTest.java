package com.skillforge.server.improve;

import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.SkillEntity;
import com.skillforge.server.entity.SkillEvalHistoryEntity;
import com.skillforge.server.event.SkillAbCompletedEvent;
import com.skillforge.server.repository.SkillEvalHistoryRepository;
import com.skillforge.server.repository.SkillRepository;
import com.skillforge.server.service.AgentService;
import com.skillforge.server.websocket.UserWebSocketHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SkillSelfImproveLoop")
class SkillSelfImproveLoopTest {

    @Mock private SkillRepository skillRepository;
    @Mock private SkillEvalHistoryRepository historyRepository;
    @Mock private AgentService agentService;
    @Mock private SkillEvolutionService skillEvolutionService;
    @Mock private UserWebSocketHandler userWebSocketHandler;

    private SkillSelfImproveLoop newLoop(boolean enabled, double threshold) {
        return new SkillSelfImproveLoop(
                skillRepository, historyRepository, agentService,
                skillEvolutionService, userWebSocketHandler,
                enabled, threshold);
    }

    private SkillEntity skill(Long id, Long ownerId) {
        SkillEntity s = new SkillEntity();
        s.setId(id);
        s.setName("Skill" + id);
        s.setOwnerId(ownerId);
        s.setEnabled(true);
        s.setSystem(false);
        return s;
    }

    private AgentEntity agent(Long id, Long ownerId) {
        AgentEntity a = new AgentEntity();
        a.setId(id);
        a.setOwnerId(ownerId);
        return a;
    }

    private SkillEvalHistoryEntity history(double composite) {
        SkillEvalHistoryEntity h = new SkillEvalHistoryEntity();
        h.setCompositeScore(composite);
        h.setTriggeredBy("scheduled");
        return h;
    }

    // ─── runOnce() — cron path ───────────────────────────────────────────

    @Test
    @DisplayName("happy path: latest score < threshold → triggers evolve via SYSTEM_USER_ID = 0L")
    void runOnce_lowScore_triggersEvolve() {
        SkillEntity s = skill(11L, 7L);
        when(skillRepository.findByIsSystemFalseAndEnabledTrue()).thenReturn(List.of(s));
        when(historyRepository.findFirstBySkillIdOrderByCreatedAtDesc(11L))
                .thenReturn(Optional.of(history(40.0)));
        when(agentService.listAgents(7L)).thenReturn(List.of(agent(99L, 7L)));

        newLoop(true, 60.0).runOnce();

        verify(skillEvolutionService).createAndTrigger(11L, "99", 0L);
    }

    @Test
    @DisplayName("yaml off: short-circuits without query")
    void runOnce_yamlOff_noop() {
        newLoop(false, 60.0).runOnce();
        verify(skillRepository, never()).findByIsSystemFalseAndEnabledTrue();
        verifyNoInteractions(skillEvolutionService);
    }

    @Test
    @DisplayName("INV-4: no history → skip")
    void runOnce_noHistory_skipped() {
        SkillEntity s = skill(11L, 7L);
        when(skillRepository.findByIsSystemFalseAndEnabledTrue()).thenReturn(List.of(s));
        when(historyRepository.findFirstBySkillIdOrderByCreatedAtDesc(11L))
                .thenReturn(Optional.empty());

        newLoop(true, 60.0).runOnce();

        verifyNoInteractions(skillEvolutionService);
    }

    @Test
    @DisplayName("INV-4: latest score >= threshold → skip")
    void runOnce_scoreAboveThreshold_skipped() {
        SkillEntity s = skill(11L, 7L);
        when(skillRepository.findByIsSystemFalseAndEnabledTrue()).thenReturn(List.of(s));
        when(historyRepository.findFirstBySkillIdOrderByCreatedAtDesc(11L))
                .thenReturn(Optional.of(history(80.0)));

        newLoop(true, 60.0).runOnce();

        verifyNoInteractions(skillEvolutionService);
    }

    @Test
    @DisplayName("INV-2: per-skill failure isolated, others continue")
    void runOnce_perSkillFailure_continues() {
        SkillEntity s1 = skill(11L, 7L);
        SkillEntity s2 = skill(22L, 7L);
        when(skillRepository.findByIsSystemFalseAndEnabledTrue()).thenReturn(List.of(s1, s2));
        when(historyRepository.findFirstBySkillIdOrderByCreatedAtDesc(anyLong()))
                .thenReturn(Optional.of(history(30.0)));
        when(agentService.listAgents(7L)).thenReturn(List.of(agent(99L, 7L)));
        when(skillEvolutionService.createAndTrigger(eq(11L), anyString(), anyLong()))
                .thenThrow(new RuntimeException("evolve in progress"));

        newLoop(true, 60.0).runOnce();

        verify(skillEvolutionService).createAndTrigger(11L, "99", 0L);
        verify(skillEvolutionService).createAndTrigger(22L, "99", 0L);
    }

    @Test
    @DisplayName("skill with no host agent: skipped")
    void runOnce_noHostAgent_skipped() {
        SkillEntity s = skill(11L, 7L);
        when(skillRepository.findByIsSystemFalseAndEnabledTrue()).thenReturn(List.of(s));
        when(historyRepository.findFirstBySkillIdOrderByCreatedAtDesc(11L))
                .thenReturn(Optional.of(history(30.0)));
        when(agentService.listAgents(7L)).thenReturn(List.of());

        newLoop(true, 60.0).runOnce();

        verifyNoInteractions(skillEvolutionService);
    }

    @Test
    @DisplayName("0 enabled skills: noop")
    void runOnce_noSkills_noop() {
        when(skillRepository.findByIsSystemFalseAndEnabledTrue()).thenReturn(List.of());
        newLoop(true, 60.0).runOnce();
        verifyNoInteractions(skillEvolutionService);
    }

    // ─── onAbCompleted() — listener path ─────────────────────────────────

    @Test
    @DisplayName("INV-9: promoted=true → WS push to skill owner with full payload")
    void onAbCompleted_promoted_pushesToOwner() {
        SkillEntity s = skill(11L, 7L);
        when(skillRepository.findById(11L)).thenReturn(Optional.of(s));

        SkillAbCompletedEvent event = new SkillAbCompletedEvent(
                11L, "ab-run-1", true, 35.0, 78.0, "v1", "v2");

        newLoop(true, 60.0).onAbCompleted(event);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(userWebSocketHandler).broadcast(eq(7L), captor.capture());
        Map<String, Object> payload = captor.getValue();
        assertThat(payload).containsEntry("type", "skill_auto_upgraded");
        assertThat(payload).containsEntry("skillId", 11L);
        assertThat(payload).containsEntry("oldVersion", "v1");
        assertThat(payload).containsEntry("newVersion", "v2");
        assertThat(payload).containsEntry("baselineScore", 35.0);
        assertThat(payload).containsEntry("candidateScore", 78.0);
        assertThat(payload).containsEntry("skillName", "Skill11");
    }

    @Test
    @DisplayName("INV-9: promoted=false → only logs, no WS push")
    void onAbCompleted_notPromoted_noPush() {
        SkillAbCompletedEvent event = new SkillAbCompletedEvent(
                11L, "ab-run-2", false, 50.0, 52.0, "v1", "v2");

        newLoop(true, 60.0).onAbCompleted(event);

        verifyNoInteractions(userWebSocketHandler);
    }

    @Test
    @DisplayName("event with null skillId: ignored")
    void onAbCompleted_nullSkillId_ignored() {
        SkillAbCompletedEvent event = new SkillAbCompletedEvent(
                null, "ab-run-3", true, 0.0, 100.0, null, null);
        newLoop(true, 60.0).onAbCompleted(event);
        verifyNoInteractions(userWebSocketHandler);
    }

    @Test
    @DisplayName("skill vanished after promote: log warn, no push")
    void onAbCompleted_skillMissing_noPush() {
        when(skillRepository.findById(99L)).thenReturn(Optional.empty());
        SkillAbCompletedEvent event = new SkillAbCompletedEvent(
                99L, "ab-run-4", true, 30.0, 70.0, "v1", "v2");

        newLoop(true, 60.0).onAbCompleted(event);

        verify(userWebSocketHandler, never()).broadcast(any(), any());
    }

    @Test
    @DisplayName("skill with no ownerId: log warn, no push")
    void onAbCompleted_skillNoOwner_noPush() {
        SkillEntity s = skill(11L, null);
        when(skillRepository.findById(11L)).thenReturn(Optional.of(s));
        SkillAbCompletedEvent event = new SkillAbCompletedEvent(
                11L, "ab-run-5", true, 30.0, 70.0, "v1", "v2");

        newLoop(true, 60.0).onAbCompleted(event);

        verify(userWebSocketHandler, never()).broadcast(any(), any());
    }
}
