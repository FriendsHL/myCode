package com.skillforge.workflow.exception;

/**
 * Thrown when {@code startRun} is called for a workflow name that already has an
 * in-flight run. Sprint-1 enforcement is a single-machine stripe lock
 * ({@code ConsolidationLock}); Sprint 2 upgrades it to a PG advisory lock so the
 * guard holds across multiple server instances.
 */
public class WorkflowAlreadyRunningException extends RuntimeException {
    public WorkflowAlreadyRunningException(String workflowName) {
        super("Workflow already running: " + workflowName);
    }
}
