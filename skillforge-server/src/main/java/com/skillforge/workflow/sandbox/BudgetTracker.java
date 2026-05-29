package com.skillforge.workflow.sandbox;

import com.skillforge.workflow.exception.WorkflowBudgetExceededException;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-workflow-run resource budget (plan §4 #9 / #12). Holds the instruction
 * tally (incremented by the L1 sandbox's {@code observeInstructionCount} hook)
 * and the agent-call tally (incremented by {@code HostAgent}).
 *
 * <p>Sprint-1 scope: instruction cap + agent-call cap + wall-clock timeout
 * (§4 #9 / FR-2.4). The timeout is checked from the instruction-observer hook
 * ({@link #addInstructions}) and at each {@link #incrementAgentCalls}, so a
 * runaway script (tight loop or agent flood) trips it. Limitation: while the
 * workflow thread is blocked joining offloaded {@code parallel()} futures it runs
 * no interpreter instructions, so the timeout can only be observed once control
 * returns to Rhino — acceptable for V1 (the offloaded sub-agents have their own
 * engine-level timeouts).
 */
public final class BudgetTracker {

    /** §4 #9: cumulative interpreter instruction cap (CPU DoS guard). */
    public static final long DEFAULT_INSTRUCTION_CAP = 1_000_000L;
    /** §4 #12: max agent() calls per run (LLM spend DoS guard). */
    public static final int DEFAULT_AGENT_CALL_CAP = 1000;
    /** §4 #9 / FR-2.4: max wall-clock duration of a single workflow run. */
    public static final long DEFAULT_TIMEOUT_NANOS = TimeUnit.MINUTES.toNanos(30);

    private final long instructionCap;
    private final int agentCallCap;
    private final long startedAtNanos;
    /** Wall-clock cap relative to {@link #startedAtNanos}; {@code <= 0} disables the check. */
    private final long timeoutNanos;

    private final AtomicLong instructions = new AtomicLong(0);
    private final AtomicInteger agentCalls = new AtomicInteger(0);

    public BudgetTracker(long startedAtNanos) {
        this(DEFAULT_INSTRUCTION_CAP, DEFAULT_AGENT_CALL_CAP, startedAtNanos, 0L);
    }

    public BudgetTracker(long instructionCap, int agentCallCap, long startedAtNanos) {
        this(instructionCap, agentCallCap, startedAtNanos, 0L);
    }

    /**
     * @param timeoutNanos wall-clock budget relative to {@code startedAtNanos};
     *                     {@code <= 0} disables the timeout (used by unit tests
     *                     that pass {@code startedAtNanos = 0}). Production runs
     *                     pass {@link #DEFAULT_TIMEOUT_NANOS}.
     */
    public BudgetTracker(long instructionCap, int agentCallCap, long startedAtNanos, long timeoutNanos) {
        this.instructionCap = instructionCap;
        this.agentCallCap = agentCallCap;
        this.startedAtNanos = startedAtNanos;
        this.timeoutNanos = timeoutNanos;
    }

    /**
     * Called from {@code L1SandboxFactory.observeInstructionCount}. {@code delta}
     * is the instruction count accumulated since the previous observation. Also
     * the heartbeat for the wall-clock timeout check.
     */
    public void addInstructions(int delta) {
        checkTimeout();
        long total = instructions.addAndGet(delta);
        if (total > instructionCap) {
            throw new WorkflowBudgetExceededException(
                    "workflow instruction budget exceeded: " + total + " > " + instructionCap);
        }
    }

    /** Called at the start of each {@code agent()} invocation (workflow thread). */
    public void incrementAgentCalls() {
        checkTimeout();
        int n = agentCalls.incrementAndGet();
        if (n > agentCallCap) {
            throw new WorkflowBudgetExceededException(
                    "workflow agent-call budget exceeded: " + n + " > " + agentCallCap);
        }
    }

    /**
     * §4 #9 / FR-2.4: trip the run if it has exceeded its wall-clock budget.
     * No-op when the timeout is disabled ({@code timeoutNanos <= 0}).
     */
    private void checkTimeout() {
        if (timeoutNanos <= 0) {
            return;
        }
        long elapsed = System.nanoTime() - startedAtNanos;
        if (elapsed > timeoutNanos) {
            throw new WorkflowBudgetExceededException(
                    "workflow wall-clock timeout exceeded: " + TimeUnit.NANOSECONDS.toSeconds(elapsed)
                            + "s > " + TimeUnit.NANOSECONDS.toSeconds(timeoutNanos) + "s");
        }
    }

    public long getInstructions() {
        return instructions.get();
    }

    public int getAgentCalls() {
        return agentCalls.get();
    }

    public long getStartedAtNanos() {
        return startedAtNanos;
    }
}
