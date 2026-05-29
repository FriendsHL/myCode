package com.skillforge.workflow;

import com.skillforge.workflow.sandbox.BudgetTracker;
import com.skillforge.workflow.sandbox.L1SandboxFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mozilla.javascript.RhinoException;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task A spike — gates 1 & 2.
 *
 * <p>Gate 1: confirm Rhino 1.7.14 has NO {@code async}/{@code await} support
 * (decision §0.2: drop {@code async}, {@code agent()} is synchronous).
 *
 * <p>Gate 2: confirm the await-stripping preprocessor (Task J / Judge ruling #1)
 * turns {@code await expr} into {@code expr} while leaving "await" inside string
 * literals / comments / identifiers untouched.
 */
class WorkflowSpikeAsyncAwaitTest {

    private static final WorkflowAgentInvoker NOOP_INVOKER = (p, o, i) -> "noop";

    // ---- Gate 1: async/await unsupported ----

    @Test
    @DisplayName("Gate1: `async function` is a parse error in Rhino 1.7.14")
    void asyncFunctionIsParseError() {
        L1SandboxFactory factory = new L1SandboxFactory();
        WorkflowEvaluator evaluator = new WorkflowEvaluator(factory);
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            WorkflowContext ctx = new WorkflowContext("spike-async", Map.of(), new BudgetTracker(0L));
            assertThatThrownBy(() ->
                    evaluator.evaluateRaw("async function f(){ return 1; }", ctx, NOOP_INVOKER, exec))
                    .as("Rhino 1.7.14 rejects async function at parse time")
                    .isInstanceOf(RhinoException.class);
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    @DisplayName("Gate1: plain ES5/ES6 sync code evaluates fine (sanity baseline)")
    void plainSyncCodeEvaluates() {
        L1SandboxFactory factory = new L1SandboxFactory();
        WorkflowEvaluator evaluator = new WorkflowEvaluator(factory);
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            WorkflowContext ctx = new WorkflowContext("spike-sync", Map.of(), new BudgetTracker(0L));
            Object r = evaluator.evaluateRaw("var x = 1; x + 2", ctx, NOOP_INVOKER, exec);
            assertThat(((Number) r).intValue()).isEqualTo(3);
        } finally {
            exec.shutdownNow();
        }
    }

    // ---- Gate 2: await preprocessor ----

    @Test
    @DisplayName("Gate2: strips standalone `await ` keyword")
    void stripsStandaloneAwait() {
        assertThat(AwaitPreprocessor.stripAwait("var x = await agent('a');"))
                .isEqualTo("var x = agent('a');");
        assertThat(AwaitPreprocessor.stripAwait("await parallel([]);"))
                .isEqualTo("parallel([]);");
        assertThat(AwaitPreprocessor.stripAwait("return await foo;"))
                .isEqualTo("return foo;");
    }

    @Test
    @DisplayName("Gate2: does NOT strip `await` inside string literals")
    void preservesAwaitInStrings() {
        assertThat(AwaitPreprocessor.stripAwait("log('please await results');"))
                .isEqualTo("log('please await results');");
        assertThat(AwaitPreprocessor.stripAwait("log(\"await now\");"))
                .isEqualTo("log(\"await now\");");
    }

    @Test
    @DisplayName("Gate2: does NOT strip identifiers containing `await` or member access")
    void preservesAwaitIdentifiers() {
        assertThat(AwaitPreprocessor.stripAwait("var awaitable = 1;"))
                .isEqualTo("var awaitable = 1;");
        assertThat(AwaitPreprocessor.stripAwait("obj.await + 1"))
                .isEqualTo("obj.await + 1");
    }

    @Test
    @DisplayName("Gate2: does NOT strip `await` inside comments")
    void preservesAwaitInComments() {
        assertThat(AwaitPreprocessor.stripAwait("// await this later\nvar x=1;"))
                .isEqualTo("// await this later\nvar x=1;");
        assertThat(AwaitPreprocessor.stripAwait("/* await */ var x = await y;"))
                .isEqualTo("/* await */ var x = y;");
    }

    @Test
    @DisplayName("Gate2: stripped source containing await is parseable by Rhino")
    void strippedSourceParses() {
        L1SandboxFactory factory = new L1SandboxFactory();
        WorkflowEvaluator evaluator = new WorkflowEvaluator(factory);
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            WorkflowContext ctx = new WorkflowContext("spike-strip", Map.of(), new BudgetTracker(0L));
            // `await (1 + 2)` would be a parse error if not stripped (await unknown).
            Object r = evaluator.evaluateRaw("await (1 + 2)", ctx, NOOP_INVOKER, exec);
            assertThat(((Number) r).intValue()).isEqualTo(3);
        } finally {
            exec.shutdownNow();
        }
    }
}
