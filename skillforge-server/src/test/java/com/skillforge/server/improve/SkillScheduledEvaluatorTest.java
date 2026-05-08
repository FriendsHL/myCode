package com.skillforge.server.improve;

import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.SkillEntity;
import com.skillforge.server.entity.SkillEvalHistoryEntity;
import com.skillforge.server.repository.SkillEvalHistoryRepository;
import com.skillforge.server.repository.SkillRepository;
import com.skillforge.server.service.AgentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SkillScheduledEvaluator")
class SkillScheduledEvaluatorTest {

    @Mock private SkillRepository skillRepository;
    @Mock private SkillEvalHistoryRepository historyRepository;
    @Mock private AgentService agentService;
    @Mock private SkillAbEvalService skillAbEvalService;

    private SkillScheduledEvaluator newEvaluator(boolean enabled) {
        return new SkillScheduledEvaluator(
                skillRepository, historyRepository, agentService, skillAbEvalService, enabled);
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

    @Test
    @DisplayName("happy path: evaluates each enabled skill, persists history via runBaselineOnly")
    void runOnce_happy_callsRunBaselineOnly() {
        SkillEntity s1 = skill(11L, 7L);
        SkillEntity s2 = skill(22L, 7L);
        when(skillRepository.findByIsSystemFalseAndEnabledTrue()).thenReturn(List.of(s1, s2));
        when(historyRepository.countBySkillIdAndCreatedAtAfter(anyLong(), any(Instant.class)))
                .thenReturn(0L);
        when(agentService.listAgents(7L)).thenReturn(List.of(agent(99L, 7L)));
        when(skillAbEvalService.runBaselineOnly(anyLong(), anyString(), anyLong(), any(), eq("scheduled")))
                .thenReturn(new SkillEvalHistoryEntity());

        newEvaluator(true).runOnce();

        verify(skillAbEvalService).runBaselineOnly(11L, "99", 7L, null, "scheduled");
        verify(skillAbEvalService).runBaselineOnly(22L, "99", 7L, null, "scheduled");
    }

    @Test
    @DisplayName("yaml off: short-circuits without query")
    void runOnce_yamlOff_noop() {
        newEvaluator(false).runOnce();
        verify(skillRepository, never()).findByIsSystemFalseAndEnabledTrue();
        verify(skillAbEvalService, never()).runBaselineOnly(
                anyLong(), anyString(), anyLong(), any(), anyString());
    }

    @Test
    @DisplayName("skill evaluated within last 7d: skipped")
    void runOnce_recentlyEvaluated_skipped() {
        SkillEntity s1 = skill(11L, 7L);
        when(skillRepository.findByIsSystemFalseAndEnabledTrue()).thenReturn(List.of(s1));
        when(historyRepository.countBySkillIdAndCreatedAtAfter(eq(11L), any(Instant.class)))
                .thenReturn(1L);

        newEvaluator(true).runOnce();

        verify(skillAbEvalService, never()).runBaselineOnly(
                anyLong(), anyString(), anyLong(), any(), anyString());
    }

    @Test
    @DisplayName("single skill failure logs warn, others continue")
    void runOnce_perSkillFailure_continues() {
        SkillEntity s1 = skill(11L, 7L);
        SkillEntity s2 = skill(22L, 7L);
        when(skillRepository.findByIsSystemFalseAndEnabledTrue()).thenReturn(List.of(s1, s2));
        when(historyRepository.countBySkillIdAndCreatedAtAfter(anyLong(), any(Instant.class)))
                .thenReturn(0L);
        when(agentService.listAgents(7L)).thenReturn(List.of(agent(99L, 7L)));
        when(skillAbEvalService.runBaselineOnly(eq(11L), anyString(), anyLong(), any(), anyString()))
                .thenThrow(new RuntimeException("LLM timeout"));
        when(skillAbEvalService.runBaselineOnly(eq(22L), anyString(), anyLong(), any(), anyString()))
                .thenReturn(new SkillEvalHistoryEntity());

        newEvaluator(true).runOnce();

        // Both attempted, second succeeded.
        verify(skillAbEvalService, times(1)).runBaselineOnly(
                eq(11L), anyString(), anyLong(), any(), anyString());
        verify(skillAbEvalService, times(1)).runBaselineOnly(
                eq(22L), anyString(), anyLong(), any(), anyString());
    }

    @Test
    @DisplayName("skill with no host agent (no owner agents): skipped")
    void runOnce_noHostAgent_skipped() {
        SkillEntity s1 = skill(11L, 7L);
        when(skillRepository.findByIsSystemFalseAndEnabledTrue()).thenReturn(List.of(s1));
        when(historyRepository.countBySkillIdAndCreatedAtAfter(anyLong(), any(Instant.class)))
                .thenReturn(0L);
        when(agentService.listAgents(7L)).thenReturn(List.of());

        newEvaluator(true).runOnce();

        verify(skillAbEvalService, never()).runBaselineOnly(
                anyLong(), anyString(), anyLong(), any(), anyString());
    }

    @Test
    @DisplayName("0 enabled skills: noop")
    void runOnce_noSkills_noop() {
        when(skillRepository.findByIsSystemFalseAndEnabledTrue()).thenReturn(List.of());

        newEvaluator(true).runOnce();

        verify(skillAbEvalService, never()).runBaselineOnly(
                anyLong(), anyString(), anyLong(), any(), anyString());
    }
}
