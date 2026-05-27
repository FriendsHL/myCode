package com.skillforge.server.memory.transcript;

import java.time.Instant;
import java.util.List;

public record SessionTranscriptChunk(
        String sessionId,
        Long userId,
        Long agentId,
        Instant completedAt,
        int turnCount,
        String transcript,
        List<SessionTranscriptTurn> turns) {

    public SessionTranscriptChunk {
        turns = turns == null ? List.of() : List.copyOf(turns);
    }

    public SessionTranscriptChunk(String sessionId,
                                  Long userId,
                                  Long agentId,
                                  Instant completedAt,
                                  int turnCount,
                                  String transcript) {
        this(sessionId, userId, agentId, completedAt, turnCount, transcript, List.of());
    }
}
