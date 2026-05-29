package com.skillforge.workflow;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-workflow-name mutual-exclusion lock so the same workflow cannot run twice
 * concurrently (plan §6 / Task G).
 *
 * <p><b>Sprint 1 scope:</b> a single-machine stripe — a {@link ConcurrentHashMap}
 * keyed by workflow name. {@link #tryAcquire} atomically claims the name and
 * {@link #release} frees it. This holds on ONE server instance only.
 *
 * <p><b>Sprint 2:</b> replace the in-memory map with a PostgreSQL advisory lock
 * ({@code pg_try_advisory_lock(hash(name))}) so the guard survives across a
 * multi-instance deployment. The public surface ({@code tryAcquire}/{@code
 * release}) stays the same so callers don't change.
 */
@Component
public class ConsolidationLock {

    private final ConcurrentHashMap<String, Boolean> held = new ConcurrentHashMap<>();

    /**
     * Atomically claims the lock for {@code workflowName}.
     *
     * @return {@code true} if acquired, {@code false} if already held.
     */
    public boolean tryAcquire(String workflowName) {
        return held.putIfAbsent(workflowName, Boolean.TRUE) == null;
    }

    /** Releases a previously-acquired lock. Idempotent. */
    public void release(String workflowName) {
        held.remove(workflowName);
    }

    /** Test/observability helper. */
    public boolean isHeld(String workflowName) {
        return held.containsKey(workflowName);
    }
}
