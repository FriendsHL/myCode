package com.skillforge.workflow.exception;

/**
 * Thrown when an {@code agent('...', {agentSlug})} call references an
 * {@code agentSlug} that does not resolve to a {@code t_agent} row.
 */
public class WorkflowAgentNotFoundException extends RuntimeException {
    public WorkflowAgentNotFoundException(String agentSlug) {
        super("Workflow agent not found for slug: " + agentSlug);
    }
}
