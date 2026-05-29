package com.skillforge.workflow.exception;

/**
 * Thrown when a workflow exceeds a hard budget cap — instruction count
 * (CPU DoS guard, §4 #9), agent-call count (LLM spend DoS guard, §4 #12),
 * or wall-clock timeout (§4 #9).
 *
 * <p>Unchecked (domain error) per project Java rules. Propagates out of the
 * Rhino {@code evaluateString} call so {@code WorkflowRunnerService} can mark
 * the run as errored.
 */
public class WorkflowBudgetExceededException extends RuntimeException {

    public WorkflowBudgetExceededException(String message) {
        super(message);
    }
}
