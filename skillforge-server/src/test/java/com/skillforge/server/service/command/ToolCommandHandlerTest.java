package com.skillforge.server.service.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.server.dto.CommandResult;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.service.AgentService;
import com.skillforge.server.service.SessionService;
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
@DisplayName("ToolCommandHandler — INV-12 includes system built-ins")
class ToolCommandHandlerTest {

    @Mock private SessionService sessionService;
    @Mock private AgentService agentService;
    @Mock private SkillRegistry skillRegistry;

    private ObjectMapper objectMapper;
    private ToolCommandHandler handler;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        handler = new ToolCommandHandler(sessionService, agentService, skillRegistry, objectMapper);
    }

    @Test
    @DisplayName("INV-12: lists ALL registered tools when agent.toolIds is empty (no whitelist)")
    void inv12_systemBuiltins_includedWhenNoWhitelist() {
        SessionEntity s = sess("sess-1", 100L);
        AgentEntity a = agent("bot", null);
        when(sessionService.getSession("sess-1")).thenReturn(s);
        when(agentService.getAgent(100L)).thenReturn(a);

        Tool bash = fakeTool("Bash", "Run shell commands");
        Tool fileRead = fakeTool("FileRead", "Read a file");
        Tool customX = fakeTool("CustomX", "user-defined tool");
        when(skillRegistry.getAllTools()).thenReturn(List.of(bash, fileRead, customX));

        CommandResult r = handler.execute(7L, "sess-1", "", ExecutionContext.web());

        assertThat(r.success()).isTrue();
        // System-classification: Bash + FileRead are system built-ins; CustomX is "user".
        assertThat(r.markdownBody())
                .contains("Bash").contains("FileRead").contains("CustomX")
                .contains("system").contains("user");
        assertThat(r.message()).contains("Tool 数: 3");
    }

    @Test
    @DisplayName("agent.toolIds whitelist: only listed tools render")
    void whitelist_filters() {
        SessionEntity s = sess("sess-1", 100L);
        AgentEntity a = agent("bot", "[\"Bash\"]");
        when(sessionService.getSession("sess-1")).thenReturn(s);
        when(agentService.getAgent(100L)).thenReturn(a);

        when(skillRegistry.getAllTools()).thenReturn(List.of(
                fakeTool("Bash", "shell"),
                fakeTool("FileRead", "read"),
                fakeTool("Memory", "memory ops")));

        CommandResult r = handler.execute(7L, "sess-1", "", ExecutionContext.web());
        assertThat(r.markdownBody()).contains("Bash");
        assertThat(r.markdownBody()).doesNotContain("| 🔧 | `FileRead`");
        assertThat(r.markdownBody()).doesNotContain("| 🔧 | `Memory`");
        assertThat(r.message()).contains("Tool 数: 1");
    }

    @Test
    @DisplayName("empty registry: friendly message")
    void emptyRegistry() {
        SessionEntity s = sess("sess-1", 100L);
        AgentEntity a = agent("bot", null);
        when(sessionService.getSession("sess-1")).thenReturn(s);
        when(agentService.getAgent(100L)).thenReturn(a);
        when(skillRegistry.getAllTools()).thenReturn(List.of());

        CommandResult r = handler.execute(7L, "sess-1", "", ExecutionContext.web());
        assertThat(r.success()).isTrue();
        assertThat(r.markdownBody()).contains("没有可用 tool");
    }

    private static SessionEntity sess(String id, Long agentId) {
        SessionEntity s = new SessionEntity();
        s.setId(id);
        s.setAgentId(agentId);
        return s;
    }

    private static AgentEntity agent(String name, String toolIdsJson) {
        AgentEntity a = new AgentEntity();
        a.setId(100L);
        a.setName(name);
        a.setToolIds(toolIdsJson);
        return a;
    }

    private static Tool fakeTool(String name, String desc) {
        return new Tool() {
            @Override public String getName() { return name; }
            @Override public String getDescription() { return desc; }
            @Override public ToolSchema getToolSchema() { return null; }
            @Override public SkillResult execute(Map<String, Object> input, SkillContext context) {
                throw new UnsupportedOperationException();
            }
        };
    }
}
