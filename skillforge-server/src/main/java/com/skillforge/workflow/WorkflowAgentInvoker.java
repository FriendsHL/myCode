package com.skillforge.workflow;

import java.util.Map;

/**
 * Seam between the Rhino {@code agent()} host binding and the actual sub-agent
 * execution (plan §5.1 {@code agent()} → {@code AgentLoopEngine.run} synchronous
 * path).
 *
 * <p>Crucially this method runs <strong>pure Java</strong> — it must never touch
 * the Rhino {@code Context}/scope. That is what makes the offload concurrency
 * model (plan §2.1) safe: in {@code parallel()}, {@code HostAgent} submits
 * {@code invoke(...)} to the sub-agent executor so N {@code engine.run} calls run
 * concurrently on worker threads while the single workflow thread stays the only
 * one ever touching Rhino.
 *
 * <p>Sprint-1 spike: a test supplies an implementation backed by a real
 * {@link com.skillforge.core.engine.AgentLoopEngine} built via
 * {@link com.skillforge.workflow.engine.WorkflowSubAgentEngineFactory} + a stub
 * {@code LlmProvider}. Task D implements the production binding (resolve
 * agentSlug → AgentDefinition, create sub-session, run engine, persist step).
 *
 * @param prompt    the prompt passed to {@code agent('...')}
 * @param opts      the options object ({@code agentSlug}, {@code model}, ...); may be empty
 * @param stepIndex deterministic invoke-order index (plan §3.2)
 * @return the sub-agent result to hand back to the workflow JS (typically the
 *         {@code LoopResult.getFinalResponse()} String)
 */
@FunctionalInterface
public interface WorkflowAgentInvoker {
    Object invoke(String prompt, Map<String, Object> opts, int stepIndex);
}
