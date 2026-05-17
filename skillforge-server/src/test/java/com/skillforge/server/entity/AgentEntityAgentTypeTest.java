package com.skillforge.server.entity;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SYSTEM-AGENT-TYPING Phase 1.1: pure-POJO contract for the new
 * {@code AgentEntity.agentType} field.
 *
 * <p>Covers the in-process invariants (default value / null coercion /
 * round-trip JSON shape) without spinning up Spring context. DB-side
 * constraints (NOT NULL / VARCHAR(16) / chk_agent_type CHECK) live in
 * {@link com.skillforge.server.migration.AgentTypeMigrationIT}.
 */
@DisplayName("AgentEntity.agentType POJO contract")
class AgentEntityAgentTypeTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("default agentType is 'user'")
    void defaultAgentType_isUser() {
        AgentEntity a = new AgentEntity();
        assertThat(a.getAgentType()).isEqualTo("user");
    }

    @Test
    @DisplayName("setAgentType('system') is reflected in getter")
    void setterRoundTrips_system() {
        AgentEntity a = new AgentEntity();
        a.setAgentType("system");
        assertThat(a.getAgentType()).isEqualTo("system");
    }

    @Test
    @DisplayName("setAgentType(null) coerces to default 'user' (safety net for Jackson deserializers that omit the field)")
    void setterCoercesNullToUser() {
        AgentEntity a = new AgentEntity();
        a.setAgentType("system");
        a.setAgentType(null);
        assertThat(a.getAgentType())
                .as("null coercion prevents NOT NULL / CHECK violations at persist time")
                .isEqualTo("user");
    }

    @Test
    @DisplayName("Jackson serializes agentType under camelCase 'agentType' key")
    void jacksonSerialize_emitsAgentTypeKey() throws Exception {
        AgentEntity a = new AgentEntity();
        a.setId(123L);
        a.setName("memory-curator");
        a.setAgentType("system");

        String json = objectMapper.writeValueAsString(a);

        assertThat(json)
                .as("FE schemas.ts AgentSchema relies on the camelCase 'agentType' key — " +
                        "any rename would silently strip the field via Zod parse")
                .contains("\"agentType\":\"system\"");
    }

    @Test
    @DisplayName("Jackson deserializes camelCase 'agentType' back to the field")
    void jacksonDeserialize_acceptsAgentTypeKey() throws Exception {
        String json = "{\"name\":\"main-assistant\",\"agentType\":\"user\"}";

        AgentEntity a = objectMapper.readValue(json, AgentEntity.class);

        assertThat(a.getAgentType()).isEqualTo("user");
        assertThat(a.getName()).isEqualTo("main-assistant");
    }

    @Test
    @DisplayName("Jackson omitting agentType key keeps the field default 'user'")
    void jacksonDeserialize_omittedAgentType_keepsDefault() throws Exception {
        // Backward compat: legacy clients (FE built pre-Phase 1.1) won't send agentType.
        // The entity field default + setter null-coercion combo must keep agentType='user'
        // rather than null, so the NOT NULL DB constraint never trips on legacy payloads.
        String json = "{\"name\":\"legacy-agent\"}";

        AgentEntity a = objectMapper.readValue(json, AgentEntity.class);

        assertThat(a.getAgentType()).isEqualTo("user");
    }
}
