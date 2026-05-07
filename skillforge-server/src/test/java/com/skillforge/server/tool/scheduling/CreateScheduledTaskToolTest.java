package com.skillforge.server.tool.scheduling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.server.dto.ScheduledTaskRequest;
import com.skillforge.server.entity.ScheduledTaskEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.exception.ScheduledTaskAccessDeniedException;
import com.skillforge.server.service.ScheduledTaskService;
import com.skillforge.server.service.SessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CreateScheduledTaskTool")
class CreateScheduledTaskToolTest {

    @Mock private ScheduledTaskService scheduledTaskService;
    @Mock private SessionService sessionService;

    private CreateScheduledTaskTool tool;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        tool = new CreateScheduledTaskTool(scheduledTaskService, sessionService, objectMapper);
    }

    private SkillContext context(Long userId, String sessionId) {
        SkillContext ctx = new SkillContext();
        ctx.setUserId(userId);
        ctx.setSessionId(sessionId);
        return ctx;
    }

    @Test
    @DisplayName("creates task when input is valid; agent_id falls back to current session's agent")
    void create_success_agentFallback() {
        SessionEntity session = new SessionEntity();
        session.setId("sess-1");
        session.setAgentId(99L);
        when(sessionService.getSession("sess-1")).thenReturn(session);
        ScheduledTaskEntity created = new ScheduledTaskEntity();
        created.setId(1L);
        when(scheduledTaskService.create(eq(7L), any())).thenReturn(created);

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("name", "daily");
        input.put("prompt_template", "summarize");
        input.put("cron_expr", "0 0 9 * * *");

        SkillResult result = tool.execute(input, context(7L, "sess-1"));

        assertThat(result.isSuccess()).isTrue();
        ArgumentCaptor<ScheduledTaskRequest> captor = ArgumentCaptor.forClass(ScheduledTaskRequest.class);
        verify(scheduledTaskService).create(eq(7L), captor.capture());
        assertThat(captor.getValue().getAgentId()).isEqualTo(99L); // fallback applied
        assertThat(captor.getValue().getCronExpr()).isEqualTo("0 0 9 * * *");
    }

    @Test
    @DisplayName("missing userId in context returns validationError")
    void missingUserId_validationError() {
        SkillResult result = tool.execute(Map.of("name", "x", "prompt_template", "y"), context(null, null));
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorType()).isEqualTo(SkillResult.ErrorType.VALIDATION);
    }

    @Test
    @DisplayName("agent_id absent and no session falls through to validationError")
    void noAgentResolution_validationError() {
        SkillResult result = tool.execute(
                Map.of("name", "x", "prompt_template", "y", "cron_expr", "0 0 9 * * *"),
                context(7L, null));
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("agent_id is required");
    }

    @Test
    @DisplayName("cross-user / forbidden mapped to error result")
    void serviceForbidden_mapped() {
        SessionEntity session = new SessionEntity();
        session.setAgentId(99L);
        session.setId("sess-1");
        when(sessionService.getSession("sess-1")).thenReturn(session);
        when(scheduledTaskService.create(eq(7L), any()))
                .thenThrow(new ScheduledTaskAccessDeniedException("nope"));

        SkillResult result = tool.execute(Map.of(
                "name", "x", "prompt_template", "y", "cron_expr", "0 0 9 * * *"),
                context(7L, "sess-1"));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("forbidden");
        assertThat(result.getErrorType()).isEqualTo(SkillResult.ErrorType.EXECUTION);
    }

    @Test
    @DisplayName("explicit channel_target object is forwarded as a Map (contract fix smoke test)")
    void channelTarget_objectForwarded() {
        SessionEntity session = new SessionEntity();
        session.setAgentId(99L);
        session.setId("sess-1");
        when(sessionService.getSession("sess-1")).thenReturn(session);
        ScheduledTaskEntity created = new ScheduledTaskEntity();
        created.setId(1L);
        when(scheduledTaskService.create(eq(7L), any())).thenReturn(created);

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("name", "x");
        input.put("prompt_template", "y");
        input.put("cron_expr", "0 0 9 * * *");
        input.put("channel_target", Map.of("channelType", "feishu", "channelId", "oc_x"));

        SkillResult result = tool.execute(input, context(7L, "sess-1"));

        assertThat(result.isSuccess()).isTrue();
        ArgumentCaptor<ScheduledTaskRequest> captor = ArgumentCaptor.forClass(ScheduledTaskRequest.class);
        verify(scheduledTaskService).create(eq(7L), captor.capture());
        assertThat(captor.getValue().isChannelTargetPresent()).isTrue();
        assertThat(captor.getValue().getChannelTarget())
                .containsEntry("channelType", "feishu")
                .containsEntry("channelId", "oc_x");
    }
}
