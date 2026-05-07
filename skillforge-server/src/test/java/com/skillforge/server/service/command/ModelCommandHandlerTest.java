package com.skillforge.server.service.command;

import com.skillforge.server.dto.CommandResult;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.service.SessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ModelCommandHandler — INV-4 + INV-8")
class ModelCommandHandlerTest {

    @Mock private SessionService sessionService;
    @Mock private ModelCatalog catalog;

    private ModelCommandHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ModelCommandHandler(sessionService, catalog);
    }

    @Test
    @DisplayName("INV-4: writes ONLY runtime_model_override on the session, never agentId / agent.modelId")
    void inv4_setsOnlyRuntimeOverride() {
        SessionEntity s = new SessionEntity();
        s.setId("sess-1");
        s.setUserId(7L);
        s.setAgentId(100L);
        when(sessionService.getSession("sess-1")).thenReturn(s);
        when(catalog.isAvailable("claude:claude-sonnet-4-20250514")).thenReturn(true);

        CommandResult r = handler.execute(7L, "sess-1",
                "claude:claude-sonnet-4-20250514", ExecutionContext.web());

        assertThat(r.success()).isTrue();
        assertThat(r.modelId()).isEqualTo("claude:claude-sonnet-4-20250514");
        ArgumentCaptor<SessionEntity> cap = ArgumentCaptor.forClass(SessionEntity.class);
        verify(sessionService).saveSession(cap.capture());
        // INV-4: only runtimeModelOverride changed; agentId untouched
        assertThat(cap.getValue().getRuntimeModelOverride())
                .isEqualTo("claude:claude-sonnet-4-20250514");
        assertThat(cap.getValue().getAgentId()).isEqualTo(100L);
    }

    @Test
    @DisplayName("INV-8: rejects modelId not in catalog and does NOT save session")
    void inv8_unknownModel_rejected() {
        when(catalog.isAvailable("ghost-model")).thenReturn(false);

        CommandResult r = handler.execute(7L, "sess-1", "ghost-model", ExecutionContext.web());

        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("ghost-model");
        verify(sessionService, never()).saveSession(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("blank args: returns usage error")
    void blankArgs_returnsUsageError() {
        CommandResult r = handler.execute(7L, "sess-1", "", ExecutionContext.web());
        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("用法");
        verify(sessionService, never()).saveSession(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("metadata: name=model, description, usage")
    void metadata() {
        assertThat(handler.getName()).isEqualTo("model");
        assertThat(handler.getDescription()).isNotBlank();
        assertThat(handler.getUsage()).contains("modelId");
    }
}
