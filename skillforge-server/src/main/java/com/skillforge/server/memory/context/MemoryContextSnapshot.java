package com.skillforge.server.memory.context;

import java.util.Set;

public record MemoryContextSnapshot(
        Long userId,
        String taskContext,
        String rendered,
        Set<Long> memoryIds,
        String contextHash) {
}
