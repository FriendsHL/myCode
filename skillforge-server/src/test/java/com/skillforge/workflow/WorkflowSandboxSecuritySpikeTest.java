package com.skillforge.workflow;

import com.skillforge.workflow.sandbox.BudgetTracker;
import com.skillforge.workflow.sandbox.L1SandboxFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task A spike — gate 3: minimal L1 sandbox blocks the headline attack vectors
 * (plan §4). Each malicious expression MUST throw (never execute successfully).
 *
 * <p>Covers ≥5 vectors: System.exit (#1), new java.io.File (#2),
 * Packages.Runtime (#3), eval (#5), and the {@code this.constructor.constructor}
 * Function-synthesis escape (#7). Bonus: importClass (#4), java.lang.Thread (#8),
 * and a sanity check that legitimate JS (arrow functions / higher-order) still
 * runs.
 */
class WorkflowSandboxSecuritySpikeTest {

    private static final WorkflowAgentInvoker NOOP_INVOKER = (p, o, i) -> "noop";

    private final L1SandboxFactory factory = new L1SandboxFactory();
    private final WorkflowEvaluator evaluator = new WorkflowEvaluator(factory);

    private void assertBlocked(String maliciousSource) {
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            WorkflowContext ctx = new WorkflowContext("sec-spike", Map.of(), new BudgetTracker(0L));
            assertThatThrownBy(() -> evaluator.evaluateRaw(maliciousSource, ctx, NOOP_INVOKER, exec))
                    .as("sandbox must block: %s", maliciousSource)
                    .isInstanceOf(RuntimeException.class);
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    @DisplayName("Gate3 #1: System.exit blocked")
    void blocksSystemExit() {
        assertBlocked("java.lang.System.exit(0)");
    }

    @Test
    @DisplayName("Gate3 #2: new java.io.File blocked")
    void blocksNewFile() {
        assertBlocked("new java.io.File('/etc/passwd')");
    }

    @Test
    @DisplayName("Gate3 #3: Packages.java.lang.Runtime blocked")
    void blocksPackagesRuntime() {
        assertBlocked("Packages.java.lang.Runtime.getRuntime().exec('id')");
    }

    @Test
    @DisplayName("Gate3 #5: eval blocked")
    void blocksEval() {
        assertBlocked("eval('1+1')");
    }

    @Test
    @DisplayName("Gate3 #7: this.constructor.constructor Function-synthesis escape blocked")
    void blocksFunctionConstructorEscape() {
        assertBlocked("(function(){ return this.constructor.constructor('return 1')(); })()");
        assertBlocked("new Function('return 1')()");
        assertBlocked("(function(){}).constructor('return 2')()");
    }

    @Test
    @DisplayName("Gate3 #4: importClass blocked")
    void blocksImportClass() {
        assertBlocked("importClass(java.io.File)");
    }

    @Test
    @DisplayName("Gate3 #8: java.lang.Thread reference blocked")
    void blocksThreadReference() {
        assertBlocked("var t = java.lang.Thread; t");
    }

    @Test
    @DisplayName("Gate3 sanity: legitimate arrow / higher-order JS still runs")
    void legitimateJsStillRuns() {
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            WorkflowContext ctx = new WorkflowContext("sec-ok", Map.of(), new BudgetTracker(0L));
            Object r = evaluator.evaluateRaw("[1,2,3].map(x => x * 2).join(',')", ctx, NOOP_INVOKER, exec);
            assertThat(String.valueOf(r)).isEqualTo("2,4,6");
        } finally {
            exec.shutdownNow();
        }
    }
}
