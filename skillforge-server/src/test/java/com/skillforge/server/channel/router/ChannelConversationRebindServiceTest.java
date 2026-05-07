package com.skillforge.server.channel.router;

import com.skillforge.server.entity.ChannelConversationEntity;
import com.skillforge.server.repository.ChannelConversationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Coverage for the W1 fix: {@link ChannelConversationRebindService#rebind} is
 * marked {@code @Transactional} so the close + insert pair runs atomically.
 *
 * <p>The actual rollback semantics are enforced by Spring's AOP advice + the
 * transaction manager — we cannot exercise that in a pure unit test. Instead
 * we assert:
 * <ol>
 *   <li>the bean's {@code rebind} method bears {@code @Transactional} (reflection
 *       check — spec compliance, not behaviour)</li>
 *   <li>operation order: {@code findActiveForUpdate} → {@code closeById} →
 *       {@code save} (all three are repo calls, so when wrapped by Spring TX
 *       advice they share a single tx)</li>
 *   <li>when the active row is missing, only {@code save} runs (no NPE,
 *       idempotent)</li>
 *   <li>when {@code save} throws, the exception propagates (so AOP advice can
 *       trigger rollback)</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ChannelConversationRebindService — atomic close+save (W1)")
class ChannelConversationRebindServiceTest {

    @Mock private ChannelConversationRepository repo;

    private ChannelConversationRebindService service;

    @BeforeEach
    void setUp() {
        service = new ChannelConversationRebindService(repo);
    }

    @Test
    @DisplayName("W1: rebind() is annotated @Transactional so AOP wraps close+save in one tx")
    void rebind_methodIs_transactional() throws NoSuchMethodException {
        Method m = ChannelConversationRebindService.class.getMethod(
                "rebind", String.class, String.class, Long.class, String.class);
        Transactional tx = m.getAnnotation(Transactional.class);
        assertThat(tx)
                .as("rebind() must carry @Transactional so close+save share one tx — see W1 fix")
                .isNotNull();
    }

    @Test
    @DisplayName("rebind closes existing active row and inserts new row pointing to newSessionId")
    void rebind_closeThenSave() {
        ChannelConversationEntity active = new ChannelConversationEntity();
        active.setId(42L);
        active.setPlatform("feishu");
        active.setConversationId("oc_abc");
        active.setSessionId("sess-1");
        when(repo.findActiveForUpdate("feishu", "oc_abc")).thenReturn(Optional.of(active));

        service.rebind("feishu", "oc_abc", 7L, "sess-2");

        verify(repo).closeById(eq(42L), any(Instant.class));
        ArgumentCaptor<ChannelConversationEntity> cap =
                ArgumentCaptor.forClass(ChannelConversationEntity.class);
        verify(repo).save(cap.capture());
        ChannelConversationEntity saved = cap.getValue();
        assertThat(saved.getPlatform()).isEqualTo("feishu");
        assertThat(saved.getConversationId()).isEqualTo("oc_abc");
        assertThat(saved.getSessionId()).isEqualTo("sess-2");
        assertThat(saved.getChannelConfigId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("rebind skips closeById when no active row exists, still inserts new row")
    void rebind_noActiveRow_insertsOnly() {
        when(repo.findActiveForUpdate("feishu", "oc_abc")).thenReturn(Optional.empty());

        service.rebind("feishu", "oc_abc", 7L, "sess-2");

        verify(repo, never()).closeById(any(Long.class), any(Instant.class));
        verify(repo).save(any(ChannelConversationEntity.class));
    }

    @Test
    @DisplayName("save() failure propagates so the surrounding @Transactional advice rolls back the close")
    void rebind_savePropagatesForRollback() {
        ChannelConversationEntity active = new ChannelConversationEntity();
        active.setId(42L);
        when(repo.findActiveForUpdate("feishu", "oc_abc")).thenReturn(Optional.of(active));
        when(repo.save(any(ChannelConversationEntity.class)))
                .thenThrow(new RuntimeException("DB down"));

        assertThatThrownBy(() -> service.rebind("feishu", "oc_abc", 7L, "sess-2"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("DB down");

        // closeById was attempted before the failing save — Spring's @Transactional advice
        // (in production) will roll back the closeById on this exception.
        verify(repo).closeById(eq(42L), any(Instant.class));
        verify(repo).save(any(ChannelConversationEntity.class));
    }
}
