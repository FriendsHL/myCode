package com.skillforge.server.tool.scheduling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.server.dto.ScheduledTaskRequest;
import com.skillforge.server.entity.ScheduledTaskEntity;
import com.skillforge.server.exception.ScheduledTaskAccessDeniedException;
import com.skillforge.server.service.ScheduledTaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("UpdateScheduledTaskTool")
class UpdateScheduledTaskToolTest {

    @Mock private ScheduledTaskService scheduledTaskService;
    private UpdateScheduledTaskTool tool;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        tool = new UpdateScheduledTaskTool(scheduledTaskService, objectMapper);
    }

    private SkillContext userCtx(Long userId) {
        SkillContext ctx = new SkillContext();
        ctx.setUserId(userId);
        return ctx;
    }

    @Test
    @DisplayName("updates only the fields that are present in input (partial patch)")
    void partialPatch() {
        ScheduledTaskEntity updated = new ScheduledTaskEntity();
        updated.setId(1L);
        when(scheduledTaskService.update(eq(1L), eq(7L), any())).thenReturn(updated);

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("task_id", 1);
        input.put("name", "renamed");

        SkillResult result = tool.execute(input, userCtx(7L));

        assertThat(result.isSuccess()).isTrue();
        ArgumentCaptor<ScheduledTaskRequest> captor = ArgumentCaptor.forClass(ScheduledTaskRequest.class);
        verify(scheduledTaskService).update(eq(1L), eq(7L), captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("renamed");
        assertThat(captor.getValue().getCronExpr()).isNull();
        assertThat(captor.getValue().isCronExprPresent()).isFalse();
        assertThat(captor.getValue().isOneShotAtPresent()).isFalse();
    }

    @Test
    @DisplayName("cron-to-one-shot conversion: explicit null cron + new oneShotAt sets both flags")
    void cronToOneShot_setsBothFlags() {
        ScheduledTaskEntity updated = new ScheduledTaskEntity();
        updated.setId(1L);
        when(scheduledTaskService.update(eq(1L), eq(7L), any())).thenReturn(updated);

        // HashMap allows explicit null values which Map.of forbids.
        Map<String, Object> input = new HashMap<>();
        input.put("task_id", 1);
        input.put("cron_expr", null);
        input.put("one_shot_at", "2026-12-25T09:00:00Z");

        SkillResult result = tool.execute(input, userCtx(7L));

        assertThat(result.isSuccess()).isTrue();
        ArgumentCaptor<ScheduledTaskRequest> captor = ArgumentCaptor.forClass(ScheduledTaskRequest.class);
        verify(scheduledTaskService).update(eq(1L), eq(7L), captor.capture());
        assertThat(captor.getValue().isCronExprPresent()).isTrue();
        assertThat(captor.getValue().getCronExpr()).isNull();
        assertThat(captor.getValue().isOneShotAtPresent()).isTrue();
        assertThat(captor.getValue().getOneShotAt()).isNotNull();
    }

    @Test
    @DisplayName("forbidden cross-user maps to EXECUTION error")
    void forbidden_mapped() {
        when(scheduledTaskService.update(eq(1L), eq(99L), any()))
                .thenThrow(new ScheduledTaskAccessDeniedException("nope"));

        SkillResult result = tool.execute(Map.of("task_id", 1, "name", "x"), userCtx(99L));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("forbidden");
    }

    @Test
    @DisplayName("missing task_id returns validationError")
    void missingTaskId_validation() {
        SkillResult result = tool.execute(Map.of("name", "x"), userCtx(7L));
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorType()).isEqualTo(SkillResult.ErrorType.VALIDATION);
    }
}
