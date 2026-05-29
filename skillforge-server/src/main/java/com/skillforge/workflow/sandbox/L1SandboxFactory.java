package com.skillforge.workflow.sandbox;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

/**
 * L1 capability sandbox factory (plan §4). A {@link ContextFactory} that mints
 * locked-down {@link Context}s:
 *
 * <ul>
 *   <li>{@code setOptimizationLevel(-1)} — interpreted mode (required for the
 *       instruction observer; also the only mode Rhino continuations would work
 *       in, though we don't use them).</li>
 *   <li>{@code setLanguageVersion(VERSION_ES6)} — arrow functions / let / const
 *       (workflow thunks are {@code () => agent(...)}).</li>
 *   <li>{@code ClassShutter} returning {@code false} for every class name — no
 *       Java class is visible to script (§4 #1-#3, #8).</li>
 *   <li>{@code setInstructionObserverThreshold} + {@link #observeInstructionCount}
 *       — CPU DoS guard (§4 #9), charged to the run's {@link BudgetTracker}.</li>
 *   <li>{@code setMaximumInterpreterStackDepth} — deep-recursion guard (§4 #10).</li>
 * </ul>
 *
 * <p>Scope is created via {@code initSafeStandardObjects()} (NOT
 * {@code initStandardObjects()} — Judge ruling #2) then scrubbed by
 * {@link ScopeScrubber}.
 *
 * <p>Thread model: each workflow run {@link #enter()}s a Context bound to the
 * current (workflow) thread and stashes its {@link BudgetTracker} via
 * {@code putThreadLocal} so {@link #observeInstructionCount} can find it.
 */
public final class L1SandboxFactory extends ContextFactory {

    static final String BUDGET_KEY = "workflow.budget";

    private static final int INSTRUCTION_OBSERVER_THRESHOLD = 10_000;
    private static final int MAX_INTERPRETER_STACK_DEPTH = 64;

    @Override
    protected Context makeContext() {
        Context cx = super.makeContext();
        cx.setOptimizationLevel(-1);
        cx.setLanguageVersion(Context.VERSION_ES6);
        cx.setInstructionObserverThreshold(INSTRUCTION_OBSERVER_THRESHOLD);
        cx.setMaximumInterpreterStackDepth(MAX_INTERPRETER_STACK_DEPTH);
        // §4 #1-#3/#8: no Java class is ever visible to script.
        cx.setClassShutter(fullName -> false);
        return cx;
    }

    @Override
    protected void observeInstructionCount(Context cx, int instructionCount) {
        Object budget = cx.getThreadLocal(BUDGET_KEY);
        if (budget instanceof BudgetTracker tracker) {
            // throws WorkflowBudgetExceededException when the cap is crossed
            tracker.addInstructions(instructionCount);
        }
    }

    /**
     * Enters a sandbox Context bound to the current thread and registers the
     * run's budget so {@link #observeInstructionCount} can charge it. Caller MUST
     * {@code Context.exit()} in a finally block.
     */
    public Context enter(BudgetTracker tracker) {
        Context cx = enterContext();
        cx.putThreadLocal(BUDGET_KEY, tracker);
        return cx;
    }

    /**
     * Creates a safe top-level scope: {@code initSafeStandardObjects()} +
     * {@link ScopeScrubber#scrub}. The returned scope has no Java bridge, no
     * {@code eval}, and a neutralised {@code Function} constructor.
     */
    public Scriptable createSafeScope(Context cx) {
        ScriptableObject scope = cx.initSafeStandardObjects();
        ScopeScrubber.scrub(cx, scope);
        return scope;
    }
}
