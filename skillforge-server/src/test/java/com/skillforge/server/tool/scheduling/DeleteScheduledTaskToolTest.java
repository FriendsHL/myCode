package com.skillforge.server.tool.scheduling;

import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.server.exception.ScheduledTaskAccessDeniedException;
import com.skillforge.server.service.ScheduledTaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("DeleteScheduledTaskTool")
class DeleteScheduledTaskToolTest {

    @Mock private ScheduledTaskService scheduledTaskService;
    private DeleteScheduledTaskTool tool;

    @BeforeEach
    void setUp() {
        tool = new DeleteScheduledTaskTool(scheduledTaskService);
    }

    private SkillContext ctx(Long userId) {
        SkillContext c = new SkillContext();
        c.setUserId(userId);
        return c;
    }

    @Test
    @DisplayName("deletes when task_id and userId are present")
    void deletes_success() {
        SkillResult result = tool.execute(Map.of("task_id", 1), ctx(7L));
        assertThat(result.isSuccess()).isTrue();
        verify(scheduledTaskService).delete(1L, 7L);
    }

    @Test
    @DisplayName("missing task_id returns validation error and does NOT call service")
    void missingTaskId_validation() {
        SkillResult result = tool.execute(Map.of(), ctx(7L));
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorType()).isEqualTo(SkillResult.ErrorType.VALIDATION);
        verify(scheduledTaskService, never()).delete(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("forbidden cross-user maps to EXECUTION error")
    void forbidden_mapped() {
        doThrow(new ScheduledTaskAccessDeniedException("nope"))
                .when(scheduledTaskService).delete(1L, 99L);
        SkillResult result = tool.execute(Map.of("task_id", 1), ctx(99L));
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("forbidden");
    }
}
