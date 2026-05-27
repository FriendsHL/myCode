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
    private final MemoryTranscriptProperties properties;

    public SessionTranscriptProviderImpl(SessionRepository sessionRepository,
                                         MultiTurnTranscriptBuilder transcriptBuilder,
                                         MemoryTranscriptProperties properties) {
        this.sessionRepository = sessionRepository;
        this.transcriptBuilder = transcriptBuilder;
        this.properties = properties;
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

        int safeLookbackDays = clamp(lookbackDays, MIN_LOOKBACK_DAYS,
                effectiveMax(properties.getMaxLookbackDays(), MIN_LOOKBACK_DAYS, MAX_LOOKBACK_DAYS));
        int safeSessions = clamp(maxSessions, MIN_SESSIONS,
                effectiveMax(properties.getMaxSessions(), MIN_SESSIONS, MAX_SESSIONS));
        int safeChars = clamp(maxCharsPerSession, MIN_CHARS_PER_SESSION,
                effectiveMax(properties.getMaxCharsPerSession(), MIN_CHARS_PER_SESSION, MAX_CHARS_PER_SESSION));

        Instant since = Instant.now().minus(Duration.ofDays(safeLookbackDays));
        List<SessionTranscriptChunk> chunks = new ArrayList<>();
        int page = 0;
        while (chunks.size() < safeSessions) {
            List<SessionEntity> sessions = sessionRepository.findRecentProductionSessionsForMemoryDreaming(
                    userId, since, PageRequest.of(page, safeSessions));
            if (sessions == null || sessions.isEmpty()) {
                break;
            }
            for (SessionEntity session : sessions) {
                if (chunks.size() >= safeSessions) {
                    break;
                }
                SessionTranscriptChunk chunk = toChunk(session, safeChars);
                if (chunk != null) {
                    chunks.add(chunk);
                }
            }
            if (sessions.size() < safeSessions) {
                break;
            }
            page++;
        }
        return chunks;
    }

    private SessionTranscriptChunk toChunk(SessionEntity session, int safeChars) {
        if (session == null || session.getId() == null || session.getId().isBlank()) {
            return null;
        }
        MultiTurnTranscript transcript = transcriptBuilder.fromSession(session.getId());
        if (transcript == null || transcript.isEmpty()) {
            return null;
        }
        String rendered = transcript.render();
        if (rendered == null || rendered.isBlank()) {
            return null;
        }
        return new SessionTranscriptChunk(
                session.getId(),
                session.getUserId(),
                session.getAgentId(),
                session.getCompletedAt(),
                transcript.getEntries().size(),
                truncate(rendered, safeChars)
        );
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    private static int effectiveMax(int configuredMax, int min, int fallbackMax) {
        if (configuredMax < min) {
            return Math.max(min, fallbackMax);
        }
        return configuredMax;
    }

    private static String truncate(String rendered, int maxChars) {
        if (rendered.length() <= maxChars) {
            return rendered;
        }
        return rendered.substring(0, maxChars) + TRUNCATED_MARKER;
    }
}
