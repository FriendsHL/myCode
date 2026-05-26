package com.skillforge.server.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CHAT-REASONING-PANEL — pure-POJO contract for {@code AgentEntity.thinkingVisible}.
 *
 * <p>The field is nullable on purpose: {@code null} means "follow global default
 * (collapsed)", {@code true} means "default expanded", {@code false} means
 * "explicitly collapsed". FE resolves null with {@code agent.thinkingVisible ?? false}.
 *
 * <p>Covered here without Spring context:
 *  - default value is {@code null}
 *  - setters round-trip true / false / null
 *  - Jackson emits camelCase {@code thinkingVisible} matching the FE TS contract
 *    (java.md footgun #6)
 *  - omitted key deserializes back to {@code null} (legacy clients stay backward-compatible)
 */
@DisplayName("AgentEntity.thinkingVisible POJO contract")
class AgentEntityThinkingVisibleTest {

    // java.md footgun #1: register JavaTimeModule + disable WRITE_DATES_AS_TIMESTAMPS even
    // though current cases leave createdAt/updatedAt null. Future case additions that touch
    // those LocalDateTime fields would otherwise silently emit wrong timestamps.
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    @DisplayName("default thinkingVisible is null (follow global default)")
    void defaultThinkingVisible_isNull() {
        AgentEntity a = new AgentEntity();
        assertThat(a.getThinkingVisible()).isNull();
    }

    @Test
    @DisplayName("setter round-trips true / false / null")
    void setterRoundTrips() {
        AgentEntity a = new AgentEntity();

        a.setThinkingVisible(Boolean.TRUE);
        assertThat(a.getThinkingVisible()).isTrue();

        a.setThinkingVisible(Boolean.FALSE);
        assertThat(a.getThinkingVisible()).isFalse();

        a.setThinkingVisible(null);
        assertThat(a.getThinkingVisible()).isNull();
    }

    @Test
    @DisplayName("Jackson serializes thinkingVisible=true under camelCase key")
    void jacksonSerialize_true() throws Exception {
        AgentEntity a = new AgentEntity();
        a.setId(42L);
        a.setName("reasoning-agent");
        a.setThinkingVisible(Boolean.TRUE);

        String json = objectMapper.writeValueAsString(a);
        JsonNode node = objectMapper.readTree(json);

        // FE TS interface uses camelCase `thinkingVisible?: boolean | null`. Any rename
        // (e.g. snake_case `thinking_visible`) would silently break the contract since
        // Jackson would emit a key the FE doesn't read. java.md footgun #6 self-check.
        assertThat(node.has("thinkingVisible")).isTrue();
        assertThat(node.path("thinkingVisible").isBoolean()).isTrue();
        assertThat(node.path("thinkingVisible").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("Jackson serializes thinkingVisible=null as JSON null (not omitted)")
    void jacksonSerialize_null() throws Exception {
        AgentEntity a = new AgentEntity();
        a.setName("default-agent");
        // thinkingVisible left null

        String json = objectMapper.writeValueAsString(a);
        JsonNode node = objectMapper.readTree(json);

        // Field must be present (even when null) so FE distinguishes
        // "agent has no preference" (null → resolve to global default) from
        // "agent was loaded from a pre-V119 cache that doesn't know the field".
        assertThat(node.has("thinkingVisible")).isTrue();
        assertThat(node.path("thinkingVisible").isNull()).isTrue();
    }

    @Test
    @DisplayName("Jackson deserializes camelCase thinkingVisible=true")
    void jacksonDeserialize_true() throws Exception {
        String json = "{\"name\":\"x\",\"thinkingVisible\":true}";

        AgentEntity a = objectMapper.readValue(json, AgentEntity.class);

        assertThat(a.getThinkingVisible()).isTrue();
    }

    @Test
    @DisplayName("Jackson deserializes camelCase thinkingVisible=null")
    void jacksonDeserialize_explicitNull() throws Exception {
        String json = "{\"name\":\"x\",\"thinkingVisible\":null}";

        AgentEntity a = objectMapper.readValue(json, AgentEntity.class);

        assertThat(a.getThinkingVisible()).isNull();
    }

    @Test
    @DisplayName("Jackson omitting thinkingVisible keeps field null (backward-compat with pre-V119 clients, existing rows have null)")
    void jacksonDeserialize_omitted_keepsNull() throws Exception {
        // Legacy FE built before this migration won't send thinkingVisible.
        // Must not blow up; must default to null (≡ collapsed).
        String json = "{\"name\":\"legacy-agent\"}";

        AgentEntity a = objectMapper.readValue(json, AgentEntity.class);

        assertThat(a.getThinkingVisible()).isNull();
    }
}
