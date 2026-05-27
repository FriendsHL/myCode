package com.skillforge.server.memory.transcript;

public record SessionTranscriptTurn(
        Long seqNo,
        String role,
        String text) {
}
