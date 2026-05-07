package com.skillforge.server.tool.scheduling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.server.entity.ScheduledTaskEntity;
import com.skillforge.server.service.ScheduledTaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ListScheduledTasksTool")
class ListScheduledTasksToolTest {

    @Mock private ScheduledTaskService scheduledTaskService;
    private ListScheduledTasksTool tool;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        tool = new ListScheduledTasksTool(scheduledTaskService, objectMapper);
    }

    private SkillContext ctx(Long userId) {
        SkillContext c = new SkillContext();
        c.setUserId(userId);
        return c;
    }

    private ScheduledTaskEntity entity(long id, boolean enabled) {
        ScheduledTaskEntity e = new ScheduledTaskEntity();
        e.setId(id);
        e.setName("t" + id);
        e.setCreatorUserId(7L);
        e.setAgentId(42L);
        e.setEnabled(enabled);
        e.setStatus("idle");
        e.setSessionMode("new");
        e.setConcurrencyPolicy("skip-if-running");
        e.setTimezone("Asia/Shanghai");
        e.setCronExpr("0 0 9 * * *");
        e.setPromptTemplate("p");
        return e;
    }

    @Test
    @DisplayName("returns paginated list with total count")
    void list_basic() throws Exception {
        when(scheduledTaskService.listForUser(7L)).thenReturn(
                List.of(entity(1, true), entity(2, false), entity(3, true)));

        SkillResult result = tool.execute(Map.of(), ctx(7L));

        assertThat(result.isSuccess()).isTrue();
        Map<?, ?> body = objectMapper.readValue(result.getOutput(), Map.class);
        assertThat(body.get("total")).isEqualTo(3);
        List<?> items = (List<?>) body.get("items");
        assertThat(items).hasSize(3);
    }

    @Test
    @DisplayName("enabled_only filter drops disabled rows from the listing AND from total")
    void list_enabledOnly() throws Exception {
        when(scheduledTaskService.listForUser(7L)).thenReturn(
                List.of(entity(1, true), entity(2, false), entity(3, true)));

        SkillResult result = tool.execute(Map.of("enabled_only", true), ctx(7L));

        assertThat(result.isSuccess()).isTrue();
        Map<?, ?> body = objectMapper.readValue(result.getOutput(), Map.class);
        assertThat(body.get("total")).isEqualTo(2);
    }

    @Test
    @DisplayName("limit / offset paginate within the (already-filtered) list")
    void list_pagination() throws Exception {
        when(scheduledTaskService.listForUser(7L)).thenReturn(
                List.of(entity(1, true), entity(2, true), entity(3, true), entity(4, true)));

        SkillResult result = tool.execute(Map.of("limit", 2, "offset", 1), ctx(7L));

        assertThat(result.isSuccess()).isTrue();
        Map<?, ?> body = objectMapper.readValue(result.getOutput(), Map.class);
        assertThat(((List<?>) body.get("items"))).hasSize(2);
        assertThat(body.get("total")).isEqualTo(4);
    }

    @Test
    @DisplayName("missing userId returns validation error")
    void missingUserId_validation() {
        SkillResult result = tool.execute(Map.of(), ctx(null));
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorType()).isEqualTo(SkillResult.ErrorType.VALIDATION);
    }
}
