package com.skillforge.server.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CHAT-REASONING-PANEL — verify {@code ChatWebSocketHandler.reasoningDelta} emits
 * the {type:'reasoning_delta', sessionId, delta} payload to all subscribers of the
 * session. Mirrors the existing {@code textDelta} contract so the FE
 * useChatWsEventHandler can branch on {@code evt.type}.
 */
class ChatWebSocketHandlerReasoningDeltaTest {

    private ChatWebSocketHandler handler;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        // ChatWebSocketHandler depends on UserWebSocketHandler / CollabRunRepository /
        // SessionRepository / ApplicationEventPublisher only for non-broadcast paths
        // (collab events / session_status idle channel context). Pass mocks; we only
        // exercise the broadcast path here.
        handler = new ChatWebSocketHandler(
                mock(UserWebSocketHandler.class),
                mock(com.skillforge.server.repository.CollabRunRepository.class),
                mock(com.skillforge.server.repository.SessionRepository.class),
                mock(org.springframework.context.ApplicationEventPublisher.class));
    }

    @Test
    @DisplayName("reasoningDelta emits {type:'reasoning_delta', sessionId, delta} payload")
    void reasoningDelta_emitsPayload() throws Exception {
        String sessionId = "sess-reason-123";
        WebSocketSession ws = mock(WebSocketSession.class);
        when(ws.getUri()).thenReturn(URI.create("ws://localhost/ws/chat/" + sessionId));
        when(ws.isOpen()).thenReturn(true);

        handler.afterConnectionEstablished(ws);

        handler.reasoningDelta(sessionId, "Let me think... ");

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(ws).sendMessage(captor.capture());

        JsonNode payload = objectMapper.readTree(captor.getValue().getPayload());
        assertThat(payload.path("type").asText()).isEqualTo("reasoning_delta");
        assertThat(payload.path("sessionId").asText()).isEqualTo(sessionId);
        assertThat(payload.path("delta").asText()).isEqualTo("Let me think... ");
    }

    @Test
    @DisplayName("reasoningDelta is parallel to textDelta (different type, same shape)")
    void reasoningDelta_parallelShapeToTextDelta() throws Exception {
        String sessionId = "sess-shape-456";
        WebSocketSession ws = mock(WebSocketSession.class);
        when(ws.getUri()).thenReturn(URI.create("ws://localhost/ws/chat/" + sessionId));
        when(ws.isOpen()).thenReturn(true);

        handler.afterConnectionEstablished(ws);

        handler.reasoningDelta(sessionId, "reasoning chunk");
        handler.textDelta(sessionId, "text chunk");

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(ws, times(2)).sendMessage(captor.capture());

        JsonNode reasoningPayload = objectMapper.readTree(captor.getAllValues().get(0).getPayload());
        JsonNode textPayload = objectMapper.readTree(captor.getAllValues().get(1).getPayload());

        // Same field set, different type discriminator + delta value
        assertThat(reasoningPayload.path("type").asText()).isEqualTo("reasoning_delta");
        assertThat(textPayload.path("type").asText()).isEqualTo("text_delta");

        assertThat(reasoningPayload.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("type", "sessionId", "delta");
        assertThat(textPayload.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("type", "sessionId", "delta");

        assertThat(reasoningPayload.path("delta").asText()).isEqualTo("reasoning chunk");
        assertThat(textPayload.path("delta").asText()).isEqualTo("text chunk");
    }

    @Test
    @DisplayName("reasoningDelta no-op when no subscribers (avoids NPE on unknown session)")
    void reasoningDelta_noSubscribers_noOp() {
        // Should not throw — broadcast(...) returns early on empty session set.
        handler.reasoningDelta("unknown-sess", "anything");
    }

    @Test
    @DisplayName("reasoningDelta is closed sessions are skipped (defensive)")
    void reasoningDelta_skipsClosedSessions() throws Exception {
        String sessionId = "sess-closed-789";
        WebSocketSession ws = mock(WebSocketSession.class);
        when(ws.getUri()).thenReturn(URI.create("ws://localhost/ws/chat/" + sessionId));
        when(ws.isOpen()).thenReturn(false); // socket already closed

        handler.afterConnectionEstablished(ws);
        handler.reasoningDelta(sessionId, "data");

        // sendMessage must not be invoked when isOpen()==false
        verify(ws, times(0)).sendMessage(any());
    }
}
