package com.skillforge.server.memory.llmsynth;

/**
 * MEMORY-LLM-SYNTHESIS (V68): aggregate counters for one user × one synthesis run.
 */
public record SynthesisRunResult(
        String runId,
        boolean skipped,
        String skipReason,
        int clusters,
        int dedupProposals,
        int reflectionProposals,
        int optimizeProposals,
        int contradictionProposals,
        long inputTokens,
        long outputTokens,
        double estimatedUsd) {

    public static SynthesisRunResult skipped(String reason) {
        return new SynthesisRunResult(null, true, reason, 0, 0, 0, 0, 0, 0L, 0L, 0.0);
    }

    public static SynthesisRunResult success(String runId,
                                             int clusters,
                                             int dedupProposals,
                                             int reflectionProposals,
                                             int optimizeProposals,
                                             int contradictionProposals,
                                             long inputTokens,
                                             long outputTokens,
                                             double estimatedUsd) {
        return new SynthesisRunResult(runId, false, null,
                clusters, dedupProposals, reflectionProposals,
                optimizeProposals, contradictionProposals,
                inputTokens, outputTokens, estimatedUsd);
    }

    public int totalProposals() {
        return dedupProposals + reflectionProposals + optimizeProposals + contradictionProposals;
    }
}
