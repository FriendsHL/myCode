package com.skillforge.server.service.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.skillforge.core.model.SkillDefinition;
import com.skillforge.core.skill.SkillRegistry;
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

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SkillCommandHandler — list agent skills, tolerate missing")
class SkillCommandHandlerTest {

    @Mock private SessionService sessionService;
    @Mock private AgentService agentService;
    @Mock private SkillRegistry skillRegistry;

    private ObjectMapper objectMapper;
    private SkillCommandHandler handler;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        handler = new SkillCommandHandler(sessionService, agentService, skillRegistry, objectMapper);
    }

    @Test
    @DisplayName("lists agent skills with descriptions resolved from registry")
    void listsAgentSkills() {
        SessionEntity s = sess("sess-1", 100L);
        AgentEntity a = agent("research-bot", "[\"web_search\", \"summarize\"]");
        when(sessionService.getSession("sess-1")).thenReturn(s);
        when(agentService.getAgent(100L)).thenReturn(a);
        when(skillRegistry.getSkillDefinition("web_search"))
                .thenReturn(Optional.of(skillDef("web_search", "Search the web")));
        when(skillRegistry.getSkillDefinition("summarize"))
                .thenReturn(Optional.of(skillDef("summarize", "Summarize text")));

        CommandResult r = handler.execute(7L, "sess-1", "", ExecutionContext.web());

        assertThat(r.success()).isTrue();
        assertThat(r.displayMode()).isEqualTo("modal");
        assertThat(r.markdownBody())
                .contains("web_search").contains("Search the web")
                .contains("summarize").contains("Summarize text");
        assertThat(r.message()).contains("2/2");
    }

    @Test
    @DisplayName("missing skill in registry: rendered as (missing) — does NOT throw")
    void missingSkill_isToleratedWithMarker() {
        SessionEntity s = sess("sess-1", 100L);
        AgentEntity a = agent("a", "[\"good\", \"phantom\"]");
        when(sessionService.getSession("sess-1")).thenReturn(s);
        when(agentService.getAgent(100L)).thenReturn(a);
        when(skillRegistry.getSkillDefinition("good"))
                .thenReturn(Optional.of(skillDef("good", "Good skill")));
        when(skillRegistry.getSkillDefinition("phantom")).thenReturn(Optional.empty());

        CommandResult r = handler.execute(7L, "sess-1", "", ExecutionContext.web());
        assertThat(r.success()).isTrue();
        assertThat(r.markdownBody()).contains("(missing) phantom");
        assertThat(r.message()).contains("1/2");
    }

    @Test
    @DisplayName("agent with empty skill_ids: friendly empty body")
    void emptySkills_friendlyMessage() {
        SessionEntity s = sess("sess-1", 100L);
        AgentEntity a = agent("a", null);
        when(sessionService.getSession("sess-1")).thenReturn(s);
        when(agentService.getAgent(100L)).thenReturn(a);

        CommandResult r = handler.execute(7L, "sess-1", "", ExecutionContext.web());
        assertThat(r.success()).isTrue();
        assertThat(r.markdownBody()).contains("未启用任何 skill");
    }

    private static SessionEntity sess(String id, Long agentId) {
        SessionEntity s = new SessionEntity();
        s.setId(id);
        s.setAgentId(agentId);
        return s;
    }

    private static AgentEntity agent(String name, String skillIdsJson) {
        AgentEntity a = new AgentEntity();
        a.setId(100L);
        a.setName(name);
        a.setSkillIds(skillIdsJson);
        return a;
    }

    private static SkillDefinition skillDef(String name, String desc) {
        SkillDefinition d = new SkillDefinition();
        d.setName(name);
        d.setDescription(desc);
        return d;
    }
}
