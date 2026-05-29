package com.skillforge.core.engine;

import com.skillforge.core.llm.LlmProvider;
import com.skillforge.core.llm.LlmProviderFactory;
import com.skillforge.core.llm.LlmRequest;
import com.skillforge.core.llm.LlmResponse;
import com.skillforge.core.llm.LlmStreamHandler;
import com.skillforge.core.model.AgentDefinition;
import com.skillforge.core.skill.SkillRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression test for {@link AgentLoopEngine#resolveProvider} — the provider /
 * model resolution from an {@link AgentDefinition} {@code modelId}.
 *
 * <p>Root cause guarded here (found during AUTOEVOLVING V1 Sprint 4 e2e testing):
 * an agent whose {@code modelId} carries an explicit {@code "provider:"} prefix
 * for a provider that is NOT configured (its API key env var is blank → the
 * provider is skipped at startup, see {@code SkillForgeConfig#llmProviderFactory})
 * used to fall through and hand the DEFAULT provider the full prefixed model id
 * (e.g. {@code "claude:claude-sonnet-4-20250514"}). The default provider's
 * gateway then rejected the unknown model with a misleading downstream error —
 * xiaomi-mimo returns {@code HTTP 401 "Invalid API Key"} for an unknown model,
 * masking the real cause (claude not wired). The fix fails fast with a clear,
 * actionable message instead.</p>
 *
 * <p>The bare-model-id path (no {@code "provider:"} prefix → default provider)
 * is intentionally preserved and locked by {@link #bareModelId_usesDefaultProvider}
 * / {@link #nullModelId_usesDefaultProvider}.</p>
 */
@DisplayName("AgentLoopEngine.resolveProvider — provider/model resolution + fail-fast on unconfigured named provider")
class AgentLoopEngineResolveProviderTest {

    private static final String DEFAULT_PROVIDER = "xiaomi-mimo";

    private LlmProvider mimo;
    private AgentLoopEngine engine;

    @BeforeEach
    void setUp() {
        LlmProviderFactory factory = new LlmProviderFactory();
        // Hand-written stub (skillforge-core test scope has no Mockito). Only identity
        // matters here — resolveProvider never calls chat/chatStream.
        mimo = new StubProvider(DEFAULT_PROVIDER);
        // Only the default provider is configured; "claude" is deliberately absent
        // (mirrors a deployment with a blank ANTHROPIC_API_KEY).
        factory.registerProvider(DEFAULT_PROVIDER, mimo);
        engine = new AgentLoopEngine(
                factory, DEFAULT_PROVIDER, new SkillRegistry(),
                List.of(), List.of(), List.of());
    }

    /** Minimal {@link LlmProvider} stub; resolveProvider only needs object identity. */
    private record StubProvider(String name) implements LlmProvider {
        @Override
        public String getName() {
            return name;
        }

        @Override
        public LlmResponse chat(LlmRequest request) {
            throw new UnsupportedOperationException("not invoked in resolveProvider tests");
        }

        @Override
        public void chatStream(LlmRequest request, LlmStreamHandler handler) {
            throw new UnsupportedOperationException("not invoked in resolveProvider tests");
        }
    }

    @Test
    @DisplayName("prefixed model id for a CONFIGURED provider → that provider + stripped model name")
    void prefixedConfiguredProvider_usesItAndStripsPrefix() {
        AgentDefinition def = new AgentDefinition();
        def.setModelId("xiaomi-mimo:mimo-v2.5-pro");
        String[] resolvedModel = new String[1];

        LlmProvider provider = engine.resolveProvider(def, resolvedModel);

        assertThat(provider).isSameAs(mimo);
        assertThat(resolvedModel[0]).isEqualTo("mimo-v2.5-pro");
    }

    @Test
    @DisplayName("prefixed model id for an UNCONFIGURED provider → fail fast with clear message (no silent fallback)")
    void prefixedUnconfiguredProvider_failsFast() {
        AgentDefinition def = new AgentDefinition();
        def.setModelId("claude:claude-sonnet-4-20250514");
        String[] resolvedModel = new String[1];

        assertThatThrownBy(() -> engine.resolveProvider(def, resolvedModel))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("claude")
                .hasMessageContaining("claude:claude-sonnet-4-20250514")
                .hasMessageContaining("not configured");
    }

    @Test
    @DisplayName("bare model id (no provider prefix) → default provider, model id unchanged")
    void bareModelId_usesDefaultProvider() {
        AgentDefinition def = new AgentDefinition();
        def.setModelId("mimo-v2.5-pro");
        String[] resolvedModel = new String[1];

        LlmProvider provider = engine.resolveProvider(def, resolvedModel);

        assertThat(provider).isSameAs(mimo);
        assertThat(resolvedModel[0]).isEqualTo("mimo-v2.5-pro");
    }

    @Test
    @DisplayName("empty provider prefix (\":model\") → treated as unconfigured provider, fails fast")
    void emptyProviderPrefix_failsFast() {
        // indexOf(':') == 0 → providerName == "" → getProvider("") == null → fail-fast.
        // Degenerate input (no caller produces a leading colon) but locks the behavior:
        // an empty provider name is NOT silently routed to the default provider.
        AgentDefinition def = new AgentDefinition();
        def.setModelId(":mimo-v2.5-pro");
        String[] resolvedModel = new String[1];

        assertThatThrownBy(() -> engine.resolveProvider(def, resolvedModel))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not configured");
    }

    @Test
    @DisplayName("null model id → default provider, resolved model stays null")
    void nullModelId_usesDefaultProvider() {
        AgentDefinition def = new AgentDefinition();
        def.setModelId(null);
        String[] resolvedModel = new String[1];

        LlmProvider provider = engine.resolveProvider(def, resolvedModel);

        assertThat(provider).isSameAs(mimo);
        assertThat(resolvedModel[0]).isNull();
    }
}
