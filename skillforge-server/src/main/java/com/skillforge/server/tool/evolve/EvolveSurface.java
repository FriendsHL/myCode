package com.skillforge.server.tool.evolve;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * AUTOEVOLVE-AGENT-FLYWHEEL Module B — the three optimisation surfaces the
 * A/B-eval tools route over. The wire value (sent by the orchestrator agent in
 * the {@code surface} field) matches the surface vocabulary already used by
 * {@code OptimizationEventEntity.SURFACE_*} and the auto-trigger listener's
 * switch ({@code OptimizationEventAutoTriggerListener}).
 *
 * <p>This is shared validation/parsing only — each tool fans out to the
 * EXISTING surface-specific service; nothing here re-implements A/B compute.
 */
public enum EvolveSurface {

    PROMPT("prompt"),
    SKILL("skill"),
    BEHAVIOR_RULE("behavior_rule");

    private final String wire;

    EvolveSurface(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    /**
     * Parse the agent-supplied {@code surface} value. Returns {@code null} for a
     * null/blank/unknown value so the calling tool can surface a clean
     * validation error (rather than throwing here and forcing a try/catch).
     */
    public static EvolveSurface fromWire(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        for (EvolveSurface s : values()) {
            if (s.wire.equals(trimmed)) {
                return s;
            }
        }
        return null;
    }

    /** Comma-separated list of accepted wire values, for error messages / schema. */
    public static String acceptedValues() {
        return Arrays.stream(values())
                .map(EvolveSurface::wire)
                .collect(Collectors.joining(", "));
    }
}
