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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("NewCommandHandler")
class NewCommandHandlerTest {

    @Mock private SessionService sessionService;
    @Mock private AgentService agentService;

    private NewCommandHandler handler;

    @BeforeEach
    void setUp() {
        handler = new NewCommandHandler(sessionService, agentService);
    }

    @Test
    @DisplayName("no args: inherits agent from current session")
    void noArgs_inheritsCurrentAgent() {
        SessionEntity current = sessionWith("s-1", 7L, 100L);
        SessionEntity created = sessionWith("s-2", 7L, 100L);
        when(sessionService.getSession("s-1")).thenReturn(current);
        when(sessionService.createSession(7L, 100L)).thenReturn(created);

        CommandResult r = handler.execute(7L, "s-1", "", ExecutionContext.web());

        assertThat(r.success()).isTrue();
        assertThat(r.displayMode()).isEqualTo("redirect");
        assertThat(r.newSessionId()).isEqualTo("s-2");
        assertThat(r.message()).contains("已开启");
        verify(sessionService).createSession(7L, 100L);
    }

    @Test
    @DisplayName("agent name arg: switches to user-owned agent by name")
    void argMatchesUserOwnedAgent() {
        SessionEntity current = sessionWith("s-1", 7L, 100L);
        when(sessionService.getSession("s-1")).thenReturn(current);

        AgentEntity userAgent = new AgentEntity();
        userAgent.setId(200L);
        userAgent.setName("researcher");
        when(agentService.listAgents(7L)).thenReturn(List.of(userAgent));

        SessionEntity created = sessionWith("s-99", 7L, 200L);
        when(sessionService.createSession(7L, 200L)).thenReturn(created);

        CommandResult r = handler.execute(7L, "s-1", "researcher", ExecutionContext.web());

        assertThat(r.newSessionId()).isEqualTo("s-99");
        verify(sessionService).createSession(7L, 200L);
    }

    @Test
    @DisplayName("agent name arg: falls back to public agents when not user-owned")
    void argMatchesPublicAgent() {
        SessionEntity current = sessionWith("s-1", 7L, 100L);
        when(sessionService.getSession("s-1")).thenReturn(current);
        when(agentService.listAgents(7L)).thenReturn(List.of());

        AgentEntity pub = new AgentEntity();
        pub.setId(300L);
        pub.setName("public-bot");
        when(agentService.listPublicAgents()).thenReturn(List.of(pub));
        SessionEntity created = sessionWith("s-77", 7L, 300L);
        when(sessionService.createSession(7L, 300L)).thenReturn(created);

        CommandResult r = handler.execute(7L, "s-1", "public-bot", ExecutionContext.web());
        assertThat(r.success()).isTrue();
        assertThat(r.newSessionId()).isEqualTo("s-77");
    }

    @Test
    @DisplayName("agent name arg: unknown name returns error")
    void argUnknownAgent_returnsError() {
        SessionEntity current = sessionWith("s-1", 7L, 100L);
        when(sessionService.getSession("s-1")).thenReturn(current);
        when(agentService.listAgents(7L)).thenReturn(List.of());
        when(agentService.listPublicAgents()).thenReturn(List.of());

        CommandResult r = handler.execute(7L, "s-1", "ghost", ExecutionContext.web());

        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("ghost");
    }

    @Test
    @DisplayName("metadata accessors: name, description, usage are non-blank")
    void metadataAccessors() {
        assertThat(handler.getName()).isEqualTo("new");
        assertThat(handler.getDescription()).isNotBlank();
        assertThat(handler.getUsage()).contains("/new");
    }

    private SessionEntity sessionWith(String id, Long userId, Long agentId) {
        SessionEntity s = new SessionEntity();
        s.setId(id);
        s.setUserId(userId);
        s.setAgentId(agentId);
        return s;
    }
}
