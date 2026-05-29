package com.skillforge.workflow;

import com.skillforge.workflow.sandbox.BudgetTracker;
import com.skillforge.workflow.sandbox.L1SandboxFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mozilla.javascript.Scriptable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task A spike — gate 5 ⭐ (most critical): the offload concurrency model
 * (plan §2.1). {@code parallel([()=>agent('a'), ()=>agent('b')])} must:
 *
 * <ul>
 *   <li>(a) run the two {@code engine.run}s <b>truly concurrently</b> (overlapping
 *       execution windows on worker threads);</li>
 *   <li>(b) keep Rhino single-threaded — every {@code agent()} invoke happens on
 *       the one workflow thread, no {@code IllegalStateException};</li>
 *   <li>(c) return results in <b>call order</b> regardless of completion order;</li>
 *   <li>(d) map a failing branch to {@code null}, leaving others intact.</li>
 * </ul>
 *
 * <p>The invoker here simulates {@code engine.run} with a sleep + timestamp
 * recorder (plan §2.4 #3) — what matters is that the invoker is pure Java and is
 * offloaded to the sub-agent executor, so this faithfully exercises the Rhino
 * threading boundary.
 */
class WorkflowParallelConcurrencyTest {

    private final L1SandboxFactory sandbox = new L1SandboxFactory();
    private final WorkflowEvaluator evaluator = new WorkflowEvaluator(sandbox);

    @Test
    @DisplayName("Gate5(a,b,c): parallel runs engine.run concurrently, Rhino single-threaded, call-order results")
    void parallelOffloadConcurrentAndOrdered() {
        ExecutorService exec = Executors.newFixedThreadPool(4);
        ConcurrentHashMap<String, long[]> windows = new ConcurrentHashMap<>();
        ConcurrentHashMap<String, String> execThreads = new ConcurrentHashMap<>();
        try {
            WorkflowContext ctx = new WorkflowContext("parallel-spike", Map.of(), new BudgetTracker(0L));

            WorkflowAgentInvoker invoker = (prompt, opts, stepIndex) -> {
                long start = System.nanoTime();
                execThreads.put(prompt, Thread.currentThread().getName());
                try {
                    Thread.sleep(300L); // simulate blocking engine.run
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                long end = System.nanoTime();
                windows.put(prompt, new long[]{start, end});
                return "resp:" + prompt;
            };

            String workflowThread = Thread.currentThread().getName();
            Object result = evaluator.evaluate(
                    "return parallel([() => agent('a'), () => agent('b')]);",
                    ctx, invoker, exec);

            // (c) results in call order
            assertThat(result).isInstanceOf(Scriptable.class);
            Scriptable arr = (Scriptable) result;
            assertThat(arr.get(0, arr)).isEqualTo("resp:a");
            assertThat(arr.get(1, arr)).isEqualTo("resp:b");

            // (a) execution windows overlap → genuinely concurrent
            long[] wa = windows.get("a");
            long[] wb = windows.get("b");
            assertThat(wa).isNotNull();
            assertThat(wb).isNotNull();
            long latestStart = Math.max(wa[0], wb[0]);
            long earliestEnd = Math.min(wa[1], wb[1]);
            assertThat(latestStart)
                    .as("engine.run windows must overlap (true concurrency, not serialized)")
                    .isLessThan(earliestEnd);

            // (b) Rhino touched only on the workflow thread; engine.run offloaded
            assertThat(ctx.getInvokeThreadNames())
                    .as("both agent() invokes happen on the single workflow thread")
                    .containsExactly(workflowThread, workflowThread);
            assertThat(execThreads.get("a")).isNotEqualTo(workflowThread);
            assertThat(execThreads.get("b")).isNotEqualTo(workflowThread);
            assertThat(execThreads.get("a"))
                    .as("the two engine.run executions run on different worker threads")
                    .isNotEqualTo(execThreads.get("b"));

            assertThat(ctx.getBudget().getAgentCalls()).isEqualTo(2);
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    @DisplayName("Gate5: stepIndex assigned in thunk (call) order, NOT completion order")
    void parallelStepIndexFollowsCallOrder() {
        // Sprint-2 journal-replay precondition (plan §3.2): the step index is the
        // deterministic invoke-order primitive. It is allocated on the workflow
        // thread when agent() is invoked (thunk order [0,1]) BEFORE the blocking
        // engine.run is offloaded — never reordered by completion timing.
        ExecutorService exec = Executors.newFixedThreadPool(4);
        ConcurrentHashMap<String, Integer> stepIndexByPrompt = new ConcurrentHashMap<>();
        try {
            WorkflowContext ctx = new WorkflowContext("parallel-stepindex", Map.of(), new BudgetTracker(0L));

            WorkflowAgentInvoker invoker = (prompt, opts, stepIndex) -> {
                stepIndexByPrompt.put(prompt, stepIndex);
                // 'a' (thunk 0) finishes LAST, 'b' (thunk 1) finishes first — so a
                // completion-ordered index would (wrongly) give 'b' index 0.
                try {
                    Thread.sleep("a".equals(prompt) ? 300L : 50L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "resp:" + prompt;
            };

            evaluator.evaluate(
                    "return parallel([() => agent('a'), () => agent('b')]);",
                    ctx, invoker, exec);

            assertThat(stepIndexByPrompt.get("a"))
                    .as("thunk 0 ('a') must get stepIndex 0 regardless of completion order")
                    .isEqualTo(0);
            assertThat(stepIndexByPrompt.get("b"))
                    .as("thunk 1 ('b') must get stepIndex 1 regardless of completion order")
                    .isEqualTo(1);
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    @DisplayName("Gate5(d): a failing branch maps to null, other branch unaffected")
    void parallelFailingBranchMapsToNull() {
        ExecutorService exec = Executors.newFixedThreadPool(4);
        try {
            WorkflowContext ctx = new WorkflowContext("parallel-fail-spike", Map.of(), new BudgetTracker(0L));

            WorkflowAgentInvoker invoker = (prompt, opts, stepIndex) -> {
                if ("b".equals(prompt)) {
                    throw new RuntimeException("simulated engine.run failure for b");
                }
                return "resp:" + prompt;
            };

            Object result = evaluator.evaluate(
                    "return parallel([() => agent('a'), () => agent('b'), () => agent('c')]);",
                    ctx, invoker, exec);

            Scriptable arr = (Scriptable) result;
            assertThat(arr.get(0, arr)).isEqualTo("resp:a");
            // position 1 (failed branch) → null. Rhino represents an array hole/null
            // read as Scriptable.NOT_FOUND or null; assert it's not the success value.
            Object pos1 = arr.get(1, arr);
            assertThat(pos1).isNotEqualTo("resp:b");
            assertThat(arr.get(2, arr)).isEqualTo("resp:c");
        } finally {
            exec.shutdownNow();
        }
    }
}
