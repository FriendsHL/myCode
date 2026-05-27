package com.skillforge.server.memory.transcript;

import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.eval.MultiTurnTranscript;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.skill.MultiTurnTranscriptBuilder;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class SessionTranscriptProviderImpl implements SessionTranscriptProvider {

    private static final int MIN_LOOKBACK_DAYS = 1;
    private static final int MAX_LOOKBACK_DAYS = 30;
    private static final int MIN_SESSIONS = 1;
    private static final int MAX_SESSIONS = 20;
    private static final int MIN_CHARS_PER_SESSION = 500;
    private static final int MAX_CHARS_PER_SESSION = 12000;
    private static final String TRUNCATED_MARKER = "\n[truncated]";

    private final SessionRepository sessionRepository;
    private final MultiTurnTranscriptBuilder transcriptBuilder;

    public SessionTranscriptProviderImpl(SessionRepository sessionRepository,
                                         MultiTurnTranscriptBuilder transcriptBuilder) {
        this.sessionRepository = sessionRepository;
        this.transcriptBuilder = transcriptBuilder;
    }

    @Override
    @Transactional(readOnly = true)
    public List<SessionTranscriptChunk> recentTranscripts(Long userId,
                                                          int lookbackDays,
                                                          int maxSessions,
                                                          int maxCharsPerSession) {
        if (userId == null || userId <= 0) {
            return List.of();
        }

        int safeLookbackDays = clamp(lookbackDays, MIN_LOOKBACK_DAYS, MAX_LOOKBACK_DAYS);
        int safeSessions = clamp(maxSessions, MIN_SESSIONS, MAX_SESSIONS);
        int safeChars = clamp(maxCharsPerSession, MIN_CHARS_PER_SESSION, MAX_CHARS_PER_SESSION);

        Instant since = Instant.now().minus(Duration.ofDays(safeLookbackDays));
        List<SessionEntity> sessions = sessionRepository.findRecentProductionSessionsForMemoryDreaming(
                userId, since, PageRequest.of(0, safeSessions));

        List<SessionTranscriptChunk> chunks = new ArrayList<>();
        for (SessionEntity session : sessions) {
            if (session == null || session.getId() == null || session.getId().isBlank()) {
                continue;
            }
            MultiTurnTranscript transcript = transcriptBuilder.fromSession(session.getId());
            if (transcript == null || transcript.isEmpty()) {
                continue;
            }
            String rendered = transcript.render();
            if (rendered == null || rendered.isBlank()) {
                continue;
            }
            chunks.add(new SessionTranscriptChunk(
                    session.getId(),
                    session.getUserId(),
                    session.getAgentId(),
                    session.getCompletedAt(),
                    transcript.getEntries().size(),
                    truncate(rendered, safeChars)
            ));
        }
        return chunks;
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    private static String truncate(String rendered, int maxChars) {
        if (rendered.length() <= maxChars) {
            return rendered;
        }
        return rendered.substring(0, maxChars) + TRUNCATED_MARKER;
    }
}
