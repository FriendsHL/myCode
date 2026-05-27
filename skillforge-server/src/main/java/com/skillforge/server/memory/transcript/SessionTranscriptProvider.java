package com.skillforge.server.memory.transcript;

import java.util.List;

public interface SessionTranscriptProvider {
    List<SessionTranscriptChunk> recentTranscripts(Long userId, int lookbackDays, int maxSessions, int maxCharsPerSession);
}
