package com.skillforge.workflow;

import com.skillforge.workflow.sandbox.BudgetTracker;
import com.skillforge.workflow.sandbox.L1SandboxFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.Scriptable;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task E — {@code pipeline()} V1 serial semantics (plan §2.1) + {@code parallel()}
 * boundary robustness (empty / single / all-failing).
 *
 * <p>The invoker is a pure-Java stub (no real {@code engine.run}) that echoes the
 * prompt — what matters here is the host-binding control flow, not LLM behaviour.
 */
class WorkflowPipelineTest {

    private final L1SandboxFactory sandbox = new L1SandboxFactory();
    private final WorkflowEvaluator evaluator = new WorkflowEvaluator(sandbox);

    /** Echoes the prompt back as the sub-agent result. */
    private static final WorkflowAgentInvoker ECHO = (prompt, opts, stepIndex) -> prompt;

    private WorkflowContext newCtx(String runId) {
        return new WorkflowContext(runId, Map.of(), new BudgetTracker(0L));
    }

    // ----------------------------------------------------------------- pipeline

    @Test
    @DisplayName("pipeline: each item flows through every stage in order")
    void pipelineItemFlowsThroughAllStages() {
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            WorkflowContext ctx = newCtx("pipe-flow");
            Object result = evaluator.evaluate(
                    "return pipeline([1, 2, 3],"
                  + "  function(x){ return agent('s1:' + x); },"
                  + "  function(r){ return agent('s2:' + r); });",
                    ctx, ECHO, exec);

            Scriptable arr = (Scriptable) result;
            // item 1 → s1:1 → s2:s1:1 ; proves both stages applied in order.
            assertThat(arr.get(0, arr)).isEqualTo("s2:s1:1");
            assertThat(arr.get(1, arr)).isEqualTo("s2:s1:2");
            assertThat(arr.get(2, arr)).isEqualTo("s2:s1:3");
            // 3 items × 2 stages = 6 agent() invocations.
            assertThat(ctx.getBudget().getAgentCalls()).isEqualTo(6);
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    @DisplayName("pipeline: V1 serial execution is announced via log() (no silent cap)")
    void pipelineLogsSerialExecution() {
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            WorkflowContext ctx = newCtx("pipe-log");
            evaluator.evaluate(
                    "return pipeline([1], function(x){ return agent('s:' + x); });",
                    ctx, ECHO, exec);
            assertThat(ctx.getLogs())
                    .anyMatch(l -> l.contains("V1 串行执行"));
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    @DisplayName("pipeline: runs serially on the single workflow thread (no offload)")
    void pipelineRunsSerialOnWorkflowThread() {
        ExecutorService exec = Executors.newFixedThreadPool(4);
        try {
            WorkflowContext ctx = newCtx("pipe-serial");
            String workflowThread = Thread.currentThread().getName();
            evaluator.evaluate(
                    "return pipeline([1, 2],"
                  + "  function(x){ return agent('s1:' + x); },"
                  + "  function(r){ return agent('s2:' + r); });",
                    ctx, ECHO, exec);
            // All 4 invocations (2 items × 2 stages) happen inline on the workflow
            // thread — pipeline never offloads (plan §2.1).
            assertThat(ctx.getInvokeThreadNames())
                    .hasSize(4)
                    .allMatch(t -> t.equals(workflowThread));
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    @DisplayName("pipeline: a stage that throws drops that item to null, others unaffected")
    void pipelineStageFailureMapsItemToNull() {
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            WorkflowContext ctx = newCtx("pipe-fail");
            WorkflowAgentInvoker invoker = (prompt, opts, stepIndex) -> {
                if ("s:2".equals(prompt)) {
                    throw new RuntimeException("simulated stage failure for item 2");
                }
                return prompt;
            };
            Object result = evaluator.evaluate(
                    "return pipeline([1, 2, 3], function(x){ return agent('s:' + x); });",
                    ctx, invoker, exec);

            Scriptable arr = (Scriptable) result;
            assertThat(arr.get(0, arr)).isEqualTo("s:1");
            Object pos1 = arr.get(1, arr);
            assertThat(pos1).isNotEqualTo("s:2"); // failed item → null (hole)
            assertThat(arr.get(2, arr)).isEqualTo("s:3");
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    @DisplayName("pipeline: a later-stage failure skips the item's remaining stages")
    void pipelineLaterStageFailureSkipsRemaining() {
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            WorkflowContext ctx = newCtx("pipe-skip");
            WorkflowAgentInvoker invoker = (prompt, opts, stepIndex) -> {
                if (prompt.startsWith("s2:")) {
                    throw new RuntimeException("stage 2 always fails");
                }
                return prompt;
            };
            Object result = evaluator.evaluate(
                    "return pipeline([1],"
                  + "  function(x){ return agent('s1:' + x); },"
                  + "  function(r){ return agent('s2:' + r); },"
                  + "  function(r){ return agent('s3:' + r); });",
                    ctx, invoker, exec);

            Scriptable arr = (Scriptable) result;
            assertThat(arr.get(0, arr)).isNotEqualTo("s3:s2:s1:1");
            // s1 + s2 invoked, s3 skipped → exactly 2 agent calls.
            assertThat(ctx.getBudget().getAgentCalls()).isEqualTo(2);
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    @DisplayName("pipeline: empty items array returns empty array (log still emitted)")
    void pipelineEmptyItems() {
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            WorkflowContext ctx = newCtx("pipe-empty");
            Object result = evaluator.evaluate(
                    "return pipeline([], function(x){ return agent('s:' + x); });",
                    ctx, ECHO, exec);
            assertThat(((NativeArray) result).getLength()).isZero();
            assertThat(ctx.getBudget().getAgentCalls()).isZero();
            assertThat(ctx.getLogs()).anyMatch(l -> l.contains("V1 串行执行"));
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    @DisplayName("pipeline: zero stages passes items through unchanged")
    void pipelineZeroStages() {
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            WorkflowContext ctx = newCtx("pipe-nostage");
            Object result = evaluator.evaluate("return pipeline([10, 20, 30]);", ctx, ECHO, exec);
            Scriptable arr = (Scriptable) result;
            assertThat(((NativeArray) result).getLength()).isEqualTo(3);
            assertThat(((Number) arr.get(0, arr)).intValue()).isEqualTo(10);
            assertThat(((Number) arr.get(2, arr)).intValue()).isEqualTo(30);
            assertThat(ctx.getBudget().getAgentCalls()).isZero();
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    @DisplayName("pipeline: a stage may compose with parallel()")
    void pipelineComposesWithParallel() {
        ExecutorService exec = Executors.newFixedThreadPool(4);
        try {
            WorkflowContext ctx = newCtx("pipe-parallel");
            Object result = evaluator.evaluate(
                    "return pipeline([1, 2],"
                  + "  function(x){ return agent('a:' + x); },"
                  + "  function(r){ return parallel([function(){ return agent('p:' + r); }]); });",
                    ctx, ECHO, exec);

            Scriptable arr = (Scriptable) result;
            Scriptable inner0 = (Scriptable) arr.get(0, arr);
            assertThat(inner0.get(0, inner0)).isEqualTo("p:a:1");
            Scriptable inner1 = (Scriptable) arr.get(1, arr);
            assertThat(inner1.get(0, inner1)).isEqualTo("p:a:2");
        } finally {
            exec.shutdownNow();
        }
    }

    // -------------------------------------------------- parallel boundary cases

    @Test
    @DisplayName("parallel: empty array returns empty array")
    void parallelEmptyArray() {
        ExecutorService exec = Executors.newFixedThreadPool(2);
        try {
            WorkflowContext ctx = newCtx("par-empty");
            Object result = evaluator.evaluate("return parallel([]);", ctx, ECHO, exec);
            assertThat(((NativeArray) result).getLength()).isZero();
            assertThat(ctx.getBudget().getAgentCalls()).isZero();
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    @DisplayName("parallel: single element returns a one-element array")
    void parallelSingleElement() {
        ExecutorService exec = Executors.newFixedThreadPool(2);
        try {
            WorkflowContext ctx = newCtx("par-single");
            Object result = evaluator.evaluate(
                    "return parallel([function(){ return agent('solo'); }]);", ctx, ECHO, exec);
            Scriptable arr = (Scriptable) result;
            assertThat(((NativeArray) result).getLength()).isEqualTo(1);
            assertThat(arr.get(0, arr)).isEqualTo("solo");
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    @DisplayName("parallel: all branches failing → all positions null")
    void parallelAllBranchesFail() {
        ExecutorService exec = Executors.newFixedThreadPool(2);
        try {
            WorkflowContext ctx = newCtx("par-allfail");
            WorkflowAgentInvoker invoker = (prompt, opts, stepIndex) -> {
                throw new RuntimeException("everything fails");
            };
            Object result = evaluator.evaluate(
                    "return parallel([function(){ return agent('a'); }, function(){ return agent('b'); }]);",
                    ctx, invoker, exec);
            Scriptable arr = (Scriptable) result;
            assertThat(((NativeArray) result).getLength()).isEqualTo(2);
            assertThat(arr.get(0, arr)).isNotEqualTo("a");
            assertThat(arr.get(1, arr)).isNotEqualTo("b");
            // both branches still consumed budget (incremented before offload).
            assertThat(ctx.getBudget().getAgentCalls()).isEqualTo(2);
        } finally {
            exec.shutdownNow();
        }
    }
}
