package com.skillforge.server.memory.llmsynth;

import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.repository.AgentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * MEMORY-LLM-SYNTHESIS (V69 dogfood): boot-time swapper for the
 * {@code memory-curator} agent's {@code system_prompt}.
 *
 * <p>The V69 Flyway migration seeds {@code t_agent.system_prompt = 'SEE_FILE:memory-curator-system-prompt.md'}
 * to avoid hammering ~150 lines of bilingual prompt through SQL string escaping.
 * This bootstrap reads the prompt from {@code classpath:memory-curator-system-prompt.md}
 * and overwrites the placeholder once per boot.
 *
 * <p>Why not bake the prompt directly into the migration: SQL-escaping a long
 * markdown prompt (sandwich defense, role-play sample text, bilingual sections)
 * is fragile and migration rollback semantics get unclear. Keeping the prompt
 * as a tracked-in-git resource lets us edit it without writing a new Flyway
 * version every time we tighten the wording.
 *
 * <p>Idempotency: if the {@code system_prompt} no longer starts with the
 * {@code SEE_FILE:} sentinel (operator manually edited it, or a prior boot
 * already swapped), we leave it alone — operator edits win. We never overwrite
 * non-placeholder prompts.
 *
 * <p>Why {@link ApplicationReadyEvent} instead of {@code @PostConstruct}: the
 * V69 migration must finish first (Flyway runs before {@code ApplicationReadyEvent}
 * but after some {@code @PostConstruct} hooks), and we want the agent row to
 * already exist when we run.
 */
@Component
public class MemoryCuratorBootstrap {

    private static final Logger log = LoggerFactory.getLogger(MemoryCuratorBootstrap.class);

    public static final String AGENT_NAME = "memory-curator";
    static final String SEE_FILE_SENTINEL_PREFIX = "SEE_FILE:";
    static final String PROMPT_RESOURCE_PATH = "memory-curator-system-prompt.md";

    private final AgentRepository agentRepository;

    public MemoryCuratorBootstrap(AgentRepository agentRepository) {
        this.agentRepository = agentRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void swapSystemPromptOnBoot() {
        Optional<AgentEntity> opt;
        try {
            opt = agentRepository.findFirstByName(AGENT_NAME);
        } catch (Exception e) {
            // If the V69 migration hasn't applied yet (test profile bypassing
            // Flyway, etc.) we don't want to crash the whole app. Just log + move on.
            log.warn("[MemoryCuratorBootstrap] lookup failed (migration not yet applied?): {}", e.getMessage());
            return;
        }
        if (opt.isEmpty()) {
            log.debug("[MemoryCuratorBootstrap] no memory-curator agent row — skipping swap");
            return;
        }
        AgentEntity agent = opt.get();
        String current = agent.getSystemPrompt();
        if (current == null || !current.startsWith(SEE_FILE_SENTINEL_PREFIX)) {
            // Already swapped, or operator hand-edited — leave alone.
            log.debug("[MemoryCuratorBootstrap] agentId={} prompt already non-placeholder ({} chars) — leaving alone",
                    agent.getId(), current == null ? 0 : current.length());
            return;
        }

        String resourcePath = current.substring(SEE_FILE_SENTINEL_PREFIX.length()).trim();
        if (resourcePath.isEmpty()) {
            resourcePath = PROMPT_RESOURCE_PATH;
        }
        String prompt = loadPromptFromClasspath(resourcePath);
        if (prompt == null) {
            log.warn("[MemoryCuratorBootstrap] agentId={} cannot resolve prompt resource '{}' — leaving placeholder",
                    agent.getId(), resourcePath);
            return;
        }

        agent.setSystemPrompt(prompt);
        agentRepository.save(agent);
        log.info("[MemoryCuratorBootstrap] agentId={} swapped placeholder for {} ({} chars)",
                agent.getId(), resourcePath, prompt.length());
    }

    /**
     * Package-private for unit testing. Returns null when the resource is missing or
     * unreadable (caller logs and skips — never crash boot just because the prompt
     * file got moved).
     */
    String loadPromptFromClasspath(String resourcePath) {
        ClassPathResource resource = new ClassPathResource(resourcePath);
        if (!resource.exists()) {
            return null;
        }
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("[MemoryCuratorBootstrap] failed to read resource '{}': {}",
                    resourcePath, e.getMessage());
            return null;
        }
    }
}
