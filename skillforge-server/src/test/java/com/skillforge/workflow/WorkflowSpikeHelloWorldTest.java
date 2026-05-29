package com.skillforge.workflow;

import com.skillforge.core.engine.AgentLoopEngine;
import com.skillforge.core.engine.ChatEventBroadcaster;
import com.skillforge.core.engine.LoopContext;
import com.skillforge.core.engine.LoopResult;
import com.skillforge.core.llm.LlmProvider;
import com.skillforge.core.llm.LlmProviderFactory;
import com.skillforge.core.llm.LlmRequest;
import com.skillforge.core.llm.LlmResponse;
import com.skillforge.core.llm.LlmStreamHandler;
import com.skillforge.core.model.AgentDefinition;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.server.config.LlmProperties;
import com.skillforge.workflow.engine.WorkflowSubAgentEngineFactory;
import com.skillforge.workflow.sandbox.BudgetTracker;
import com.skillforge.workflow.sandbox.L1SandboxFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mozilla.javascript.Scriptable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Task A spike — gate 4: top-level hello-world workflow with {@code agent()}
 * driving a REAL {@link AgentLoopEngine#run} (plan §5.1 synchronous path), built
 * via {@link WorkflowSubAgentEngineFactory} (copied from EvalEngineFactory) and a
 * stub {@code LlmProvider} (no tokens burned, no DB harness — same pattern as
 * {@code AgentLoopEngineNoTraceCollectorRecordIT}).
 *
 * <p>Asserts {@code agent('say hi')} synchronously returns the engine's
 * {@code LoopResult.getFinalResponse()} into the workflow JS, and that
 * {@code phase()}/{@code log()} fire.
 */
class WorkflowSpikeHelloWorldTest {

    /** Stub provider: returns a single end_turn text response (loop ends turn 1). */
    private static final class TextProvider implements LlmProvider {
        private final String text;
        TextProvider(String text) { this.text = text; }
        @Override public String getName() { return "fake"; }
        @Override public LlmResponse chat(LlmRequest request) { return resp(); }
        @Override public void chatStream(LlmRequest request, LlmStreamHandler handler) {
            handler.onComplete(resp());
        }
        private LlmResponse resp() {
            LlmResponse r = new LlmResponse();
            r.setStopReason("end_turn");
            r.setContent(text);
            return r;
        }
    }

    private WorkflowAgentInvoker realEngineInvoker(String cannedResponse) {
        LlmProviderFactory factory = new LlmProviderFactory();
        factory.registerProvider("fake", new TextProvider(cannedResponse));
        LlmProperties props = new LlmProperties();
        props.setDefaultProvider("fake");
        ChatEventBroadcaster broadcaster = mock(ChatEventBroadcaster.class);
        WorkflowSubAgentEngineFactory engineFactory =
                new WorkflowSubAgentEngineFactory(factory, broadcaster, props);

        return (prompt, opts, stepIndex) -> {
            AgentLoopEngine engine = engineFactory.buildWorkflowEngine(new SkillRegistry());
            AgentDefinition def = new AgentDefinition();
            def.setName("worker");
            def.setModelId("fake:model");
            def.setSystemPrompt("You are a workflow sub-agent.");
            def.setConfig(Map.of("max_loops", 3));
            LoopContext lc = new LoopContext();
            lc.setExecutionMode("auto");
            lc.setMaxLoops(3);
            LoopResult r = engine.run(def, prompt, null, "wf-sub-" + stepIndex, 1L, lc);
            return r.getFinalResponse();
        };
    }

    @Test
    @DisplayName("Gate4: agent() synchronously returns engine.run finalResponse into workflow JS")
    void helloWorldAgentSyncReturn() {
        L1SandboxFactory sandbox = new L1SandboxFactory();
        WorkflowEvaluator evaluator = new WorkflowEvaluator(sandbox);
        ExecutorService exec = Executors.newFixedThreadPool(2);
        try {
            WorkflowContext ctx = new WorkflowContext("hello-world", Map.of(), new BudgetTracker(0L));
            WorkflowAgentInvoker invoker = realEngineInvoker("hi-from-agent");

            String body =
                    "phase('Hello');\n" +
                    "log('world');\n" +
                    "var r = agent('say hi');\n" +
                    "return { ok: true, r: r };";

            Object result = evaluator.evaluate(body, ctx, invoker, exec);

            assertThat(result).isInstanceOf(Scriptable.class);
            Scriptable obj = (Scriptable) result;
            assertThat(obj.get("ok", obj)).isEqualTo(Boolean.TRUE);
            assertThat(obj.get("r", obj)).isEqualTo("hi-from-agent");

            // host primitives fired
            assertThat(ctx.getPhases()).containsExactly("Hello");
            assertThat(ctx.getLogs()).containsExactly("world");
            // exactly one agent() invocation, on the workflow thread, step index 0 consumed
            assertThat(ctx.getBudget().getAgentCalls()).isEqualTo(1);
            assertThat(ctx.getInvokeThreadNames())
                    .containsExactly(Thread.currentThread().getName());
        } finally {
            exec.shutdownNow();
        }
    }
}
