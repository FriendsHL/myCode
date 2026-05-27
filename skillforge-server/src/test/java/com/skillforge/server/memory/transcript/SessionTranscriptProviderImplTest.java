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
import static org.mockito.Mockito.times;
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
        SessionTranscriptProviderImpl provider = provider(new MemoryTranscriptProperties());

        assertThat(provider.recentTranscripts(null, 7, 5, 6000)).isEmpty();
        assertThat(provider.recentTranscripts(0L, 7, 5, 6000)).isEmpty();
        assertThat(provider.recentTranscripts(-1L, 7, 5, 6000)).isEmpty();

        verifyNoInteractions(sessionRepository, transcriptBuilder);
    }

    @Test
    @DisplayName("configured max values control query clamping and page size")
    void recentTranscripts_outOfRangeInputs_usesConfiguredMaxCaps() {
        when(sessionRepository.findRecentProductionSessionsForMemoryDreaming(eq(42L), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of());
        MemoryTranscriptProperties properties = new MemoryTranscriptProperties();
        properties.setMaxLookbackDays(9);
        properties.setMaxSessions(2);
        properties.setMaxCharsPerSession(700);
        SessionTranscriptProviderImpl provider = provider(properties);

        List<SessionTranscriptChunk> chunks = provider.recentTranscripts(42L, 999, 999, 99999);

        assertThat(chunks).isEmpty();
        ArgumentCaptor<Pageable> pageCap = ArgumentCaptor.forClass(Pageable.class);
        ArgumentCaptor<Instant> sinceCap = ArgumentCaptor.forClass(Instant.class);
        verify(sessionRepository).findRecentProductionSessionsForMemoryDreaming(eq(42L), sinceCap.capture(), pageCap.capture());
        assertThat(pageCap.getValue().getPageSize()).isEqualTo(2);
        assertThat(sinceCap.getValue()).isAfter(Instant.now().minusSeconds(10L * 24 * 60 * 60));
        verifyNoInteractions(transcriptBuilder);
    }

    @Test
    @DisplayName("bad configured max values fall back to built-in safe caps")
    void recentTranscripts_badConfiguredMaxValues_fallsBackToSafeCaps() {
        when(sessionRepository.findRecentProductionSessionsForMemoryDreaming(eq(42L), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of());
        MemoryTranscriptProperties properties = new MemoryTranscriptProperties();
        properties.setMaxLookbackDays(0);
        properties.setMaxSessions(0);
        properties.setMaxCharsPerSession(0);
        SessionTranscriptProviderImpl provider = provider(properties);

        provider.recentTranscripts(42L, 999, 999, 99999);

        ArgumentCaptor<Pageable> pageCap = ArgumentCaptor.forClass(Pageable.class);
        ArgumentCaptor<Instant> sinceCap = ArgumentCaptor.forClass(Instant.class);
        verify(sessionRepository).findRecentProductionSessionsForMemoryDreaming(eq(42L), sinceCap.capture(), pageCap.capture());
        assertThat(pageCap.getValue().getPageSize()).isEqualTo(20);
        assertThat(sinceCap.getValue()).isAfter(Instant.now().minusSeconds(31L * 24 * 60 * 60));
    }

    @Test
    @DisplayName("empty transcripts are skipped")
    void recentTranscripts_emptyTranscript_skipped() {
        SessionEntity empty = session("s-empty", 42L, 10L, Instant.parse("2026-05-26T10:00:00Z"));
        when(sessionRepository.findRecentProductionSessionsForMemoryDreaming(eq(42L), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(empty));
        when(transcriptBuilder.fromSession("s-empty")).thenReturn(new MultiTurnTranscript());
        SessionTranscriptProviderImpl provider = provider(new MemoryTranscriptProperties());

        assertThat(provider.recentTranscripts(42L, 7, 5, 6000)).isEmpty();
    }

    @Test
    @DisplayName("continues paging when early candidate transcript is empty")
    void recentTranscripts_firstCandidateEmpty_returnsLaterValidTranscript() {
        SessionEntity empty = session("s-empty", 42L, 10L, Instant.parse("2026-05-26T10:00:00Z"));
        SessionEntity valid = session("s-valid", 42L, 11L, Instant.parse("2026-05-26T09:00:00Z"));
        MultiTurnTranscript validTranscript = new MultiTurnTranscript();
        validTranscript.add("user", "later");

        when(sessionRepository.findRecentProductionSessionsForMemoryDreaming(eq(42L), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(empty), List.of(valid));
        when(transcriptBuilder.fromSession("s-empty")).thenReturn(new MultiTurnTranscript());
        when(transcriptBuilder.fromSession("s-valid")).thenReturn(validTranscript);
        SessionTranscriptProviderImpl provider = provider(new MemoryTranscriptProperties());

        List<SessionTranscriptChunk> chunks = provider.recentTranscripts(42L, 7, 1, 6000);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).sessionId()).isEqualTo("s-valid");
        assertThat(chunks.get(0).transcript()).contains("[user] later");
        ArgumentCaptor<Pageable> pageCap = ArgumentCaptor.forClass(Pageable.class);
        verify(sessionRepository, times(2))
                .findRecentProductionSessionsForMemoryDreaming(eq(42L), any(Instant.class), pageCap.capture());
        assertThat(pageCap.getAllValues()).extracting(Pageable::getPageNumber).containsExactly(0, 1);
        assertThat(pageCap.getAllValues()).extracting(Pageable::getPageSize).containsExactly(1, 1);
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
        SessionTranscriptProviderImpl provider = provider(new MemoryTranscriptProperties());

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

    private SessionTranscriptProviderImpl provider(MemoryTranscriptProperties properties) {
        return new SessionTranscriptProviderImpl(sessionRepository, transcriptBuilder, properties);
    }
}
