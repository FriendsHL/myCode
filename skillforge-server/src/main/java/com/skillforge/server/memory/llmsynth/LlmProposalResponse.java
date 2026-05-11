package com.skillforge.server.memory.llmsynth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * MEMORY-LLM-SYNTHESIS (V68): wire shape of LLM JSON responses for any phase.
 * Schema-validation happens AFTER parse in {@link LlmMemorySynthesizer}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LlmProposalResponse(List<RawProposal> proposals) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RawProposal(
            String type,
            List<Long> sourceMemoryIds,
            Long winnerMemoryId,
            String suggestedTitle,
            String suggestedContent,
            String suggestedImportance,
            String reasoning) {
    }
}
