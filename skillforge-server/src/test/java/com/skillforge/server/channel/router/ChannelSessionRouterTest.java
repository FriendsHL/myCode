package com.skillforge.server.channel.router;

import com.skillforge.server.channel.event.ChannelSessionOutputEvent;
import com.skillforge.server.channel.registry.ChannelAdapterRegistry;
import com.skillforge.server.channel.spi.ChannelAdapter;
import com.skillforge.server.channel.spi.ChannelConfigDecrypted;
import com.skillforge.server.channel.spi.ChannelMessage;
import com.skillforge.server.dto.CommandResult;
import com.skillforge.server.service.ChatService;
import com.skillforge.server.service.command.ExecutionContext;
import com.skillforge.server.service.command.SlashCommandService;
import com.skillforge.server.websocket.ChatWebSocketHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ChannelSessionRouter — slash-command interception (INV-5/INV-6)")
class ChannelSessionRouterTest {

    private ChannelConversationResolver resolver;
    private ChatService chatService;
    private ChatWebSocketHandler chatWebSocketHandler;
    private ChannelAdapterRegistry adapterRegistry;
    private SlashCommandService slashCommandService;
    private org.springframework.context.ApplicationEventPublisher eventPublisher;
    private ChannelConversationRebindService rebindService;

    private ChannelSessionRouter router;

    @BeforeEach
    void setUp() {
        resolver = mock(ChannelConversationResolver.class);
        chatService = mock(ChatService.class);
        chatWebSocketHandler = mock(ChatWebSocketHandler.class);
        adapterRegistry = mock(ChannelAdapterRegistry.class);
        slashCommandService = mock(SlashCommandService.class);
        eventPublisher = mock(org.springframework.context.ApplicationEventPublisher.class);
        rebindService = mock(ChannelConversationRebindService.class);

        router = new ChannelSessionRouter(
                resolver, chatService, chatWebSocketHandler, adapterRegistry,
                slashCommandService, eventPublisher, rebindService);
    }

