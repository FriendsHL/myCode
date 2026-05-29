package com.skillforge.workflow.exception;

/**
 * Thrown when a workflow file's {@code export const meta = {...}} block is
 * missing, is not a pure object literal, or omits a required field
 * ({@code name} / {@code description}). FR-1.3: {@code meta} must be a pure
 * literal — variables, function calls, template strings, and spreads are
 * rejected so the phase list can be statically validated and {@code GET
 * /api/workflows} can list definitions without executing them.
 */
public class WorkflowMetaException extends RuntimeException {
    public WorkflowMetaException(String message) {
        super(message);
    }
}
