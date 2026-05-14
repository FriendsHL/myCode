package com.skillforge.server.skill;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.AgentDefinition;
import com.skillforge.core.model.SkillDefinition;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.core.skill.view.SessionSkillResolver;
import com.skillforge.core.skill.view.SessionSkillView;
import com.skillforge.server.canary.CanaryAllocator;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.repository.AgentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Plan r2 §5 — DefaultSessionSkillResolver。
 * 解析逻辑：
 * <ol>
 *   <li>system skills = SkillRegistry 中所有 {@code def.isSystem() == true}，
 *       减去 agent.disabledSystemSkills 集合；</li>
 *   <li>user skills = 解析 agent.skillIds JSON，逐个查 SkillRegistry，
 *       命中即加入；同名 system skill 优先（已经被 system loader registerSkillDefinition 覆盖到 registry）；</li>
 *   <li>合并去重（system 优先），构造不可变 {@link SessionSkillView}。</li>
 * </ol>
 */
@Component
public class DefaultSessionSkillResolver implements SessionSkillResolver {

    private static final Logger log = LoggerFactory.getLogger(DefaultSessionSkillResolver.class);

    private final SkillRegistry skillRegistry;
    private final AgentRepository agentRepository;
    private final ObjectMapper objectMapper;
    /**
     * SKILL-CANARY-ROLLOUT V2 Phase 1.2 — nullable canary allocator. Final
     * field, constructor-injected (java.md 强制构造器注入). May be {@code null}
     * for legacy / test callers that use the 3-arg backward-compat constructor;
     * when null the resolver behaves as if every skill has rolloutPercentage=100
     * baseline (the pre-V2 "一刀切" default).
     */
    private final CanaryAllocator canaryAllocator;

    /**
     * 3-arg backward-compat constructor — preserves existing call sites
     * (legacy tests, manual wiring) that don't know about CanaryAllocator.
     * Delegates to the 4-arg constructor with {@code null} allocator.
     */
    public DefaultSessionSkillResolver(SkillRegistry skillRegistry,
                                       AgentRepository agentRepository,
                                       ObjectMapper objectMapper) {
        this(skillRegistry, agentRepository, objectMapper, null);
    }

    /**
     * 4-arg constructor — Spring auto-wires this one. {@code @Autowired(required=false)}
     * on the constructor (not the field) keeps the bean instantiable even when no
     * {@link CanaryAllocator} bean is present in the application context (e.g.
     * test slices that exclude the canary package).
     */
    @Autowired(required = false)
    public DefaultSessionSkillResolver(SkillRegistry skillRegistry,
                                       AgentRepository agentRepository,
                                       ObjectMapper objectMapper,
                                       CanaryAllocator canaryAllocator) {
        this.skillRegistry = skillRegistry;
        this.agentRepository = agentRepository;
        this.objectMapper = objectMapper;
        this.canaryAllocator = canaryAllocator;
    }

    @Override
    public SessionSkillView resolveFor(AgentDefinition agentDef) {
        return resolveFor(agentDef, null);
    }