    @Test
    @DisplayName("INV-5: non-slash text is forwarded to chatService.chatAsync as before")
    void nonSlashText_goesToChat() throws Exception {
        ChannelMessage msg = newMsg("hello world");
        ChannelConfigDecrypted cfg = newConfig();
        when(resolver.resolveSession(eq(msg), eq(cfg), any()))
                .thenReturn(new SessionRouteResult("sess-1", false, 7L));
        ChannelAdapter adapter = mock(ChannelAdapter.class);
        when(adapterRegistry.get("feishu")).thenReturn(Optional.of(adapter));
        when(adapter.sendAck(msg, cfg)).thenReturn("ack-1");

        invokeRouteInternal(msg, cfg);

        verify(chatService).chatAsync("sess-1", "hello world", 7L);
        verify(slashCommandService, never())
                .execute(anyLong(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("INV-5: text starting with '/' is intercepted — chatAsync is NEVER called")
    void slashText_isIntercepted() throws Exception {
        ChannelMessage msg = newMsg("/help");
        ChannelConfigDecrypted cfg = newConfig();
        when(resolver.resolveSession(eq(msg), eq(cfg), any()))
                .thenReturn(new SessionRouteResult("sess-1", false, 7L));
        when(slashCommandService.execute(eq(7L), eq("sess-1"), eq("/help"), any()))
                .thenReturn(CommandResult.modal("help summary", "# Help\n- /new\n"));

        invokeRouteInternal(msg, cfg);

        verify(slashCommandService).execute(eq(7L), eq("sess-1"), eq("/help"), any(ExecutionContext.class));
        verify(chatService, never()).chatAsync(anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("slash command result → publishes ChannelSessionOutputEvent with reply text")
    void slashResult_publishedAsEvent() throws Exception {
        ChannelMessage msg = newMsg("/help");
        ChannelConfigDecrypted cfg = newConfig();
        when(resolver.resolveSession(eq(msg), eq(cfg), any()))
                .thenReturn(new SessionRouteResult("sess-1", false, 7L));
        when(slashCommandService.execute(eq(7L), eq("sess-1"), eq("/help"), any()))
                .thenReturn(CommandResult.modal("help summary", "# Help\n- /new\n"));

        invokeRouteInternal(msg, cfg);

        org.mockito.ArgumentCaptor<ChannelSessionOutputEvent> cap =
                org.mockito.ArgumentCaptor.forClass(ChannelSessionOutputEvent.class);
        verify(eventPublisher).publishEvent(cap.capture());
        ChannelSessionOutputEvent ev = cap.getValue();
        assertThat(ev.sessionId()).isEqualTo("sess-1");
        assertThat(ev.platformMessageId()).isEqualTo(msg.platformMessageId());
        assertThat(ev.replyText()).contains("# Help"); // markdown body preferred
    }

    @Test
    @DisplayName("INV-6: /new in feishu calls rebindService with (platform, conv, configId, newSessionId)")
    void inv6_feishuNew_callsRebindService() throws Exception {
        ChannelMessage msg = newMsg("/new");
        ChannelConfigDecrypted cfg = newConfig();
        when(resolver.resolveSession(eq(msg), eq(cfg), any()))
                .thenReturn(new SessionRouteResult("sess-1", false, 7L));
        when(slashCommandService.execute(eq(7L), eq("sess-1"), eq("/new"), any()))
                .thenReturn(CommandResult.redirect("sess-2", "已开启新对话"));

        invokeRouteInternal(msg, cfg);

        // Rebind delegated to the @Transactional service — atomic close+save (W1 fix).
        verify(rebindService).rebind("feishu", "oc_abc", 1L, "sess-2");
        // event also published with the redirect message
        verify(eventPublisher).publishEvent(any(ChannelSessionOutputEvent.class));
    }

    @Test
    @DisplayName("/new but rebindService throws → router logs + still publishes channel reply (does not crash)")
    void rebindFailure_isLoggedAndDoesNotCrashRouter() throws Exception {
        ChannelMessage msg = newMsg("/new");
        ChannelConfigDecrypted cfg = newConfig();
        when(resolver.resolveSession(eq(msg), eq(cfg), any()))
                .thenReturn(new SessionRouteResult("sess-1", false, 7L));
        when(slashCommandService.execute(eq(7L), eq("sess-1"), eq("/new"), any()))
                .thenReturn(CommandResult.redirect("sess-2", "已开启新对话"));
        org.mockito.Mockito.doThrow(new RuntimeException("DB down"))
                .when(rebindService).rebind(anyString(), anyString(), anyLong(), anyString());

        invokeRouteInternal(msg, cfg);

        verify(rebindService).rebind("feishu", "oc_abc", 1L, "sess-2");
        // Even though rebind threw, the user still gets a channel reply.
        verify(eventPublisher).publishEvent(any(ChannelSessionOutputEvent.class));
    }

    @Test
    @DisplayName("renderReplyText: failure → '❌ <error>' string")
    void renderReplyText_failure_prefixedWithCross() {
        String t = ChannelSessionRouter.renderReplyText(CommandResult.error("boom"));
        assertThat(t).startsWith("❌").contains("boom");
    }

    @Test
    @DisplayName("renderReplyText: prefers markdownBody when present")
    void renderReplyText_modal_returnsMarkdown() {
        CommandResult r = CommandResult.modal("summary", "# Body");
        assertThat(ChannelSessionRouter.renderReplyText(r)).isEqualTo("# Body");
    }

    @Test
    @DisplayName("renderReplyText: redirect → uses message")
    void renderReplyText_redirect_usesMessage() {
        CommandResult r = CommandResult.redirect("s-2", "已开启新对话");
        assertThat(ChannelSessionRouter.renderReplyText(r)).isEqualTo("已开启新对话");
    }

    private static ChannelMessage newMsg(String text) {
        return new ChannelMessage(
                "feishu",
                "oc_abc",
                "ou_user",
                "om_msg",
                ChannelMessage.MessageType.TEXT,
                text,
                null,
                Instant.now(),
                Map.of());
    }

    private static ChannelConfigDecrypted newConfig() {
        return new ChannelConfigDecrypted(1L, "feishu", null, "{}", "{}", 100L);
    }

    /**
     * routeInternal is private; call it via reflection so we can test the
     * dispatch logic without needing to set up the full @Async runtime.
     */
    private void invokeRouteInternal(ChannelMessage msg, ChannelConfigDecrypted cfg) throws Exception {
        Method m = ChannelSessionRouter.class.getDeclaredMethod(
                "routeInternal", ChannelMessage.class, ChannelConfigDecrypted.class);
        m.setAccessible(true);
        m.invoke(router, msg, cfg);
    }
}
