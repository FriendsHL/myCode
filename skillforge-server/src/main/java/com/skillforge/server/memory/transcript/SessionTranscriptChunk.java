package com.skillforge.server.memory.transcript;

import java.time.Instant;

public record SessionTranscriptChunk(
        String sessionId,
        Long userId,
        Long agentId,
        Instant completedAt,
        int turnCount,
        String transcript) {
}
