package com.skillforge.core.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards default {@code maxTokens} against regression. Bumped from historical 4096 → 16384
 * (LLM-OUTPUT-BUDGET-AND-TRUNCATE Fix 1).
 */
class LlmRequestTest {

    @Test
    @DisplayName("default maxTokens equals DEFAULT_MAX_TOKENS constant (16384)")
    void defaultMaxTokens_matchesConstant() {
        LlmRequest req = new LlmRequest();
        assertThat(req.getMaxTokens()).isEqualTo(LlmRequest.DEFAULT_MAX_TOKENS);
        assertThat(LlmRequest.DEFAULT_MAX_TOKENS).isEqualTo(16384);
    }

    @Test
    @DisplayName("explicit setMaxTokens still wins over default")
    void setMaxTokens_overridesDefault() {
        LlmRequest req = new LlmRequest();
        req.setMaxTokens(2000);
        assertThat(req.getMaxTokens()).isEqualTo(2000);
    }
}
