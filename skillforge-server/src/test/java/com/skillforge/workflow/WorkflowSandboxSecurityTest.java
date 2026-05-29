package com.skillforge.workflow;

import com.skillforge.workflow.exception.WorkflowAgentNotFoundException;
import com.skillforge.workflow.exception.WorkflowBudgetExceededException;
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
 * Task I — full L1 sandbox security audit (plan §4, FR-2.7). Each malicious
 * expression MUST throw (never execute successfully). Covers the testable
 * vectors §4 #1-#13:
 *
 * <ul>
 *   <li>#1 System.exit / Runtime.exec — ClassShutter + scope scrub</li>
 *   <li>#2 new java.io.File — ClassShutter + scope scrub</li>
 *   <li>#3 Packages.* entry — scope scrub of Packages/java/...</li>
 *   <li>#4 importClass / importPackage / JavaImporter — scope scrub</li>
 *   <li>#5 eval / new Function — scope scrub + Function-constructor neutralisation</li>
 *   <li>#6 getClass reflection escape — no host object exposes getClass</li>
 *   <li>#7 this.constructor.constructor Function-synthesis escape</li>
 *   <li>#8 java.lang.Thread / JavaAdapter</li>
 *   <li>#9 while(true) infinite loop — instruction cap (BudgetTracker)</li>
 *   <li>#10 deep recursion — interpreter stack-depth cap</li>
 *   <li>#11 memory bomb — instruction cap fallback (see limitation note)</li>
 *   <li>#12 agent() flood — agent-call cap (BudgetTracker)</li>
 *   <li>#13 load / readFile / readUrl / spawn — undefined in embedded scope</li>
 * </ul>
 *
 * <p>Not asserted here:
 * <ul>
 *   <li>#14 dynamic (non-literal) {@code meta} rejection — enforced at definition
 *       load time, covered by {@code WorkflowDefinitionRegistryTest}.</li>
 *   <li>#15 prototype pollution — accepted backlog risk (plan §4: low severity,
 *       no persistent global across runs); V1 does not specifically block it.</li>
 *   <li>#11 precise heap isolation — JVM-internal isolation is impossible
 *       (plan §4 #11 / R4); only the instruction-cap soft guard is asserted, with
 *       this limitation documented rather than silently capped.</li>
 * </ul>
 *
 * <p>AC-2 (require('fs') / eval / new java.io.File / while(true)) is satisfied by
 * {@link #blocksRequire()} / {@link #blocksEval()} / {@link #blocksNewFile()} /
 * {@link #blocksInfiniteLoop()}.
 */
class WorkflowSandboxSecurityTest {

    private static final WorkflowAgentInvoker NOOP_INVOKER = (p, o, i) -> "noop";

    /** Invoker that throws a host Java exception so Rhino wraps it (→ {@code e.javaException}). */
    private static final WorkflowAgentInvoker THROWING_INVOKER = (p, o, i) -> {
        throw new WorkflowAgentNotFoundException("__nonexistent__");
    };

    private final L1SandboxFactory factory = new L1SandboxFactory();
    private final WorkflowEvaluator evaluator = new WorkflowEvaluator(factory);

    /** Asserts the source throws some {@link RuntimeException} (never executes). */
    private void assertBlocked(String maliciousSource) {
        assertBlocked(maliciousSource, new BudgetTracker(0L));
    }

    private void assertBlocked(String maliciousSource, BudgetTracker budget) {
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            WorkflowContext ctx = new WorkflowContext("sec", Map.of(), budget);
            assertThatThrownBy(() -> evaluator.evaluateRaw(maliciousSource, ctx, NOOP_INVOKER, exec))
                    .as("sandbox must block: %s", maliciousSource)
                    .isInstanceOf(RuntimeException.class);
        } finally {
            exec.shutdownNow();
        }
    }

    /** Asserts the source throws a {@link WorkflowBudgetExceededException} specifically. */
    private void assertBudgetExceeded(String maliciousSource, BudgetTracker budget) {
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            WorkflowContext ctx = new WorkflowContext("sec", Map.of(), budget);
            assertThatThrownBy(() -> evaluator.evaluateRaw(maliciousSource, ctx, NOOP_INVOKER, exec))
                    .as("budget guard must trip: %s", maliciousSource)
                    .isInstanceOf(WorkflowBudgetExceededException.class);
        } finally {
            exec.shutdownNow();
        }
    }

    // --------------------------------------------------------------- #1 System

    @Test
    @DisplayName("#1: System.exit / Runtime.exec blocked")
    void blocksSystemAndRuntime() {
        assertBlocked("java.lang.System.exit(0)");
        assertBlocked("java.lang.Runtime.getRuntime().exec('id')");
    }

    // ------------------------------------------------------------------ #2 File

    @Test
    @DisplayName("#2: new java.io.File blocked")
    void blocksNewFile() {
        assertBlocked("new java.io.File('/etc/passwd')");
    }

    // ------------------------------------------------------------- #3 Packages

    @Test
    @DisplayName("#3: Packages.* entry blocked")
    void blocksPackages() {
        assertBlocked("Packages.java.lang.Runtime.getRuntime().exec('id')");
        assertBlocked("Packages.java.lang.System.exit(0)");
    }

    // ----------------------------------------------------------- #4 importClass

    @Test
    @DisplayName("#4: importClass / importPackage / JavaImporter blocked")
    void blocksJavaImport() {
        assertBlocked("importClass(java.io.File)");
        assertBlocked("importPackage(java.io)");
        assertBlocked("new JavaImporter(java.io)");
        assertBlocked("JavaImporter(java.io)");
    }

    // ----------------------------------------------------- #5 eval / Function

    @Test
    @DisplayName("#5: eval blocked")
    void blocksEval() {
        assertBlocked("eval('1+1')");
    }

    @Test
    @DisplayName("#5: new Function blocked")
    void blocksNewFunction() {
        assertBlocked("new Function('return 1')()");
        assertBlocked("Function('return 1')()");
    }

    // -------------------------------------------------------------- #6 getClass

    @Test
    @DisplayName("#6: getClass reflection escape blocked (host fns + literals expose no getClass)")
    void blocksGetClassEscape() {
        // Host bindings are plain BaseFunctions, not wrapped Java objects → no getClass.
        assertBlocked("phase.getClass().getName()");
        assertBlocked("agent.getClass()");
        assertBlocked("args.getClass()");
        // JS string/array literals are not Java objects → no getClass.
        assertBlocked("'x'.getClass().getClassLoader()");
        assertBlocked("[].getClass()");
    }

    // ------------------------------------------------- #7 constructor.constructor

    @Test
    @DisplayName("#7: this.constructor.constructor Function-synthesis escape blocked")
    void blocksConstructorChainEscape() {
        assertBlocked("(function(){ return this.constructor.constructor('return 1')(); })()");
        assertBlocked("({}).constructor.constructor('return 2')()");
        assertBlocked("[].constructor.constructor('return 3')()");
        assertBlocked("(function(){}).constructor('return 4')()");
    }

    // ------------------------------------------------- #8 Thread / JavaAdapter

    @Test
    @DisplayName("#8: java.lang.Thread / JavaAdapter blocked")
    void blocksThreadAndAdapter() {
        assertBlocked("var t = java.lang.Thread; t");
        assertBlocked("new JavaAdapter(java.lang.Runnable, { run: function(){} })");
    }

    // ------------------------------------------------------ #9 infinite loop

    @Test
    @DisplayName("#9: while(true) infinite loop tripped by instruction cap")
    void blocksInfiniteLoop() {
        // Low instruction cap so the test is fast; observer threshold is 10k.
        assertBudgetExceeded("while (true) { }", new BudgetTracker(100_000L, 1000, 0L));
        assertBudgetExceeded("for (;;) { }", new BudgetTracker(100_000L, 1000, 0L));
    }

    // ----------------------------------------------------- #10 deep recursion

    @Test
    @DisplayName("#10: unbounded recursion tripped by interpreter stack-depth cap")
    void blocksDeepRecursion() {
        assertBlocked("(function f(){ return f(); })()");
    }

    // -------------------------------------------------------- #11 memory bomb

    @Test
    @DisplayName("#11: memory bomb caught by instruction cap (heap isolation limitation, plan §4 #11)")
    void blocksMemoryBombViaInstructionCap() {
        // NOTE (limitation, not a silent cap): the JVM cannot precisely isolate a
        // single workflow's heap (plan §4 #11 / R4). Real isolation needs a
        // separate process (V2+). Here the instruction cap is the soft guard that
        // stops the unbounded allocation loop before OOM.
        assertBudgetExceeded("var a = []; while (true) { a.push(1); }",
                new BudgetTracker(200_000L, 1000, 0L));
    }

    // ------------------------------------------------------- #12 agent() flood

    @Test
    @DisplayName("#12: agent() flood tripped by agent-call cap")
    void blocksAgentFlood() {
        // agentCallCap = 5 → the 6th agent() call throws (well before instruction cap).
        assertBudgetExceeded("while (true) { agent('spam'); }",
                new BudgetTracker(10_000_000L, 5, 0L));
    }

    // --------------------------------------------- #13 shell globals undefined

    @Test
    @DisplayName("#13: load / readFile / readUrl / spawn / runCommand / quit undefined")
    void blocksRhinoShellGlobals() {
        assertBlocked("load('x')");
        assertBlocked("readFile('/etc/passwd')");
        assertBlocked("readUrl('http://evil')");
        assertBlocked("spawn(function(){})");
        assertBlocked("runCommand('id')");
        assertBlocked("quit()");
    }

    @Test
    @DisplayName("AC-2: require('fs') undefined (no CommonJS require in embedded scope)")
    void blocksRequire() {
        assertBlocked("require('fs')");
    }

    // ----------------------------------- #W1 javaException reflection escape

    @Test
    @DisplayName("e.javaException 不暴露 Java 异常对象（host Java 异常在 JS 中不可 catch）")
    void blocksJavaExceptionObjectExposure() {
        // sec-reviewer W1 (the only "theoretically possible, unfalsified" escape):
        // a host fn throwing a Java exception could, if Rhino wrapped it into a
        // JS-catchable WrappedException, let the script read `e.javaException` and
        // do `getClass().getClassLoader().loadClass('java.lang.Runtime')` to bypass
        // the ClassShutter.
        //
        // VERIFIED MECHANISM (empirically, via probe before this was committed):
        // with ClassShutter returning false for EVERY class, Rhino does NOT make a
        // host Java exception catchable in JS at all — it propagates straight out
        // of evaluateString uncaught (the JS `catch(e)` block is never entered).
        // (Contrast: a JS-native `throw 'x'` IS catchable — try/catch works; it is
        // specifically the foreign Java throwable that cannot be exposed.) So
        // `e.javaException` is structurally unreachable and W1 is closed. Either
        // way the malicious source must throw rather than yield a Runtime handle.
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            WorkflowContext ctx = new WorkflowContext("sec-jex", Map.of(), new BudgetTracker(0L));
            String src =
                    "try { agent('x', {agentSlug: '__nonexistent__'}); } "
                  + "catch(e) { var c = e.javaException.getClass().getClassLoader().loadClass('java.lang.Runtime'); }";
            assertThatThrownBy(() -> evaluator.evaluateRaw(src, ctx, THROWING_INVOKER, exec))
                    .as("host Java exception must not become a usable Java reflection handle in JS")
                    .isInstanceOf(RuntimeException.class);
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    @DisplayName("generator function 构造器链被中和（或 function* 不支持时 SyntaxError 拦住）")
    void blocksGeneratorFunctionConstructor() {
        // (function*(){}).constructor.constructor resolves through Function.prototype
        // .constructor — neutralised by the ScopeScrubber bootstrap. If Rhino 1.7.14
        // rejects the `function*` syntax outright, evaluateString throws an
        // EvaluatorException (also a RuntimeException) — either way it is blocked.
        assertBlocked("(function*(){}).constructor.constructor('return 1')()");
    }

    // --------------------------------------------------- #W3 wall-clock timeout

    @Test
    @DisplayName("FR-2.4: wall-clock timeout 中止超时 workflow")
    void blocksWallClockTimeout() {
        // 1ns timeout + high instruction cap → the timeout (not the instruction cap)
        // trips once the instruction observer first fires inside the tight loop.
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            BudgetTracker budget = new BudgetTracker(10_000_000L, 1000, System.nanoTime(), 1L);
            WorkflowContext ctx = new WorkflowContext("sec-timeout", Map.of(), budget);
            assertThatThrownBy(() -> evaluator.evaluateRaw("while (true) { }", ctx, NOOP_INVOKER, exec))
                    .isInstanceOf(WorkflowBudgetExceededException.class)
                    .hasMessageContaining("timeout");
        } finally {
            exec.shutdownNow();
        }
    }

    // -------------------------------------------------------- sanity (anti-FP)

    @Test
    @DisplayName("sanity: legitimate JS (map / arrow / reduce / closures) still runs")
    void legitimateJsStillRuns() {
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            WorkflowContext ctx = new WorkflowContext("sec-ok", Map.of(), new BudgetTracker(0L));
            Object mapped = evaluator.evaluateRaw("[1,2,3].map(x => x * 2).join(',')", ctx, NOOP_INVOKER, exec);
            assertThat(String.valueOf(mapped)).isEqualTo("2,4,6");
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    @DisplayName("sanity: bounded loop + object/array ops run without tripping the budget")
    void legitimateLoopRuns() {
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            WorkflowContext ctx = new WorkflowContext("sec-ok2", Map.of(), new BudgetTracker(0L));
            Object sum = evaluator.evaluateRaw(
                    "var s = 0; for (var i = 0; i < 100; i++) { s += i; } s",
                    ctx, NOOP_INVOKER, exec);
            assertThat(((Number) sum).intValue()).isEqualTo(4950);
        } finally {
            exec.shutdownNow();
        }
    }
}