    @Override
    public SessionSkillView resolveFor(AgentDefinition agentDef, String sessionId) {
        if (agentDef == null) {
            return SessionSkillView.EMPTY;
        }

        // disabled system skills set — best-effort, on JSON parse failure treat as empty.
        Set<String> disabledSystem = loadDisabledSystemSkills(agentDef);

        Map<String, SkillDefinition> allowed = new LinkedHashMap<>();
        Set<String> systemEnabledNames = new LinkedHashSet<>();
        Set<String> userBoundNames = new LinkedHashSet<>();

        // 1) System skills (loaded by SystemSkillLoader; flag set on def)
        for (SkillDefinition def : skillRegistry.getAllSkillDefinitions()) {
            if (def.isSystem()) {
                if (disabledSystem.contains(def.getName())) {
                    continue;
                }
                allowed.put(def.getName(), def);
                systemEnabledNames.add(def.getName());
            }
        }

        // 2) User skills bound via agent.skillIds — names override only if not already present.
        //
        // SKILL-CANARY-ROLLOUT V2 Phase 1.2: each baseline name passes through
        // CanaryAllocator first. When no active canary exists (default for
        // every skill) the allocator is a pure no-op returning the baseline
        // name. When a canary is active the session may be routed to the
        // candidate skill name; we look up THAT name in the registry and
        // record it in {@code userBoundSkillNames} for diagnostics.
        Long agentIdNumeric = parseAgentIdOrNull(agentDef);
        if (agentDef.getSkillIds() != null) {
            for (String baselineName : agentDef.getSkillIds()) {
                if (baselineName == null || baselineName.isBlank()) continue;
                String resolvedName = allocateForSkill(sessionId, agentIdNumeric, baselineName);
                Optional<SkillDefinition> opt = skillRegistry.getSkillDefinition(resolvedName);
                if (opt.isEmpty()) {
                    if (!resolvedName.equals(baselineName)) {
                        // Canary picked a candidate that's not registered — fail-safe
                        // back to baseline rather than dropping the skill entirely.
                        log.warn("Canary candidate skill '{}' (baseline '{}') not in registry; falling back to baseline",
                                resolvedName, baselineName);
                        opt = skillRegistry.getSkillDefinition(baselineName);
                        resolvedName = baselineName;
                    }
                    if (opt.isEmpty()) {
                        continue;
                    }
                }
                SkillDefinition def = opt.get();
                userBoundNames.add(resolvedName);
                if (allowed.containsKey(resolvedName) && allowed.get(resolvedName).isSystem()) {
                    // System with same name already wins (plan §6).
                    log.debug("User skill name '{}' shadowed by system skill — keeping system def", resolvedName);
                    continue;
                }
                allowed.put(resolvedName, def);
            }
        }

        return new SessionSkillView(allowed, systemEnabledNames, userBoundNames);
    }

    /**
     * SKILL-CANARY-ROLLOUT V2 Phase 1.2 — hand baseline skill name to allocator
     * when it's wired AND we have session + agent context. Returns the
     * baseline unchanged when any of the inputs are missing (which is the
     * legacy / test path that {@code resolveFor(agentDef)} always takes).
     */
    private String allocateForSkill(String sessionId, Long agentId, String baselineName) {
        if (canaryAllocator == null || sessionId == null || agentId == null) {
            return baselineName;
        }
        try {
            String allocated = canaryAllocator.allocate(sessionId, agentId, baselineName);
            return allocated != null ? allocated : baselineName;
        } catch (RuntimeException e) {
            // RuntimeException narrows from the prior `Exception` catch — never traps
            // InterruptedException. The allocator already swallows DataAccessException
            // internally and returns baseline, so anything propagating here is a
            // programming bug (NPE, ISE, etc.) — log + fail-safe to baseline.
            log.warn("CanaryAllocator.allocate failed for session={}, agent={}, skill='{}'; using baseline: {}",
                    sessionId, agentId, baselineName, e.getMessage());
            return baselineName;
        }
    }

    /**
     * {@link AgentDefinition#getId()} is a {@link String} (engine layer uses
     * opaque ids) but {@code t_agent.id} is BIGINT. Parse defensively; bare
     * non-numeric values (test harnesses, synthetic agents) yield {@code null}
     * which short-circuits canary lookup.
     */
    private Long parseAgentIdOrNull(AgentDefinition agentDef) {
        if (agentDef == null || agentDef.getId() == null) return null;
        try {
            return Long.parseLong(agentDef.getId());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Set<String> loadDisabledSystemSkills(AgentDefinition agentDef) {
        // AgentDefinition does not yet carry disabled_system_skills; read from entity by id.
        // Best-effort lookup; unknown / missing → empty.
        if (agentDef.getId() == null) return Collections.emptySet();
        try {
            Long id = Long.parseLong(agentDef.getId());
            AgentEntity entity = agentRepository.findById(id).orElse(null);
            if (entity == null) return Collections.emptySet();
            String json = entity.getDisabledSystemSkills();
            if (json == null || json.isBlank() || "[]".equals(json.trim())) {
                return Collections.emptySet();
            }
            List<String> names = objectMapper.readValue(json, new TypeReference<List<String>>() {});
            return names == null ? Collections.emptySet() : Set.copyOf(names);
        } catch (NumberFormatException e) {
            return Collections.emptySet();
        } catch (Exception e) {
            log.warn("Failed to parse disabled_system_skills for agent={}: {}",
                    agentDef.getId(), e.getMessage());
            return Collections.emptySet();
        }
    }
}
