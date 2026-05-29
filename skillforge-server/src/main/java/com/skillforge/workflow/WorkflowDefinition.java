package com.skillforge.workflow;

import java.util.List;

/**
 * A parsed, registered workflow definition (plan §6). Produced by
 * {@link WorkflowDefinitionRegistry} from a {@code *.workflow.js} file.
 *
 * @param name        the {@code meta.name} (unique key used by {@code startRun})
 * @param description the {@code meta.description}
 * @param phases      the {@code meta.phases} list (title + optional detail);
 *                    used for the progress UI / static phase validation
 * @param jsSource    the executable workflow body with the {@code export const
 *                    meta = {...}} declaration removed and {@code export}
 *                    keywords stripped (Rhino cannot parse {@code export}). This
 *                    is what {@link WorkflowEvaluator} runs.
 * @param sourceHash  SHA-256 of the ORIGINAL file source. Sprint-2 resume
 *                    compares this against the run's {@code input_json.sourceHash}
 *                    and refuses to resume against a changed definition
 *                    (plan §3.3 #6).
 */
public record WorkflowDefinition(
        String name,
        String description,
        List<WorkflowPhase> phases,
        String jsSource,
        String sourceHash) {

    public WorkflowDefinition {
        phases = phases == null ? List.of() : List.copyOf(phases);
    }

    /** A single {@code meta.phases[]} entry. */
    public record WorkflowPhase(String title, String detail) {
    }
}
