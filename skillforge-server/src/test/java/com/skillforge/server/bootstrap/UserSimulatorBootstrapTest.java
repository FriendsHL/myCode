package com.skillforge.server.bootstrap;

import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.repository.AgentRepository;
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
 * V5 EVAL-DYNAMIC-USER-SIM Phase 1.2: unit tests for the V85 user-simulator
 * system-prompt bootstrap swap.
 *
 * <p>Structural twin of {@code MemoryCuratorBootstrapTest} —
 * same 7 cases (lookup miss / sentinel swap / operator-edited preserved /
 * missing resource preserved / loadPromptFromClasspath null / present /
 * repository throw swallowed).
 */
@ExtendWith(MockitoExtension.class)
class UserSimulatorBootstrapTest {

    @Mock
    private AgentRepository agentRepository;

    private UserSimulatorBootstrap bootstrap;

    @BeforeEach
    void setUp() {
        bootstrap = new UserSimulatorBootstrap(agentRepository);
    }

    @Test
    @DisplayName("swap is a no-op when no user-simulator agent row exists")
    void swap_noAgent_isNoop() {
        when(agentRepository.findFirstByName(UserSimulatorBootstrap.AGENT_NAME))
                .thenReturn(Optional.empty());

        bootstrap.swapSystemPromptOnBoot();

        verify(agentRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("swap replaces SEE_FILE placeholder with classpath resource content")
    void swap_seeFilePlaceholder_replacesWithResourceContent() {
        AgentEntity agent = newAgent("SEE_FILE:system-agents/user-simulator.system.md");
        agent.setAgentType("system");  // SYSTEM-AGENT-TYPING Phase 1.1: isolate prompt-swap path from self-heal
        when(agentRepository.findFirstByName(UserSimulatorBootstrap.AGENT_NAME))
                .thenReturn(Optional.of(agent));

        bootstrap.swapSystemPromptOnBoot();

        ArgumentCaptor<AgentEntity> saved = ArgumentCaptor.forClass(AgentEntity.class);
        verify(agentRepository).save(saved.capture());
        String swapped = saved.getValue().getSystemPrompt();
        assertThat(swapped).doesNotStartWith("SEE_FILE:");
        // Sanity: known content fragments from user-simulator-system-prompt.md
        assertThat(swapped).contains("用户行为模拟器");
        assertThat(swapped).contains("RecordSimulationResult");
        assertThat(swapped).contains("[TERMINATE]");
    }

    @Test
    @DisplayName("swap leaves operator-edited prompts alone (no SEE_FILE prefix)")
    void swap_alreadyOverridden_doesNotOverwrite() {
        AgentEntity agent = newAgent("operator edited prompt — do not overwrite");
        agent.setAgentType("system");  // SYSTEM-AGENT-TYPING Phase 1.1: prevent self-heal save
        when(agentRepository.findFirstByName(UserSimulatorBootstrap.AGENT_NAME))
                .thenReturn(Optional.of(agent));

        bootstrap.swapSystemPromptOnBoot();

        verify(agentRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("swap leaves the placeholder alone when the classpath resource is missing")
    void swap_missingResource_doesNotOverwriteWithNull() {
        AgentEntity agent = newAgent("SEE_FILE:does-not-exist.md");
        agent.setAgentType("system");  // SYSTEM-AGENT-TYPING Phase 1.1: prevent self-heal save
        when(agentRepository.findFirstByName(UserSimulatorBootstrap.AGENT_NAME))
                .thenReturn(Optional.of(agent));

        bootstrap.swapSystemPromptOnBoot();

        verify(agentRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    // ------------------------------------------------------------------------
    // SYSTEM-AGENT-TYPING Phase 1.1: agent_type self-heal coverage
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("agent_type self-heal: agent with agentType='user' gets flipped to 'system' on boot")
    void swap_agentTypeUserUnseeded_selfHealsToSystem() {
        AgentEntity agent = newAgent("operator hand-edited — no SEE_FILE");
        assertThat(agent.getAgentType()).isEqualTo("user");
        when(agentRepository.findFirstByName(UserSimulatorBootstrap.AGENT_NAME))
                .thenReturn(Optional.of(agent));

        bootstrap.swapSystemPromptOnBoot();

        ArgumentCaptor<AgentEntity> saved = ArgumentCaptor.forClass(AgentEntity.class);
        verify(agentRepository).save(saved.capture());
        assertThat(saved.getValue().getAgentType()).isEqualTo("system");
        assertThat(saved.getValue().getSystemPrompt()).isEqualTo("operator hand-edited — no SEE_FILE");
    }

    @Test
    @DisplayName("agent_type self-heal is idempotent: already-system agent is not re-saved")
    void swap_agentTypeAlreadySystem_noSelfHealSave() {
        AgentEntity agent = newAgent("operator hand-edited — no SEE_FILE");
        agent.setAgentType("system");
        when(agentRepository.findFirstByName(UserSimulatorBootstrap.AGENT_NAME))
                .thenReturn(Optional.of(agent));

        bootstrap.swapSystemPromptOnBoot();

        verify(agentRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("loadPromptFromClasspath returns null for missing resources")
    void loadPromptFromClasspath_missingResource_returnsNull() {
        assertThat(bootstrap.loadPromptFromClasspath("definitely-not-on-classpath-xyz.md")).isNull();
    }

    @Test
    @DisplayName("loadPromptFromClasspath returns content for existing classpath resources")
    void loadPromptFromClasspath_existingResource_returnsContent() {
        String content = bootstrap.loadPromptFromClasspath(
                UserSimulatorBootstrap.PROMPT_RESOURCE_PATH);
        assertThat(content).isNotNull();
        assertThat(content).contains("用户行为模拟器");
    }

    @Test
    @DisplayName("swap does not crash when the repository lookup throws (e.g. test profile bypassing migration)")
    void swap_repositoryThrows_isSwallowed() {
        when(agentRepository.findFirstByName(UserSimulatorBootstrap.AGENT_NAME))
                .thenThrow(new RuntimeException("table not yet created"));

        bootstrap.swapSystemPromptOnBoot();   // must not throw

        verify(agentRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    private static AgentEntity newAgent(String prompt) {
        AgentEntity a = new AgentEntity();
        a.setId(101L);
        a.setName(UserSimulatorBootstrap.AGENT_NAME);
        a.setSystemPrompt(prompt);
        return a;
    }
}
