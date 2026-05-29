package com.skillforge.workflow;

import com.skillforge.workflow.sandbox.BudgetTracker;
import com.skillforge.workflow.ws.WorkflowWsBroadcaster;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-run workflow execution context. Lives for the duration of one
 * {@code evaluateString} call and is shared between the host bindings
 * ({@code HostAgent} / {@code HostParallel} / {@code HostPhase} / {@code HostLog}).
 *
 * <p>Threading: mutated only on the single workflow thread (Rhino is bound to
 * one thread). {@code nextStepIndex} is an {@link AtomicInteger} both for
 * defensive safety and because it is the deterministic step-ordering primitive
 * for Sprint-2 journal-replay (plan §3.2): the index is assigned <em>at the
 * moment {@code agent()} is invoked</em> (single-threaded, in program order),
 * NOT when the offloaded {@code engine.run} completes.
 */
public final class WorkflowContext {

    private final String runId;
    private final Map<String, Object> args;
    private final BudgetTracker budget;

    private final AtomicInteger nextStepIndex = new AtomicInteger(0);

    /**
     * True while {@code parallel()/pipeline()} is evaluating its thunks: in this
     * mode {@code agent()} offloads {@code engine.run} to the sub-agent executor
     * and returns a placeholder instead of blocking (plan §2.1). Set/read only
     * on the workflow thread; {@code volatile} for visibility hygiene.
     */
    private volatile boolean inParallelCollect = false;

    // Observability captured for assertions / WS broadcast (spike: in-memory).
    private final List<String> phases = Collections.synchronizedList(new ArrayList<>());
    private final List<String> logs = Collections.synchronizedList(new ArrayList<>());
    private final List<String> invokeThreadNames = Collections.synchronizedList(new ArrayList<>());

    /**
     * Optional WS broadcaster for {@code phase()}/{@code log()} events. Null in
     * pure unit tests (spike) — then events are only recorded in-memory. Set by
     * {@code WorkflowRunnerService} for real runs.
     */
    private WorkflowWsBroadcaster broadcaster;

    public WorkflowContext(String runId, Map<String, Object> args, BudgetTracker budget) {
        this.runId = runId;
        this.args = args != null ? args : Collections.emptyMap();
        this.budget = budget;
    }

    public void setBroadcaster(WorkflowWsBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    public String getRunId() {
        return runId;
    }

    public Map<String, Object> getArgs() {
        return args;
    }

    public BudgetTracker getBudget() {
        return budget;
    }

    /** Assigns the next deterministic step index (invoke-order). */
    public int nextStepIndex() {
        return nextStepIndex.getAndIncrement();
    }

    public boolean isInParallelCollect() {
        return inParallelCollect;
    }

    public void setInParallelCollect(boolean v) {
        this.inParallelCollect = v;
    }

    public void recordPhase(String title) {
        phases.add(title);
        if (broadcaster != null) {
            broadcaster.phaseStarted(runId, title);
        }
    }

    public void recordLog(String message) {
        logs.add(message);
        if (broadcaster != null) {
            broadcaster.logged(runId, message);
        }
    }

    public void recordInvokeThread(String threadName) {
        invokeThreadNames.add(threadName);
    }

    public List<String> getPhases() {
        return phases;
    }

    public List<String> getLogs() {
        return logs;
    }

    public List<String> getInvokeThreadNames() {
        return invokeThreadNames;
    }
}
