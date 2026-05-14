package com.skillforge.server.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.AgentDefinition;
import com.skillforge.core.model.SkillDefinition;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.core.skill.view.SessionSkillView;
import com.skillforge.server.canary.CanaryAllocator;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.repository.AgentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SKILL-CANARY-ROLLOUT V2 Phase 1.2 — integration between
 * {@link DefaultSessionSkillResolver} and {@link CanaryAllocator}.
 *
 * <p>Uses Mockito (not Spring) to keep the test fast and focused on the
 * wiring contract: when the new session-aware
 * {@code resolveFor(agentDef, sessionId)} entry point is called, each
 * baseline name in {@code agentDef.skillIds} must pass through the allocator
 * before being looked up in {@link SkillRegistry}.
 *
 * <p>Scenarios:
 * <ol>
 *   <li>Allocator returns baseline (inactive canary) → resolver returns view
 *       containing the baseline skill.</li>
 *   <li>Allocator returns candidate (pct=100) → resolver returns view
 *       containing the candidate skill, baseline NOT in view.</li>
 *   <li>Legacy {@code resolveFor(agentDef)} entry point (no sessionId) →
 *       allocator NOT called (short-circuits to baseline-only path).</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class DefaultSessionSkillResolverCanaryTest {

    @Mock private SkillRegistry skillRegistry;
    @Mock private AgentRepository agentRepository;
    @Mock private CanaryAllocator canaryAllocator;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private DefaultSessionSkillResolver resolver;

    @BeforeEach
    void setUp() {
        // 4-arg constructor — Phase 1.2 review W1 mandatory fix replaced the
        // field-injection + setter test seam with constructor injection.
        resolver = new DefaultSessionSkillResolver(skillRegistry, agentRepository,
                objectMapper, canaryAllocator);
        // System-skill stream empty by default — tests focus on user-bound path.
        lenient().when(skillRegistry.getAllSkillDefinitions()).thenReturn(List.of());
        // Agent disabled-system-skills lookup not needed for these cases.
        lenient().when(agentRepository.findById(eq(42L))).thenReturn(Optional.<AgentEntity>empty());
    }

    private SkillDefinition skillDef(String name) {
        SkillDefinition d = new SkillDefinition();
        d.setName(name);
        d.setSystem(false);
        return d;
    }

    private AgentDefinition agentWithSkill(String skillName) {
        AgentDefinition agent = new AgentDefinition();
        agent.setId("42");
        agent.setName("test-agent");
        agent.setSkillIds(List.of(skillName));
        return agent;
    }

    @Test
    @DisplayName("session-aware resolve returns baseline view when canary inactive")
    void resolveFor_withSession_returnsBaseline_whenAllocatorReturnsBaseline() {
        AgentDefinition agent = agentWithSkill("my-skill");
        SkillDefinition baseline = skillDef("my-skill");
        when(canaryAllocator.allocate("sess-001", 42L, "my-skill")).thenReturn("my-skill");
        when(skillRegistry.getSkillDefinition("my-skill")).thenReturn(Optional.of(baseline));

        SessionSkillView view = resolver.resolveFor(agent, "sess-001");

        assertThat(view.isAllowed("my-skill")).isTrue();
        assertThat(view.userBoundSkillNames()).containsExactly("my-skill");
        verify(canaryAllocator).allocate("sess-001", 42L, "my-skill");
    }

    @Test
    @DisplayName("session-aware resolve returns candidate view when canary at pct=100")
    void resolveFor_withSession_returnsCandidate_whenAllocatorReturnsCandidate() {
        AgentDefinition agent = agentWithSkill("my-skill");
        SkillDefinition candidate = skillDef("my-skill-v2");
        when(canaryAllocator.allocate("sess-001", 42L, "my-skill")).thenReturn("my-skill-v2");
        when(skillRegistry.getSkillDefinition("my-skill-v2")).thenReturn(Optional.of(candidate));

        SessionSkillView view = resolver.resolveFor(agent, "sess-001");

        assertThat(view.isAllowed("my-skill-v2")).isTrue();
        assertThat(view.isAllowed("my-skill")).isFalse();
        assertThat(view.userBoundSkillNames()).containsExactly("my-skill-v2");
    }

    @Test
    @DisplayName("session-aware resolve falls back to baseline when candidate not registered")
    void resolveFor_withSession_failsSafeToBaseline_whenCandidateMissingFromRegistry() {
        AgentDefinition agent = agentWithSkill("my-skill");
        SkillDefinition baseline = skillDef("my-skill");
        when(canaryAllocator.allocate("sess-001", 42L, "my-skill")).thenReturn("ghost-skill");
        when(skillRegistry.getSkillDefinition("ghost-skill")).thenReturn(Optional.empty());
        when(skillRegistry.getSkillDefinition("my-skill")).thenReturn(Optional.of(baseline));

        SessionSkillView view = resolver.resolveFor(agent, "sess-001");

        assertThat(view.isAllowed("my-skill")).isTrue();
        assertThat(view.userBoundSkillNames()).containsExactly("my-skill");
    }

    @Test
    @DisplayName("legacy resolveFor(agentDef) does NOT invoke allocator (no session context)")
    void resolveFor_legacyEntryPoint_doesNotCallAllocator() {
        AgentDefinition agent = agentWithSkill("my-skill");
        SkillDefinition baseline = skillDef("my-skill");
        when(skillRegistry.getSkillDefinition("my-skill")).thenReturn(Optional.of(baseline));

        SessionSkillView view = resolver.resolveFor(agent);

        assertThat(view.isAllowed("my-skill")).isTrue();
        // Allocator must not be invoked from the legacy path — preserves the
        // "no session context → behave like today" contract.
        verify(canaryAllocator, never()).allocate(anyString(), org.mockito.ArgumentMatchers.anyLong(), anyString());
    }

    @Test
    @DisplayName("session-aware resolve still behaves correctly when allocator not wired")
    void resolveFor_withSession_noAllocatorWired_returnsBaseline() {
        // 3-arg backward-compat constructor delegates to 4-arg with allocator=null.
        DefaultSessionSkillResolver bareResolver =
                new DefaultSessionSkillResolver(skillRegistry, agentRepository, objectMapper);
        AgentDefinition agent = agentWithSkill("my-skill");
        SkillDefinition baseline = skillDef("my-skill");
        when(skillRegistry.getSkillDefinition("my-skill")).thenReturn(Optional.of(baseline));

        SessionSkillView view = bareResolver.resolveFor(agent, "sess-001");

        assertThat(view.isAllowed("my-skill")).isTrue();
    }

    @Test
    @DisplayName("session-aware resolve falls back to baseline when allocator throws")
    void resolveFor_withSession_fallsBackToBaseline_whenAllocatorThrows() {
        AgentDefinition agent = agentWithSkill("my-skill");
        SkillDefinition baseline = skillDef("my-skill");
        when(canaryAllocator.allocate(anyString(), any(), eq("my-skill")))
                .thenThrow(new RuntimeException("allocator died"));
        when(skillRegistry.getSkillDefinition("my-skill")).thenReturn(Optional.of(baseline));

        SessionSkillView view = resolver.resolveFor(agent, "sess-001");

        // Resolver's allocateForSkill catches RuntimeException and falls back to
        // the baseline name, so the skill is still allowed in the view.
        assertThat(view.isAllowed("my-skill")).isTrue();
        assertThat(view.userBoundSkillNames()).containsExactly("my-skill");
    }
}
