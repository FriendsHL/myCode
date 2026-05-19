package com.skillforge.server.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * chat-msg-timestamps: Jackson roundtrip coverage for {@link SessionMessageDto#createdAt()}
 * per {@code .claude/rules/java.md} footgun #6 (FE-BE Jackson contract).
 *
 * <p>Validates:
 * <ol>
 *   <li>The FE-expected camelCase key {@code "createdAt"} is present in the serialized JSON</li>
 *   <li>{@code Instant} serializes as ISO-8601 string (not unix timestamp)</li>
 *   <li>Roundtrip preserves the original {@code Instant} value byte-equally</li>
 *   <li>Backward-compat constructors default {@code createdAt} to null without breaking
 *       existing 9-arg / 8-arg / 5-arg callers</li>
 * </ol>
 */
@DisplayName("SessionMessageDto")
class SessionMessageDtoTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    @DisplayName("createdAt: FE contract — ISO-8601 string under camelCase key")
    void createdAt_serializesAsIso8601StringUnderCamelCaseKey() throws Exception {
        Instant ts = Instant.parse("2026-05-19T10:30:45.123Z");
        SessionMessageDto dto = new SessionMessageDto(
                42L, "user", "hello", "NORMAL", "normal",
                null, null, Map.of(), "trace-x", ts);

        String json = mapper.writeValueAsString(dto);

        assertThat(json).contains("\"createdAt\":");
        assertThat(json).contains("\"2026-05-19T10:30:45.123Z\"");
        // No accidental snake_case or timestamp-as-number leakage
        assertThat(json).doesNotContain("\"created_at\":");
        assertThat(json).doesNotContain("\"createdAt\":1");
    }

    @Test
    @DisplayName("createdAt: Jackson roundtrip preserves Instant value")
    void createdAt_jsonRoundtrip_preservesInstant() throws Exception {
        Instant ts = Instant.parse("2026-05-19T10:30:45.123Z");
        SessionMessageDto original = new SessionMessageDto(
                42L, "user", "hello", "NORMAL", "normal",
                null, null, Map.of("k", "v"), "trace-x", ts);

        String json = mapper.writeValueAsString(original);
        SessionMessageDto roundtripped = mapper.readValue(json, SessionMessageDto.class);

        assertThat(roundtripped.createdAt()).isEqualTo(ts);
        assertThat(roundtripped.seqNo()).isEqualTo(42L);
        assertThat(roundtripped.role()).isEqualTo("user");
        assertThat(roundtripped.traceId()).isEqualTo("trace-x");
    }

    @Test
    @DisplayName("createdAt: null serializes without breaking and roundtrips as null")
    void createdAt_nullValue_roundtrips() throws Exception {
        SessionMessageDto dto = new SessionMessageDto(
                1L, "assistant", "hi", "NORMAL", "normal",
                null, null, Map.of(), "trace-y", null);

        String json = mapper.writeValueAsString(dto);
        SessionMessageDto rt = mapper.readValue(json, SessionMessageDto.class);

        assertThat(rt.createdAt()).isNull();
    }

    @Test
    @DisplayName("backward-compat 9-arg constructor: defaults createdAt to null")
    void backwardCompat_9arg_defaultsCreatedAtToNull() {
        SessionMessageDto dto = new SessionMessageDto(
                1L, "user", "x", "NORMAL", "normal",
                null, null, Map.of(), "trace-z");

        assertThat(dto.createdAt()).isNull();
        assertThat(dto.traceId()).isEqualTo("trace-z");
    }

    @Test
    @DisplayName("backward-compat 8-arg constructor: defaults traceId + createdAt to null")
    void backwardCompat_8arg_defaultsTraceIdAndCreatedAtToNull() {
        SessionMessageDto dto = new SessionMessageDto(
                1L, "user", "x", "NORMAL", "normal",
                null, null, Map.of());

        assertThat(dto.traceId()).isNull();
        assertThat(dto.createdAt()).isNull();
    }

    @Test
    @DisplayName("backward-compat 5-arg constructor: defaults all optional fields to null")
    void backwardCompat_5arg_defaultsOptionalFieldsToNull() {
        SessionMessageDto dto = new SessionMessageDto(
                1L, "user", "x", "NORMAL", Map.of());

        assertThat(dto.messageType()).isEqualTo("normal");
        assertThat(dto.controlId()).isNull();
        assertThat(dto.answeredAt()).isNull();
        assertThat(dto.traceId()).isNull();
        assertThat(dto.createdAt()).isNull();
    }
}
