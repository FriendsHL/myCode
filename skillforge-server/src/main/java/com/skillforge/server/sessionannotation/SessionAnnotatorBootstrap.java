package com.skillforge.server.sessionannotation;

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
 * PROD-LABEL-CLUSTER V1 (V75 dogfood): boot-time swapper for the
 * {@code session-annotator} agent's {@code system_prompt}.
 *
 * <p>Structural twin of {@link com.skillforge.server.memory.llmsynth.MemoryCuratorBootstrap}
 * — only the agent name + resource path differ. The V75 Flyway migration seeds
 * {@code t_agent.system_prompt = 'SEE_FILE:session-annotator-system-prompt.md'} to
 * avoid escaping the multi-line orchestration prompt through SQL string literals.
 * This bootstrap reads the prompt from
 * {@code classpath:session-annotator-system-prompt.md} once on boot and swaps the
 * placeholder out.
 *
 * <p>Idempotency: if the {@code system_prompt} no longer starts with the
 * {@code SEE_FILE:} sentinel (operator manually edited, or a prior boot already
 * swapped), we leave it alone — operator edits win. We never overwrite
 * non-placeholder prompts.
 *
 * <p>Why {@link ApplicationReadyEvent} instead of {@code @PostConstruct}: the
 * V75 migration must finish first (Flyway runs before {@code ApplicationReadyEvent}
 * but after some {@code @PostConstruct} hooks), and we want the agent row to
 * already exist when we run.
 */
@Component
public class SessionAnnotatorBootstrap {

    private static final Logger log = LoggerFactory.getLogger(SessionAnnotatorBootstrap.class);

    public static final String AGENT_NAME = "session-annotator";
    static final String SEE_FILE_SENTINEL_PREFIX = "SEE_FILE:";
    static final String PROMPT_RESOURCE_PATH = "session-annotator-system-prompt.md";

    private final AgentRepository agentRepository;

    public SessionAnnotatorBootstrap(AgentRepository agentRepository) {
        this.agentRepository = agentRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void swapSystemPromptOnBoot() {
        Optional<AgentEntity> opt;
        try {
            opt = agentRepository.findFirstByName(AGENT_NAME);
        } catch (Exception e) {
            // If the V75 migration hasn't applied yet (test profile bypassing
            // Flyway, etc.) we don't want to crash the whole app. Just log + move on.
            log.warn("[SessionAnnotatorBootstrap] lookup failed (migration not yet applied?): {}", e.getMessage());
            return;
        }
        if (opt.isEmpty()) {
            log.debug("[SessionAnnotatorBootstrap] no session-annotator agent row — skipping swap");
            return;
        }
        AgentEntity agent = opt.get();
        String current = agent.getSystemPrompt();
        if (current == null || !current.startsWith(SEE_FILE_SENTINEL_PREFIX)) {
            // Already swapped, or operator hand-edited — leave alone.
            log.debug("[SessionAnnotatorBootstrap] agentId={} prompt already non-placeholder ({} chars) — leaving alone",
                    agent.getId(), current == null ? 0 : current.length());
            return;
        }

        String resourcePath = current.substring(SEE_FILE_SENTINEL_PREFIX.length()).trim();
        if (resourcePath.isEmpty()) {
            resourcePath = PROMPT_RESOURCE_PATH;
        }
        String prompt = loadPromptFromClasspath(resourcePath);
        if (prompt == null) {
            log.warn("[SessionAnnotatorBootstrap] agentId={} cannot resolve prompt resource '{}' — leaving placeholder",
                    agent.getId(), resourcePath);
            return;
        }

        agent.setSystemPrompt(prompt);
        agentRepository.save(agent);
        log.info("[SessionAnnotatorBootstrap] agentId={} swapped placeholder for {} ({} chars)",
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
            log.warn("[SessionAnnotatorBootstrap] failed to read resource '{}': {}",
                    resourcePath, e.getMessage());
            return null;
        }
    }
}
