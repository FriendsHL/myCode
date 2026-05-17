package com.skillforge.server.memory.llmsynth;

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
 * Unit tests for {@link MemoryCuratorBootstrap} system-prompt swap.
 */
@ExtendWith(MockitoExtension.class)
class MemoryCuratorBootstrapTest {

    @Mock
    private AgentRepository agentRepository;

    private MemoryCuratorBootstrap bootstrap;

    @BeforeEach
    void setUp() {
        bootstrap = new MemoryCuratorBootstrap(agentRepository);
    }

    @Test
    @DisplayName("swap is a no-op when no memory-curator agent row exists")
    void swap_noAgent_isNoop() {
        when(agentRepository.findFirstByName(MemoryCuratorBootstrap.AGENT_NAME))
                .thenReturn(Optional.empty());

        bootstrap.swapSystemPromptOnBoot();

        verify(agentRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("swap replaces SEE_FILE placeholder with classpath resource content")
    void swap_seeFilePlaceholder_replacesWithResourceContent() {
        AgentEntity agent = newAgent("SEE_FILE:memory-curator-system-prompt.md");
        agent.setAgentType("system");  // SYSTEM-AGENT-TYPING Phase 1.1: isolate prompt-swap path from self-heal
        when(agentRepository.findFirstByName(MemoryCuratorBootstrap.AGENT_NAME))
                .thenReturn(Optional.of(agent));

        bootstrap.swapSystemPromptOnBoot();

        ArgumentCaptor<AgentEntity> saved = ArgumentCaptor.forClass(AgentEntity.class);
        verify(agentRepository).save(saved.capture());
        String swapped = saved.getValue().getSystemPrompt();
        assertThat(swapped).doesNotStartWith("SEE_FILE:");
        // Sanity: known content fragments from memory-curator-system-prompt.md
        assertThat(swapped).contains("memory-curator");
        assertThat(swapped).contains("untrusted user data");
    }

    @Test
    @DisplayName("swap leaves operator-edited prompts alone (no SEE_FILE prefix)")
    void swap_alreadyOverridden_doesNotOverwrite() {
        AgentEntity agent = newAgent("operator edited prompt — do not overwrite");
        agent.setAgentType("system");  // SYSTEM-AGENT-TYPING Phase 1.1: prevent self-heal save
        when(agentRepository.findFirstByName(MemoryCuratorBootstrap.AGENT_NAME))
                .thenReturn(Optional.of(agent));

        bootstrap.swapSystemPromptOnBoot();

        verify(agentRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("swap leaves the placeholder alone when the classpath resource is missing")
    void swap_missingResource_doesNotOverwriteWithNull() {
        AgentEntity agent = newAgent("SEE_FILE:does-not-exist.md");
        agent.setAgentType("system");  // SYSTEM-AGENT-TYPING Phase 1.1: prevent self-heal save
        when(agentRepository.findFirstByName(MemoryCuratorBootstrap.AGENT_NAME))
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
        // Simulates a system agent row created before V89 migration ran (or a future
        // system agent whose seed migration didn't get the V89 backfill UPDATE).
        // Default newAgent() agentType is 'user' (the AgentEntity field default).
        AgentEntity agent = newAgent("operator hand-edited — no SEE_FILE");
        assertThat(agent.getAgentType()).isEqualTo("user");
        when(agentRepository.findFirstByName(MemoryCuratorBootstrap.AGENT_NAME))
                .thenReturn(Optional.of(agent));

        bootstrap.swapSystemPromptOnBoot();

        // Self-heal must have flipped agentType + saved. Prompt is untouched because
        // it's not the SEE_FILE placeholder (operator-edited path).
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
        when(agentRepository.findFirstByName(MemoryCuratorBootstrap.AGENT_NAME))
                .thenReturn(Optional.of(agent));

        bootstrap.swapSystemPromptOnBoot();

        // No prompt swap (not SEE_FILE) and no self-heal (already system) → no save.
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
                MemoryCuratorBootstrap.PROMPT_RESOURCE_PATH);
        assertThat(content).isNotNull();
        assertThat(content).contains("memory-curator");
    }

    @Test
    @DisplayName("swap does not crash when the repository lookup throws (e.g. test profile bypassing migration)")
    void swap_repositoryThrows_isSwallowed() {
        when(agentRepository.findFirstByName(MemoryCuratorBootstrap.AGENT_NAME))
                .thenThrow(new RuntimeException("table not yet created"));

        bootstrap.swapSystemPromptOnBoot();   // must not throw

        verify(agentRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    private static AgentEntity newAgent(String prompt) {
        AgentEntity a = new AgentEntity();
        a.setId(101L);
        a.setName(MemoryCuratorBootstrap.AGENT_NAME);
        a.setSystemPrompt(prompt);
        return a;
    }
}
