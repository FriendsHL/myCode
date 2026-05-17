package com.skillforge.server.bootstrap;

import com.skillforge.server.attribution.AttributionCuratorBootstrap;
import com.skillforge.server.canary.MetricsCollectorBootstrap;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.sessionannotation.SessionAnnotatorBootstrap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SYSTEM-AGENT-TYPING Phase 1.1: agent_type self-heal coverage for the 3
 * bootstrap classes that don't have a dedicated *BootstrapTest yet
 * (MetricsCollectorBootstrap / SessionAnnotatorBootstrap /
 *  AttributionCuratorBootstrap). MemoryCuratorBootstrap and
 * UserSimulatorBootstrap have full unit-test files of their own
 * (extended with self-heal cases in this same PR).
 *
 * <p>All 5 bootstraps share identical self-heal code (see
 * {@code MemoryCuratorBootstrap.swapSystemPromptOnBoot}):
 * <pre>
 *   if (!"system".equals(agent.getAgentType())) {
 *       agent.setAgentType("system");
 *       agentRepository.save(agent);
 *   }
 * </pre>
 *
 * <p>This file locks the contract for the 3 less-tested bootstraps with the
 * minimum cases needed: (a) flips user → system + saves; (b) already system
 * → no extra save. Full prompt-swap behavior is covered by the structural
 * twin {@code MemoryCuratorBootstrapTest} and need not be duplicated.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("System agent bootstrap — agent_type self-heal contract")
class SystemAgentBootstrapAgentTypeSelfHealTest {

    @Mock
    private AgentRepository agentRepository;

    @BeforeEach
    void resetMock() {
        // No-op: MockitoExtension resets between tests.
    }

    // ────────────────────────────────────────────────────────────────────────
    // MetricsCollectorBootstrap
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("MetricsCollectorBootstrap: agentType='user' is self-healed to 'system'")
    void metricsCollector_selfHealsUserToSystem() {
        MetricsCollectorBootstrap bootstrap = new MetricsCollectorBootstrap(agentRepository);
        AgentEntity agent = newAgent(MetricsCollectorBootstrap.AGENT_NAME, "edited prompt");
        when(agentRepository.findFirstByName(MetricsCollectorBootstrap.AGENT_NAME))
                .thenReturn(Optional.of(agent));

        bootstrap.swapSystemPromptOnBoot();

        ArgumentCaptor<AgentEntity> saved = ArgumentCaptor.forClass(AgentEntity.class);
        verify(agentRepository).save(saved.capture());
        assertThat(saved.getValue().getAgentType()).isEqualTo("system");
    }

    @Test
    @DisplayName("MetricsCollectorBootstrap: already-system agent is not re-saved")
    void metricsCollector_alreadySystemIsNoop() {
        MetricsCollectorBootstrap bootstrap = new MetricsCollectorBootstrap(agentRepository);
        AgentEntity agent = newAgent(MetricsCollectorBootstrap.AGENT_NAME, "edited prompt");
        agent.setAgentType("system");
        when(agentRepository.findFirstByName(MetricsCollectorBootstrap.AGENT_NAME))
                .thenReturn(Optional.of(agent));

        bootstrap.swapSystemPromptOnBoot();

        verify(agentRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    // ────────────────────────────────────────────────────────────────────────
    // SessionAnnotatorBootstrap
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("SessionAnnotatorBootstrap: agentType='user' is self-healed to 'system'")
    void sessionAnnotator_selfHealsUserToSystem() {
        SessionAnnotatorBootstrap bootstrap = new SessionAnnotatorBootstrap(agentRepository);
        AgentEntity agent = newAgent(SessionAnnotatorBootstrap.AGENT_NAME, "edited prompt");
        when(agentRepository.findFirstByName(SessionAnnotatorBootstrap.AGENT_NAME))
                .thenReturn(Optional.of(agent));

        bootstrap.swapSystemPromptOnBoot();

        ArgumentCaptor<AgentEntity> saved = ArgumentCaptor.forClass(AgentEntity.class);
        verify(agentRepository).save(saved.capture());
        assertThat(saved.getValue().getAgentType()).isEqualTo("system");
    }

    @Test
    @DisplayName("SessionAnnotatorBootstrap: already-system agent is not re-saved")
    void sessionAnnotator_alreadySystemIsNoop() {
        SessionAnnotatorBootstrap bootstrap = new SessionAnnotatorBootstrap(agentRepository);
        AgentEntity agent = newAgent(SessionAnnotatorBootstrap.AGENT_NAME, "edited prompt");
        agent.setAgentType("system");
        when(agentRepository.findFirstByName(SessionAnnotatorBootstrap.AGENT_NAME))
                .thenReturn(Optional.of(agent));

        bootstrap.swapSystemPromptOnBoot();

        verify(agentRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    // ────────────────────────────────────────────────────────────────────────
    // AttributionCuratorBootstrap
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AttributionCuratorBootstrap: agentType='user' is self-healed to 'system'")
    void attributionCurator_selfHealsUserToSystem() {
        AttributionCuratorBootstrap bootstrap = new AttributionCuratorBootstrap(agentRepository);
        AgentEntity agent = newAgent(AttributionCuratorBootstrap.AGENT_NAME, "edited prompt");
        when(agentRepository.findFirstByName(AttributionCuratorBootstrap.AGENT_NAME))
                .thenReturn(Optional.of(agent));

        bootstrap.swapSystemPromptOnBoot();

        ArgumentCaptor<AgentEntity> saved = ArgumentCaptor.forClass(AgentEntity.class);
        verify(agentRepository).save(saved.capture());
        assertThat(saved.getValue().getAgentType()).isEqualTo("system");
    }

    @Test
    @DisplayName("AttributionCuratorBootstrap: already-system agent is not re-saved")
    void attributionCurator_alreadySystemIsNoop() {
        AttributionCuratorBootstrap bootstrap = new AttributionCuratorBootstrap(agentRepository);
        AgentEntity agent = newAgent(AttributionCuratorBootstrap.AGENT_NAME, "edited prompt");
        agent.setAgentType("system");
        when(agentRepository.findFirstByName(AttributionCuratorBootstrap.AGENT_NAME))
                .thenReturn(Optional.of(agent));

        bootstrap.swapSystemPromptOnBoot();

        verify(agentRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    // ────────────────────────────────────────────────────────────────────────
    // helpers
    // ────────────────────────────────────────────────────────────────────────

    private static AgentEntity newAgent(String name, String prompt) {
        AgentEntity a = new AgentEntity();
        a.setId(99L);
        a.setName(name);
        a.setSystemPrompt(prompt);
        // agentType defaults to "user" (AgentEntity field default).
        return a;
    }
}
