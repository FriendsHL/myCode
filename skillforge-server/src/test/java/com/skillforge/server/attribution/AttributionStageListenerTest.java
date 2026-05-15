package com.skillforge.server.attribution;

import com.skillforge.server.entity.OptimizationEventEntity;
import com.skillforge.server.event.SkillAbCompletedEvent;
import com.skillforge.server.improve.event.PromptPromotedEvent;
import com.skillforge.server.repository.OptimizationEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AttributionStageListener (V3.2 stage-mirror)")
class AttributionStageListenerTest {

    @Mock private OptimizationEventRepository eventRepository;
    @Mock private AttributionEventBroadcaster broadcaster;

    private AttributionStageListener listener;

    @BeforeEach
    void setUp() {
        listener = new AttributionStageListener(eventRepository, broadcaster);
    }

    private OptimizationEventEntity event(Long id, String stage, Long abRunId, Long candidatePromptVersionId) {
        OptimizationEventEntity e = new OptimizationEventEntity();
        e.setId(id);
        e.setStage(stage);
        e.setAbRunId(abRunId);
        e.setCandidatePromptVersionId(candidatePromptVersionId);
        return e;
    }

    // --- SkillAbCompletedEvent ---

    @Test
    @DisplayName("skill-ab promoted true → mirrors ab_running → ab_passed and broadcasts")
    void skillAbCompleted_promoted_writesAbPassed() {
        OptimizationEventEntity matched = event(101L, OptimizationEventEntity.STAGE_AB_RUNNING, 555L, null);
        when(eventRepository.findByAbRunId(555L)).thenReturn(List.of(matched));
        when(eventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        listener.onSkillAbCompleted(new SkillAbCompletedEvent(
                /*skillId*/ 700L, /*evolutionAbRunId*/ "555",
                /*promoted*/ true, 0.6, 0.85, "v1", "v2"));

        ArgumentCaptor<OptimizationEventEntity> captor = ArgumentCaptor.forClass(OptimizationEventEntity.class);
        verify(eventRepository).save(captor.capture());
        assertThat(captor.getValue().getStage()).isEqualTo(OptimizationEventEntity.STAGE_AB_PASSED);
        assertThat(captor.getValue().getCandidateSkillId()).isEqualTo(700L);
        verify(broadcaster).broadcastStageTransition(any(), anyString());
    }

    @Test
    @DisplayName("skill-ab promoted false → ab_failed")
    void skillAbCompleted_notPromoted_writesAbFailed() {
        OptimizationEventEntity matched = event(102L, OptimizationEventEntity.STAGE_AB_RUNNING, 556L, null);
        when(eventRepository.findByAbRunId(556L)).thenReturn(List.of(matched));
        when(eventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        listener.onSkillAbCompleted(new SkillAbCompletedEvent(
                700L, "556", false, 0.85, 0.45, "v1", "v2"));

        verify(eventRepository).save(any());
        assertThat(matched.getStage()).isEqualTo(OptimizationEventEntity.STAGE_AB_FAILED);
    }

    @Test
    @DisplayName("skill-ab with no matching event → silent skip (manual / cron A/B not attribution-originated)")
    void skillAbCompleted_noMatch_skips() {
        when(eventRepository.findByAbRunId(999L)).thenReturn(List.of());

        listener.onSkillAbCompleted(new SkillAbCompletedEvent(
                700L, "999", true, 0.6, 0.85, "v1", "v2"));

        verify(eventRepository, never()).save(any());
        verify(broadcaster, never()).broadcastStageTransition(any(), anyString());
    }

    @Test
    @DisplayName("skill-ab event with non-numeric abRunId → skip mirror without DB read")
    void skillAbCompleted_uuidRunId_skips() {
        listener.onSkillAbCompleted(new SkillAbCompletedEvent(
                700L, "8c12d8c4-cf04-4ce3-9a07-bdfb6ec3c9f5",  // UUID, not Long-parseable
                true, 0.6, 0.85, "v1", "v2"));

        verify(eventRepository, never()).findByAbRunId(any());
        verify(eventRepository, never()).save(any());
    }

    @Test
    @DisplayName("skill-ab event but optimization event already in terminal state → skip (no rollback)")
    void skillAbCompleted_notInAbRunning_skips() {
        OptimizationEventEntity matched = event(103L, OptimizationEventEntity.STAGE_PROMOTED, 557L, null);
        when(eventRepository.findByAbRunId(557L)).thenReturn(List.of(matched));

        listener.onSkillAbCompleted(new SkillAbCompletedEvent(
                700L, "557", true, 0.6, 0.85, "v1", "v2"));

        verify(eventRepository, never()).save(any());
        verify(broadcaster, never()).broadcastStageTransition(any(), anyString());
    }

    // --- PromptPromotedEvent ---

    @Test
    @DisplayName("prompt-promoted with numeric versionId + matching event → promoted stage")
    void promptPromoted_match_writesPromoted() {
        OptimizationEventEntity matched = event(201L, OptimizationEventEntity.STAGE_AB_PASSED, null, 888L);
        when(eventRepository.findByCandidatePromptVersionId(888L)).thenReturn(List.of(matched));
        when(eventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        listener.onPromptPromoted(new PromptPromotedEvent(
                /*agentId*/ "9", /*versionId*/ "888",
                /*deltaPassRate*/ 0.25, /*versionNumber*/ 4, /*userId*/ 1L));

        verify(eventRepository).save(any());
        assertThat(matched.getStage()).isEqualTo(OptimizationEventEntity.STAGE_PROMOTED);
        verify(broadcaster).broadcastStageTransition(any(), anyString());
    }

    @Test
    @DisplayName("prompt-promoted with UUID versionId (schema mismatch) → skip without DB read")
    void promptPromoted_uuidVersion_skips() {
        listener.onPromptPromoted(new PromptPromotedEvent(
                "9", "550e8400-e29b-41d4-a716-446655440000", 0.25, 4, 1L));

        verify(eventRepository, never()).findByCandidatePromptVersionId(any());
        verify(eventRepository, never()).save(any());
    }

    @Test
    @DisplayName("prompt-promoted from candidate_ready (operator skipped A/B) → still allows promote")
    void promptPromoted_fromCandidateReady_writesPromoted() {
        OptimizationEventEntity matched = event(202L, OptimizationEventEntity.STAGE_CANDIDATE_READY, null, 889L);
        when(eventRepository.findByCandidatePromptVersionId(889L)).thenReturn(List.of(matched));
        when(eventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        listener.onPromptPromoted(new PromptPromotedEvent("9", "889", 0.0, 1, 1L));

        verify(eventRepository).save(any());
        assertThat(matched.getStage()).isEqualTo(OptimizationEventEntity.STAGE_PROMOTED);
    }

    @Test
    @DisplayName("prompt-promoted but event already promoted / rolled-back → skip")
    void promptPromoted_inWrongStage_skips() {
        OptimizationEventEntity matched = event(203L, OptimizationEventEntity.STAGE_ROLLED_BACK, null, 890L);
        when(eventRepository.findByCandidatePromptVersionId(890L)).thenReturn(List.of(matched));

        listener.onPromptPromoted(new PromptPromotedEvent("9", "890", 0.0, 1, 1L));

        verify(eventRepository, never()).save(any());
    }
}
