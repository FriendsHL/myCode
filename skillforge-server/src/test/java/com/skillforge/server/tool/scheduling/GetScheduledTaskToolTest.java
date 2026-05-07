package com.skillforge.server.tool.scheduling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.server.entity.ScheduledTaskEntity;
import com.skillforge.server.entity.ScheduledTaskRunEntity;
import com.skillforge.server.exception.ScheduledTaskAccessDeniedException;
import com.skillforge.server.service.ScheduledTaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("GetScheduledTaskTool")
class GetScheduledTaskToolTest {

    @Mock private ScheduledTaskService scheduledTaskService;
    private GetScheduledTaskTool tool;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        tool = new GetScheduledTaskTool(scheduledTaskService, objectMapper);
    }

    private SkillContext ctx(Long userId) {
        SkillContext c = new SkillContext();
        c.setUserId(userId);
        return c;
    }

    private ScheduledTaskEntity entity(long id) {
        ScheduledTaskEntity e = new ScheduledTaskEntity();
        e.setId(id);
        e.setName("t");
        e.setCreatorUserId(7L);
        e.setAgentId(42L);
        e.setEnabled(true);
        e.setStatus("idle");
        e.setSessionMode("new");
        e.setConcurrencyPolicy("skip-if-running");
        e.setTimezone("Asia/Shanghai");
        e.setCronExpr("0 0 9 * * *");
        e.setPromptTemplate("p");
        return e;
    }

    private ScheduledTaskRunEntity run(long id, long taskId) {
        ScheduledTaskRunEntity r = new ScheduledTaskRunEntity();
        r.setId(id);
        r.setTaskId(taskId);
        r.setTriggeredAt(Instant.parse("2026-05-07T00:00:00Z"));
        r.setStatus("success");
        return r;
    }

    @Test
    @DisplayName("get returns task without runs by default")
    void get_basic() throws Exception {
        when(scheduledTaskService.get(1L, 7L)).thenReturn(entity(1L));

        SkillResult result = tool.execute(Map.of("task_id", 1), ctx(7L));

        assertThat(result.isSuccess()).isTrue();
        Map<?, ?> body = objectMapper.readValue(result.getOutput(), Map.class);
        assertThat(body.get("id")).isEqualTo(1);
        assertThat(body.get("recent_runs")).isNull();
    }

    @Test
    @DisplayName("include_recent_runs=true embeds run history")
    void get_withRuns() throws Exception {
        when(scheduledTaskService.get(1L, 7L)).thenReturn(entity(1L));
        when(scheduledTaskService.listRuns(eq(1L), eq(7L), eq(10), eq(0)))
                .thenReturn(List.of(run(100L, 1L), run(101L, 1L)));

        SkillResult result = tool.execute(Map.of("task_id", 1, "include_recent_runs", true), ctx(7L));

        assertThat(result.isSuccess()).isTrue();
        Map<?, ?> body = objectMapper.readValue(result.getOutput(), Map.class);
        assertThat(((List<?>) body.get("recent_runs"))).hasSize(2);
    }

    @Test
    @DisplayName("forbidden cross-user maps to EXECUTION error")
    void forbidden_mapped() {
        when(scheduledTaskService.get(1L, 99L))
                .thenThrow(new ScheduledTaskAccessDeniedException("nope"));

        SkillResult result = tool.execute(Map.of("task_id", 1), ctx(99L));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("forbidden");
    }

    @Test
    @DisplayName("missing task_id returns validation error")
    void missingTaskId_validation() {
        SkillResult result = tool.execute(Map.of(), ctx(7L));
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorType()).isEqualTo(SkillResult.ErrorType.VALIDATION);
    }
}
