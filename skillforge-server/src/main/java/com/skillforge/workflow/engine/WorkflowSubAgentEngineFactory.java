package com.skillforge.workflow.engine;

import com.skillforge.core.engine.AgentLoopEngine;
import com.skillforge.core.engine.ChatEventBroadcaster;
import com.skillforge.core.llm.LlmProviderFactory;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.server.config.LlmProperties;
import org.springframework.stereotype.Component;

import java.util.Collections;

/**
 * Builds self-contained {@link AgentLoopEngine}s for workflow {@code agent()}
 * sub-agents (plan §5.3, Judge ruling #3). Mirrors
 * {@code com.skillforge.server.eval.EvalEngineFactory}:
 *
 * <ul>
 *   <li>Caller supplies its OWN {@link SkillRegistry} (NOT the global production
 *       bean) — keeps workflow sub-agents isolated, consistent with eval.</li>
 *   <li><b>No compactor callback</b> — workflow sub-agents must not compact.</li>
 *   <li><b>No pending-ask registry</b> — sub-agents must not block on
 *       {@code ask_user}.</li>
 * </ul>
 *
 * <p>The returned engine's {@code run(...)} is fully synchronous and touches no
 * Rhino state, which is what lets {@code HostParallel} offload it to a worker
 * thread pool (plan §2.1).
 */
@Component
public class WorkflowSubAgentEngineFactory {

    private final LlmProviderFactory llmProviderFactory;
    private final ChatEventBroadcaster broadcaster;
    private final String defaultProviderName;

    public WorkflowSubAgentEngineFactory(LlmProviderFactory llmProviderFactory,
                                         ChatEventBroadcaster broadcaster,
                                         LlmProperties llmProperties) {
        this.llmProviderFactory = llmProviderFactory;
        this.broadcaster = broadcaster;
        this.defaultProviderName = llmProperties.getDefaultProvider() != null
                ? llmProperties.getDefaultProvider() : "claude";
    }

    public AgentLoopEngine buildWorkflowEngine(SkillRegistry registry) {
        AgentLoopEngine engine = new AgentLoopEngine(
                llmProviderFactory,
                defaultProviderName,
                registry,
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList()
        );
        if (broadcaster != null) {
            engine.setBroadcaster(broadcaster);
        }
        // CRITICAL: Do NOT set compactorCallback (workflow sub-agent must not compact)
        // CRITICAL: Do NOT set pendingAskRegistry (ask_user must not block a workflow)
        return engine;
    }
}
