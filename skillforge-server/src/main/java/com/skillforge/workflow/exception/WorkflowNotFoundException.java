package com.skillforge.workflow.exception;

/**
 * Thrown when a workflow is requested by name but no {@code *.workflow.js}
 * definition is registered under it.
 */
public class WorkflowNotFoundException extends RuntimeException {
    public WorkflowNotFoundException(String workflowName) {
        super("Workflow not found: " + workflowName);
    }
}
