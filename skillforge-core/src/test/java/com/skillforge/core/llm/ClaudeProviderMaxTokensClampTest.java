package com.skillforge.core.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards {@link ClaudeProvider#clampMaxTokensForModel(int, String)} against regression.
 *
 * <p>Anthropic returns HTTP 400 if {@code max_tokens > model cap}. After
 * LLM-OUTPUT-BUDGET-AND-TRUNCATE Fix 1 raised the global default to 16384, older Claude
 * models (claude-3-opus / claude-3-5-sonnet etc.) would otherwise reject every request.
 */
class ClaudeProviderMaxTokensClampTest {

    @Test
    @DisplayName("claude-3-5-sonnet-20241022 clamps 16384 down to 8192")
    void claude35Sonnet_clampsToCap() {
        assertThat(ClaudeProvider.clampMaxTokensForModel(16384, "claude-3-5-sonnet-20241022"))
                .isEqualTo(8192);
    }

    @Test
    @DisplayName("claude-3-5-sonnet-20240620 clamps 16384 down to 8192")
    void claude35Sonnet0620_clampsToCap() {
        assertThat(ClaudeProvider.clampMaxTokensForModel(16384, "claude-3-5-sonnet-20240620"))
                .isEqualTo(8192);
    }

    @Test
    @DisplayName("claude-3-opus-20240229 clamps 16384 down to 4096")
    void claude3Opus_clampsToCap() {
        assertThat(ClaudeProvider.clampMaxTokensForModel(16384, "claude-3-opus-20240229"))
                .isEqualTo(4096);
    }

    @Test
    @DisplayName("claude-3-haiku-20240307 clamps 16384 down to 4096")
    void claude3Haiku_clampsToCap() {
        assertThat(ClaudeProvider.clampMaxTokensForModel(16384, "claude-3-haiku-20240307"))
                .isEqualTo(4096);
    }

    @Test
    @DisplayName("Unknown model (e.g. claude-opus-4-x) passes through unchanged")
    void unknownModel_passesThrough() {
        assertThat(ClaudeProvider.clampMaxTokensForModel(16384, "claude-opus-4-7"))
                .isEqualTo(16384);
        assertThat(ClaudeProvider.clampMaxTokensForModel(16384, "claude-3-7-sonnet-20250219"))
                .isEqualTo(16384);
        assertThat(ClaudeProvider.clampMaxTokensForModel(32000, "claude-sonnet-4-7"))
                .isEqualTo(32000);
    }

    @Test
    @DisplayName("Requested below cap is not modified")
    void requestedBelowCap_notModified() {
        // 4096 < 8192 (claude-3-5-sonnet cap)
        assertThat(ClaudeProvider.clampMaxTokensForModel(4096, "claude-3-5-sonnet-20241022"))
                .isEqualTo(4096);
        // 2000 < 4096 (claude-3-opus cap)
        assertThat(ClaudeProvider.clampMaxTokensForModel(2000, "claude-3-opus-20240229"))
                .isEqualTo(2000);
    }

    @Test
    @DisplayName("null model passes through (safety fallback)")
    void nullModel_passesThrough() {
        assertThat(ClaudeProvider.clampMaxTokensForModel(16384, null))
                .isEqualTo(16384);
    }

    @Test
    @DisplayName("Equal-to-cap is not modified")
    void equalToCap_notModified() {
        assertThat(ClaudeProvider.clampMaxTokensForModel(8192, "claude-3-5-sonnet-20241022"))
                .isEqualTo(8192);
        assertThat(ClaudeProvider.clampMaxTokensForModel(4096, "claude-3-opus-20240229"))
                .isEqualTo(4096);
    }
}
