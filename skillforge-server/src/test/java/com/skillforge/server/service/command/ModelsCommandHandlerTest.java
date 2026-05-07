package com.skillforge.server.service.command;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ModelsCommandHandler — list catalog + mark current (INV-14 read-only)")
class ModelsCommandHandlerTest {

    @Mock private ModelCatalog catalog;
    @Mock private SessionService sessionService;
    @Mock private AgentService agentService;

    private ModelsCommandHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ModelsCommandHandler(catalog, sessionService, agentService);
    }

    @Test
    @DisplayName("lists every catalog entry as markdown rows")
    void listsAllCatalogModels() {
        SessionEntity s = new SessionEntity();
        s.setId("sess-1");
        s.setAgentId(100L);
        when(sessionService.getSession("sess-1")).thenReturn(s);
        AgentEntity agent = new AgentEntity();
        agent.setId(100L);
        agent.setModelId("claude:claude-sonnet-4-20250514");
        when(agentService.getAgent(100L)).thenReturn(agent);

        when(catalog.listAll()).thenReturn(List.of(
                new ModelCatalog.ModelEntry("claude", "claude-sonnet-4-20250514", true),
                new ModelCatalog.ModelEntry("openai", "gpt-4o", false),
                new ModelCatalog.ModelEntry("openai", "deepseek-chat", false)
        ));

        CommandResult r = handler.execute(7L, "sess-1", "", ExecutionContext.web());

        assertThat(r.success()).isTrue();
        assertThat(r.displayMode()).isEqualTo("modal");
        assertThat(r.markdownBody()).contains("claude-sonnet-4-20250514")
                .contains("gpt-4o")
                .contains("deepseek-chat");
    }

    @Test
    @DisplayName("marks the session's runtime override model with ✅ (preferred over agent default)")
    void marksCurrentBySessionOverride() {
        SessionEntity s = new SessionEntity();
        s.setId("sess-1");
        s.setAgentId(100L);
        s.setRuntimeModelOverride("openai:gpt-4o");
        when(sessionService.getSession("sess-1")).thenReturn(s);

        when(catalog.listAll()).thenReturn(List.of(
                new ModelCatalog.ModelEntry("claude", "claude-sonnet-4-20250514", true),
                new ModelCatalog.ModelEntry("openai", "gpt-4o", false)
        ));

        CommandResult r = handler.execute(7L, "sess-1", "", ExecutionContext.web());

        // The line for gpt-4o should carry the ✅ marker (the body has multiple lines).
        String body = r.markdownBody();
        assertThat(body).contains("✅");
        assertThat(r.message()).contains("openai:gpt-4o");
        // We did NOT call agentService — runtime override short-circuits the lookup.
        verify(agentService, never()).getAgent(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("falls back to agent.modelId when no session override is set")
    void marksCurrentByAgentDefault() {
        SessionEntity s = new SessionEntity();
        s.setId("sess-1");
        s.setAgentId(100L);
        when(sessionService.getSession("sess-1")).thenReturn(s);
        AgentEntity agent = new AgentEntity();
        agent.setId(100L);
        agent.setModelId("claude:claude-sonnet-4-20250514");
        when(agentService.getAgent(100L)).thenReturn(agent);

        when(catalog.listAll()).thenReturn(List.of(
                new ModelCatalog.ModelEntry("claude", "claude-sonnet-4-20250514", true)
        ));

        CommandResult r = handler.execute(7L, "sess-1", "", ExecutionContext.web());
        assertThat(r.message()).contains("claude:claude-sonnet-4-20250514");
        assertThat(r.markdownBody()).contains("✅");
    }

    @Test
    @DisplayName("empty catalog → friendly message, still success")
    void emptyCatalog_friendlyMessage() {
        SessionEntity s = new SessionEntity();
        s.setId("sess-1");
        s.setAgentId(100L);
        when(sessionService.getSession("sess-1")).thenReturn(s);
        AgentEntity agent = new AgentEntity();
        agent.setId(100L);
        when(agentService.getAgent(100L)).thenReturn(agent);

        when(catalog.listAll()).thenReturn(List.of());

        CommandResult r = handler.execute(7L, "sess-1", "", ExecutionContext.web());
        assertThat(r.success()).isTrue();
        assertThat(r.markdownBody()).contains("未配置");
    }

    @Test
    @DisplayName("INV-14: read-only — no saveSession call regardless of input")
    void inv14_noWrites() {
        SessionEntity s = new SessionEntity();
        s.setId("sess-1");
        s.setAgentId(100L);
        when(sessionService.getSession("sess-1")).thenReturn(s);
        AgentEntity agent = new AgentEntity();
        agent.setId(100L);
        when(agentService.getAgent(100L)).thenReturn(agent);
        when(catalog.listAll()).thenReturn(List.of());

        handler.execute(7L, "sess-1", "", ExecutionContext.web());

        verify(sessionService, never()).saveSession(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("metadata: name=models, description, usage")
    void metadata() {
        assertThat(handler.getName()).isEqualTo("models");
        assertThat(handler.getDescription()).isNotBlank();
        assertThat(handler.getUsage()).isEqualTo("/models");
    }
}
