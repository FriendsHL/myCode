package com.skillforge.server.memory.transcript;

import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.eval.MultiTurnTranscript;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.skill.MultiTurnTranscriptBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SessionTranscriptProviderImpl")
class SessionTranscriptProviderImplTest {

    @Mock
    private SessionRepository sessionRepository;

    @Mock
    private MultiTurnTranscriptBuilder transcriptBuilder;

    @Test
    @DisplayName("null and non-positive userId return empty without repository hits")
    void recentTranscripts_invalidUserId_returnsEmptyWithoutRepoHit() {
        SessionTranscriptProviderImpl provider = new SessionTranscriptProviderImpl(sessionRepository, transcriptBuilder);

        assertThat(provider.recentTranscripts(null, 7, 5, 6000)).isEmpty();
        assertThat(provider.recentTranscripts(0L, 7, 5, 6000)).isEmpty();
        assertThat(provider.recentTranscripts(-1L, 7, 5, 6000)).isEmpty();

        verifyNoInteractions(sessionRepository, transcriptBuilder);
    }

    @Test
    @DisplayName("clamps query inputs and page size")
    void recentTranscripts_outOfRangeInputs_clampsPageSizeAndSince() {
        when(sessionRepository.findRecentProductionSessionsForMemoryDreaming(eq(42L), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of());
        SessionTranscriptProviderImpl provider = new SessionTranscriptProviderImpl(sessionRepository, transcriptBuilder);

        List<SessionTranscriptChunk> chunks = provider.recentTranscripts(42L, 999, 999, 99999);

        assertThat(chunks).isEmpty();
        ArgumentCaptor<Pageable> pageCap = ArgumentCaptor.forClass(Pageable.class);
        ArgumentCaptor<Instant> sinceCap = ArgumentCaptor.forClass(Instant.class);
        verify(sessionRepository).findRecentProductionSessionsForMemoryDreaming(eq(42L), sinceCap.capture(), pageCap.capture());
        assertThat(pageCap.getValue().getPageSize()).isEqualTo(20);
        assertThat(sinceCap.getValue()).isAfter(Instant.now().minusSeconds(31L * 24 * 60 * 60));
        verifyNoInteractions(transcriptBuilder);
    }

    @Test
    @DisplayName("empty transcripts are skipped")
    void recentTranscripts_emptyTranscript_skipped() {
        SessionEntity empty = session("s-empty", 42L, 10L, Instant.parse("2026-05-26T10:00:00Z"));
        when(sessionRepository.findRecentProductionSessionsForMemoryDreaming(eq(42L), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(empty));
        when(transcriptBuilder.fromSession("s-empty")).thenReturn(new MultiTurnTranscript());
        SessionTranscriptProviderImpl provider = new SessionTranscriptProviderImpl(sessionRepository, transcriptBuilder);

        assertThat(provider.recentTranscripts(42L, 7, 5, 6000)).isEmpty();
    }

    @Test
    @DisplayName("truncates long transcript and preserves chunk fields")
    void recentTranscripts_longTranscript_truncatesAndMapsFields() {
        Instant completedAt = Instant.parse("2026-05-26T10:00:00Z");
        SessionEntity session = session("s-1", 42L, 10L, completedAt);
        MultiTurnTranscript transcript = new MultiTurnTranscript();
        transcript.add("user", "hello");
        transcript.add("assistant", "a".repeat(700));

        when(sessionRepository.findRecentProductionSessionsForMemoryDreaming(eq(42L), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(session));
        when(transcriptBuilder.fromSession("s-1")).thenReturn(transcript);
        SessionTranscriptProviderImpl provider = new SessionTranscriptProviderImpl(sessionRepository, transcriptBuilder);

        List<SessionTranscriptChunk> chunks = provider.recentTranscripts(42L, 7, 5, 12);

        assertThat(chunks).hasSize(1);
        SessionTranscriptChunk chunk = chunks.get(0);
        assertThat(chunk.sessionId()).isEqualTo("s-1");
        assertThat(chunk.userId()).isEqualTo(42L);
        assertThat(chunk.agentId()).isEqualTo(10L);
        assertThat(chunk.completedAt()).isEqualTo(completedAt);
        assertThat(chunk.turnCount()).isEqualTo(2);
        assertThat(chunk.transcript()).endsWith("\n[truncated]");
        assertThat(chunk.transcript()).startsWith("[user] hello");
    }

    private static SessionEntity session(String id, Long userId, Long agentId, Instant completedAt) {
        SessionEntity s = new SessionEntity();
        s.setId(id);
        s.setUserId(userId);
        s.setAgentId(agentId);
        s.setCompletedAt(completedAt);
        return s;
    }
}
