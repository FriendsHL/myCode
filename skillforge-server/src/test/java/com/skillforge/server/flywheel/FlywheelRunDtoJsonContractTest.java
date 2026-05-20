package com.skillforge.server.flywheel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FLYWHEEL-PER-RUN — Jackson contract roundtrip test for {@link FlywheelRunDto}.
 *
 * <p>Per java.md footgun #6 (FE-BE Jackson contract): any DTO that crosses the
 * wire must be pinned by a roundtrip test asserting (a) field names match the
 * FE TS interface expectation, and (b) types serialize as documented
 * ({@code Instant} → ISO-8601 string, nullable {@code Long} → JSON null).
 *
 * <p>r2 W3 fix: ObjectMapper is built with {@code findAndRegisterModules()}
 * + {@code disable(WRITE_DATES_AS_TIMESTAMPS)} to mirror Spring Boot's
 * autoconfigured {@code Jackson2ObjectMapperBuilder} behaviour
 * (auto-discovers {@code JavaTimeModule} on the classpath via SPI). This is
 * the same shape the production REST stack uses, so a future
 * {@code PropertyNamingStrategy} addition (e.g. snake_case) added at the
 * Spring level would surface here as a failing test (per java.md footgun
 * #1 + #6 double-protection: production ObjectMapper config drifts → wire
 * contract drifts → this test fails).
 *
 * <p>Note: this test does NOT load a Spring context (no {@code @JsonTest})
 * because the DTO is a pure record with no Spring dependencies — the
 * autoconfigured behaviour is faithfully replicated via the SPI module
 * discovery path, keeping the test fast (sub-100ms) and isolated.
 */
@DisplayName("FlywheelRunDto JSON contract")
class FlywheelRunDtoJsonContractTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    @DisplayName("all fields serialize with FE-expected camelCase names")
    void allFields_serialize_withFeNames() throws Exception {
        FlywheelRunDto dto = new FlywheelRunDto(
                101L,
                21L,
                "Code Assistant",
                "skill",
                11L,
                "outcome=fail|surface=skill|tool=Bash",
                "proposal_pending",
                null,
                Instant.parse("2026-05-19T10:00:00Z"),
                Instant.parse("2026-05-20T11:00:00Z"),
                "draft-uuid-a",
                null);

        String json = mapper.writeValueAsString(dto);

        // Field names — these are the contract with FE TypeScript interface.
        assertThat(json).contains("\"optEventId\":101");
        assertThat(json).contains("\"agentId\":21");
        assertThat(json).contains("\"agentName\":\"Code Assistant\"");
        assertThat(json).contains("\"surface\":\"skill\"");
        assertThat(json).contains("\"patternId\":11");
        assertThat(json).contains("\"patternSignature\":\"outcome=fail|surface=skill|tool=Bash\"");
        assertThat(json).contains("\"currentStage\":\"proposal_pending\"");
        assertThat(json).contains("\"errorLabel\":null");
        assertThat(json).contains("\"candidateSkillDraftUuid\":\"draft-uuid-a\"");
        assertThat(json).contains("\"abRunId\":null");
        // Instant serialised as ISO-8601 string (not numeric timestamp).
        assertThat(json).contains("\"startedAt\":\"2026-05-19T10:00:00Z\"");
        assertThat(json).contains("\"lastUpdatedAt\":\"2026-05-20T11:00:00Z\"");
        // No accidental snake_case leak.
        assertThat(json).doesNotContain("\"opt_event_id\"");
        assertThat(json).doesNotContain("\"agent_name\"");
        assertThat(json).doesNotContain("\"pattern_signature\"");
        assertThat(json).doesNotContain("\"current_stage\"");
    }

    @Test
    @DisplayName("roundtrip preserves all field values")
    void roundtrip_preservesFields() throws Exception {
        FlywheelRunDto in = new FlywheelRunDto(
                42L, 7L, "agent", "prompt", 3L, "sig", "ab_failed",
                "A/B test failed",
                Instant.parse("2026-05-01T00:00:00Z"),
                Instant.parse("2026-05-02T00:00:00Z"),
                null, 555L);
        String json = mapper.writeValueAsString(in);
        FlywheelRunDto out = mapper.readValue(json, FlywheelRunDto.class);
        assertThat(out).isEqualTo(in);
    }
}
