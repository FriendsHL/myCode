package com.skillforge.server.repository;

import com.skillforge.server.AbstractPostgresIT;
import com.skillforge.server.entity.AgentEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SYSTEM-AGENT-TYPING Phase 1.1: integration coverage for the
 * {@link AgentRepository#findByAgentType(String)} derived query, used by
 * Phase 1.2 session-annotator queue prioritization and by Phase 2 admin
 * surfaces (FE filter / observability page).
 *
 * <p>Spring Data JPA derives the query from the method name — this test
 * locks the actual SQL behavior (returns only rows where
 * {@code agent_type} matches exactly, default 'user' on un-set inserts).
 */
@DisplayName("AgentRepository.findByAgentType")
class AgentRepositoryFindByAgentTypeIT extends AbstractPostgresIT {

    @Autowired
    private AgentRepository agentRepository;

    @Test
    @DisplayName("findByAgentType('user') returns only user-typed agents")
    void findByAgentType_user_returnsOnlyUserAgents() {
        agentRepository.deleteAll();

        AgentEntity userA = save("alpha", "user");
        AgentEntity userB = save("beta", null);             // null setter → coerced to 'user'
        AgentEntity systemC = save("gamma", "system");

        List<AgentEntity> userAgents = agentRepository.findByAgentType("user");

        assertThat(userAgents).extracting(AgentEntity::getName)
                .as("only agents with agent_type='user' returned (including the null-coerced 'user' default)")
                .containsExactlyInAnyOrder(userA.getName(), userB.getName());
        assertThat(userAgents).extracting(AgentEntity::getName)
                .doesNotContain(systemC.getName());
    }

    @Test
    @DisplayName("findByAgentType('system') returns only system-typed agents")
    void findByAgentType_system_returnsOnlySystemAgents() {
        agentRepository.deleteAll();

        save("alpha", "user");
        AgentEntity systemB = save("memory-curator-test", "system");
        AgentEntity systemC = save("session-annotator-test", "system");

        List<AgentEntity> systemAgents = agentRepository.findByAgentType("system");

        assertThat(systemAgents).extracting(AgentEntity::getName)
                .containsExactlyInAnyOrder(systemB.getName(), systemC.getName());
    }

    @Test
    @DisplayName("findByAgentType returns empty list for un-matched values (defense — no SQL error)")
    void findByAgentType_unknownValue_returnsEmpty() {
        agentRepository.deleteAll();
        save("alpha", "user");
        save("beta", "system");

        // 'guest' is not in the chk_agent_type CHECK enum, so no row should exist
        // with this value (insert would fail). But the query itself must remain safe
        // — just returns empty.
        List<AgentEntity> result = agentRepository.findByAgentType("guest");

        assertThat(result).isEmpty();
    }

    // ────────────────────────────────────────────────────────────────────────
    // helpers
    // ────────────────────────────────────────────────────────────────────────

    private AgentEntity save(String name, String agentType) {
        AgentEntity a = new AgentEntity();
        a.setName(name);
        a.setMcpServerIds("");
        a.setDisabledSystemSkills("[]");
        if (agentType != null) {
            a.setAgentType(agentType);
        }
        return agentRepository.save(a);
    }
}
