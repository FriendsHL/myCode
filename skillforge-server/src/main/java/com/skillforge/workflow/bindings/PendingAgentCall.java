package com.skillforge.workflow.bindings;

import java.util.concurrent.CompletableFuture;

/**
 * Placeholder returned by {@code agent()} when invoked inside a
 * {@code parallel()} collection phase (plan §2.1 step 2). Holds the deterministic
 * step index and the {@link CompletableFuture} for the offloaded
 * {@code engine.run}. {@code HostParallel} collects these from each thunk and
 * barrier-joins them after all thunks have been evaluated on the workflow thread.
 *
 * <p>This is a plain Java carrier (never exposed to JS as a usable value) — the
 * V1 thunk constraint (plan §2.2) requires the thunk's tail expression to be the
 * {@code agent()} call itself, so JS code never does arithmetic on this handle.
 */
public final class PendingAgentCall {

    private final int stepIndex;
    private final CompletableFuture<Object> future;

    public PendingAgentCall(int stepIndex, CompletableFuture<Object> future) {
        this.stepIndex = stepIndex;
        this.future = future;
    }

    public int getStepIndex() {
        return stepIndex;
    }

    public CompletableFuture<Object> getFuture() {
        return future;
    }
}
