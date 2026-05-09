package com.skillforge.core.model;

import com.skillforge.core.llm.LlmRequest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards {@link AgentDefinition#getMaxTokens()} default fallback against regression.
 * Bumped 4096 → {@link LlmRequest#DEFAULT_MAX_TOKENS} (LLM-OUTPUT-BUDGET-AND-TRUNCATE Fix 1).
 */
class AgentDefinitionMaxTokensTest {

    @Test
    @DisplayName("getMaxTokens returns DEFAULT_MAX_TOKENS when config has no max_tokens")
    void getMaxTokens_noConfig_returnsDefault() {
        AgentDefinition def = new AgentDefinition();
        assertThat(def.getMaxTokens()).isEqualTo(LlmRequest.DEFAULT_MAX_TOKENS);
    }

    @Test
    @DisplayName("getMaxTokens reads explicit config override")
    void getMaxTokens_configOverride_returnsConfigValue() {
        AgentDefinition def = new AgentDefinition();
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("max_tokens", 2000);
        def.setConfig(cfg);
        assertThat(def.getMaxTokens()).isEqualTo(2000);
    }
}
