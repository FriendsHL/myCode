package com.skillforge.server.service.command;

import com.skillforge.core.model.Message;
import com.skillforge.server.dto.CommandResult;
import com.skillforge.server.dto.ContextBreakdownDto;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.service.ContextBreakdownService;
import com.skillforge.server.service.SessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ContextCommandHandler — INV-13 reuses TokenEstimator + INV-14 read-only")
class ContextCommandHandlerTest {

    @Mock private SessionService sessionService;
    @Mock private ContextBreakdownService breakdownService;

    private ContextCommandHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ContextCommandHandler(sessionService, breakdownService);
    }

    @Test
    @DisplayName("renders headline pct + segments + top messages")
    void rendersAllSections() {
        SessionEntity s = new SessionEntity();
        s.setId("sess-1");
        s.setAgentId(100L);
        when(sessionService.getSession("sess-1")).thenReturn(s);

        ContextBreakdownDto dto = new ContextBreakdownDto(
                "sess-1", 5_000L, 100_000L, 5,
                List.of(
                        ContextBreakdownDto.Segment.leaf("system_prompt", "System prompt", 2_000L),
                        ContextBreakdownDto.Segment.leaf("messages", "Messages", 3_000L)));
        when(breakdownService.breakdown(eq(s), any())).thenReturn(dto);

        when(sessionService.getContextMessages("sess-1")).thenReturn(List.of(
                Message.user("hello"),
                Message.assistant("hi there, how can I help you with this very long request"),
                Message.user("short")));

        CommandResult r = handler.execute(7L, "sess-1", "", ExecutionContext.web());
        assertThat(r.success()).isTrue();
        assertThat(r.displayMode()).isEqualTo("modal");
        assertThat(r.message()).contains("5,000 / 100,000");
        assertThat(r.markdownBody())
                .contains("System prompt").contains("Messages")
                .contains("Top 5 largest messages");
    }

    @Test
    @DisplayName("empty messages: still shows segments, friendly note for top messages")
    void emptyMessages_friendlyTop() {
        SessionEntity s = new SessionEntity();
        s.setId("sess-1");
        s.setAgentId(100L);
        when(sessionService.getSession("sess-1")).thenReturn(s);
        ContextBreakdownDto dto = new ContextBreakdownDto(
                "sess-1", 0L, 100_000L, 0,
                List.of(ContextBreakdownDto.Segment.leaf("system_prompt", "System prompt", 0L)));
        when(breakdownService.breakdown(eq(s), any())).thenReturn(dto);
        when(sessionService.getContextMessages("sess-1")).thenReturn(List.of());

        CommandResult r = handler.execute(7L, "sess-1", "", ExecutionContext.web());
        assertThat(r.success()).isTrue();
        assertThat(r.markdownBody()).contains("会话尚无消息");
    }

    @Test
    @DisplayName("metadata: name=context, description, usage")
    void metadata() {
        assertThat(handler.getName()).isEqualTo("context");
        assertThat(handler.getDescription()).isNotBlank();
        assertThat(handler.getUsage()).isEqualTo("/context");
    }
}
