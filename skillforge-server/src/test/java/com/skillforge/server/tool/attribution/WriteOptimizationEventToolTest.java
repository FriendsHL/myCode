package com.skillforge.server.tool.attribution;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.server.entity.OptimizationEventEntity;
import com.skillforge.server.repository.OptimizationEventRepository;
import com.skillforge.server.repository.SessionPatternRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WriteOptimizationEventToolTest {

    @Mock private OptimizationEventRepository eventRepository;
    @Mock private SessionPatternRepository patternRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final Instant FIXED_NOW = Instant.parse("2026-05-21T12:00:00Z");
    private WriteOptimizationEventTool tool;

    @BeforeEach
    void setUp() {
        Clock fixed = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        tool = new WriteOptimizationEventTool(eventRepository, patternRepository, objectMapper, fixed);
    }

    private OptimizationEventEntity sampleEvent() {
        OptimizationEventEntity e = new OptimizationEventEntity();
        e.setId(99L);
        e.setPatternId(42L);
        e.setAgentId(7L);
        e.setSurfaceType(OptimizationEventEntity.SURFACE_SKILL);
        e.setStage(OptimizationEventEntity.STAGE_PROPOSAL_PENDING);
        e.setDescription("initial proposal description");
        return e;
    }

    @Test
    @DisplayName("stage transition proposal_pending → proposal_approved persists newStage")
    void execute_stageTransition_persists() throws Exception {
        when(eventRepository.findById(99L)).thenReturn(Optional.of(sampleEvent()));
        ArgumentCaptor<OptimizationEventEntity> captor = ArgumentCaptor.forClass(OptimizationEventEntity.class);
        when(eventRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> in = new HashMap<>();
        in.put("eventId", 99);
        in.put("newStage", OptimizationEventEntity.STAGE_PROPOSAL_APPROVED);
        SkillResult result = tool.execute(in, null);

        assertThat(result.isSuccess()).isTrue();
        OptimizationEventEntity saved = captor.getValue();
        assertThat(saved.getStage()).isEqualTo(OptimizationEventEntity.STAGE_PROPOSAL_APPROVED);

        Map<String, Object> payload = objectMapper.readValue(
                result.getOutput(), new TypeReference<Map<String, Object>>() {});
        assertThat(payload).containsEntry("previousStage", OptimizationEventEntity.STAGE_PROPOSAL_PENDING);
        assertThat(payload).containsEntry("newStage", OptimizationEventEntity.STAGE_PROPOSAL_APPROVED);
    }

    @Test
    @DisplayName("optional fields (candidateSkillId / abRunId / canaryId / description) update when supplied")
    void execute_optionalFields_updated() {
        when(eventRepository.findById(99L)).thenReturn(Optional.of(sampleEvent()));
        ArgumentCaptor<OptimizationEventEntity> captor = ArgumentCaptor.forClass(OptimizationEventEntity.class);
        when(eventRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> in = new HashMap<>();
        in.put("eventId", 99);
        in.put("newStage", OptimizationEventEntity.STAGE_CANDIDATE_CREATED);
        in.put("candidateSkillId", 555);
        in.put("description", "candidate skill draft created from approved proposal");
        SkillResult result = tool.execute(in, null);

        assertThat(result.isSuccess()).isTrue();
        OptimizationEventEntity saved = captor.getValue();
        assertThat(saved.getCandidateSkillId()).isEqualTo(555L);
        assertThat(saved.getDescription()).isEqualTo("candidate skill draft created from approved proposal");
        assertThat(saved.getStage()).isEqualTo(OptimizationEventEntity.STAGE_CANDIDATE_CREATED);
    }

    @Test
    @DisplayName("unknown stage rejected without saving")
    void execute_unknownStage_rejected() {
        Map<String, Object> in = new HashMap<>();
        in.put("eventId", 99);
        in.put("newStage", "not_a_real_stage");

        SkillResult result = tool.execute(in, null);

        assertThat(result.isSuccess()).isFalse();
        verify(eventRepository, never()).save(any());
    }

    @Test
    @DisplayName("MULTI-DIM-ATTRIBUTION: stage transition → proposal_rejected auto-sets 24h cooldown")
    void execute_proposalRejected_autoSetsCooldown() {
        when(eventRepository.findById(99L)).thenReturn(Optional.of(sampleEvent()));
        ArgumentCaptor<OptimizationEventEntity> captor = ArgumentCaptor.forClass(OptimizationEventEntity.class);
        when(eventRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> in = new HashMap<>();
        in.put("eventId", 99);
        in.put("newStage", OptimizationEventEntity.STAGE_PROPOSAL_REJECTED);
        in.put("description", "infrastructure_failure: V3 scope does not cover platform");
        SkillResult result = tool.execute(in, null);

        assertThat(result.isSuccess()).isTrue();
        OptimizationEventEntity saved = captor.getValue();
        assertThat(saved.getStage()).isEqualTo(OptimizationEventEntity.STAGE_PROPOSAL_REJECTED);
        // Critical: cooldown was auto-set so the next hourly dispatcher tick won't re-fire.
        assertThat(saved.getCooldownExpiresAt())
                .isEqualTo(FIXED_NOW.plus(WriteOptimizationEventTool.REJECT_COOLDOWN_DURATION));
    }

    @Test
    @DisplayName("MULTI-DIM-ATTRIBUTION: stage transition that is NOT proposal_rejected does not touch cooldown")
    void execute_nonRejectTransition_doesNotTouchCooldown() {
        OptimizationEventEntity existing = sampleEvent();
        existing.setCooldownExpiresAt(null);
        when(eventRepository.findById(99L)).thenReturn(Optional.of(existing));
        ArgumentCaptor<OptimizationEventEntity> captor = ArgumentCaptor.forClass(OptimizationEventEntity.class);
        when(eventRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> in = new HashMap<>();
        in.put("eventId", 99);
        in.put("newStage", OptimizationEventEntity.STAGE_PROPOSAL_APPROVED);
        tool.execute(in, null);

        // Approve transition must NOT carry the reject auto-cooldown.
        assertThat(captor.getValue().getCooldownExpiresAt()).isNull();
    }

    @Test
    @DisplayName("event not found rejected with LLM-readable error")
    void execute_eventNotFound_rejected() {
        when(eventRepository.findById(anyLong())).thenReturn(Optional.empty());

        Map<String, Object> in = new HashMap<>();
        in.put("eventId", 9999);
        in.put("newStage", OptimizationEventEntity.STAGE_AB_RUNNING);
        SkillResult result = tool.execute(in, null);

        assertThat(result.isSuccess()).isFalse();
        verify(eventRepository, never()).save(any());
    }
}
