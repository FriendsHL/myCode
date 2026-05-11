package com.skillforge.server.memory.hook;

import com.skillforge.core.engine.hook.HookEvent;
import com.skillforge.core.engine.hook.HookExecutionContext;
import com.skillforge.core.engine.hook.HookRunResult;
import com.skillforge.server.entity.MemoryProposalEntity;
import com.skillforge.server.repository.MemoryProposalRepository;
import com.skillforge.server.websocket.UserWebSocketHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MemoryProposalReadyBroadcaster}.
 */
@ExtendWith(MockitoExtension.class)
class MemoryProposalReadyBroadcasterTest {

    @Mock
    private MemoryProposalRepository proposalRepository;

    @Mock
    private UserWebSocketHandler userWebSocketHandler;

    private MemoryProposalReadyBroadcaster broadcaster;

    private static final HookExecutionContext CTX = new HookExecutionContext(
            "sess-curator-1", 0L, HookEvent.SESSION_END,
            Map.of("_hook_origin", "lifecycle:SessionEnd"));

    @BeforeEach
    void setUp() {
        broadcaster = new MemoryProposalReadyBroadcaster(proposalRepository, userWebSocketHandler);
    }

    @Test
    @DisplayName("ref returns builtin.memory.proposal-ready-broadcaster")
    void ref_returnsExpected() {
        assertThat(broadcaster.ref()).isEqualTo("builtin.memory.proposal-ready-broadcaster");
    }

    @Test
    @DisplayName("execute is a no-op when no proposals are pending")
    void execute_zeroPending_doesNotBroadcast() {
        when(proposalRepository.countByStatus(MemoryProposalEntity.STATUS_PROPOSED)).thenReturn(0L);

        HookRunResult result = broadcaster.execute(Map.of(), CTX);

        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("no proposals pending");
        verify(userWebSocketHandler, never()).broadcast(anyLong(), any());
    }

    @Test
    @DisplayName("execute broadcasts to every connected user when proposals are pending")
    void execute_pendingWithConnectedUsers_broadcastsToAll() {
        when(proposalRepository.countByStatus(MemoryProposalEntity.STATUS_PROPOSED)).thenReturn(3L);
        when(userWebSocketHandler.connectedUserIds()).thenReturn(Set.of(1L, 2L));

        HookRunResult result = broadcaster.execute(Map.of(), CTX);

        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("count=3");

        ArgumentCaptor<Map<String, Object>> payloadCaptor = payloadCaptor();
        verify(userWebSocketHandler, times(2)).broadcast(anyLong(), payloadCaptor.capture());
        Map<String, Object> payload = payloadCaptor.getValue();
        assertThat(payload.get("type")).isEqualTo(MemoryProposalReadyBroadcaster.PAYLOAD_TYPE);
        assertThat(payload.get("count")).isEqualTo(3L);
        assertThat(payload.get("sessionId")).isEqualTo("sess-curator-1");
        assertThat((String) payload.get("message")).contains("3");
    }

    @Test
    @DisplayName("execute is silent when proposals exist but nobody is connected")
    void execute_pendingButNoConnectedUsers_skipsBroadcast() {
        when(proposalRepository.countByStatus(MemoryProposalEntity.STATUS_PROPOSED)).thenReturn(2L);
        when(userWebSocketHandler.connectedUserIds()).thenReturn(Set.of());

        HookRunResult result = broadcaster.execute(Map.of(), CTX);

        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("no connected ws clients");
        verify(userWebSocketHandler, never()).broadcast(anyLong(), any());
    }

    @Test
    @DisplayName("execute reports failure cleanly when count query throws")
    void execute_repositoryThrows_returnsFailure() {
        when(proposalRepository.countByStatus(MemoryProposalEntity.STATUS_PROPOSED))
                .thenThrow(new RuntimeException("db down"));

        HookRunResult result = broadcaster.execute(Map.of(), CTX);

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("count query failed");
        verify(userWebSocketHandler, never()).broadcast(anyLong(), any());
    }

    @Test
    @DisplayName("execute swallows per-user broadcast errors and still reports success")
    void execute_perUserBroadcastFails_continuesAndReportsSuccess() {
        when(proposalRepository.countByStatus(MemoryProposalEntity.STATUS_PROPOSED)).thenReturn(1L);
        when(userWebSocketHandler.connectedUserIds()).thenReturn(Set.of(1L, 2L));
        // First broadcast throws; second succeeds. Hook continues per FailurePolicy.continue
        // semantics — operator gets at least one delivery + a warn log for the failed one.
        org.mockito.Mockito.doThrow(new RuntimeException("ws closed"))
                .when(userWebSocketHandler).broadcast(eq(1L), any());
        // 2L: no-op (default void answer)

        HookRunResult result = broadcaster.execute(Map.of(), CTX);

        assertThat(result.success()).isTrue();
        verify(userWebSocketHandler, times(2)).broadcast(anyLong(), any());
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Map<String, Object>> payloadCaptor() {
        return ArgumentCaptor.forClass((Class<Map<String, Object>>) (Class<?>) Map.class);
    }
}
