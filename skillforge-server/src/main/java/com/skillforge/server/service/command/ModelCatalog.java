package com.skillforge.server.service.command;

import com.skillforge.server.config.LlmProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Aggregates the configured LLM providers / models (the project source of truth
 * for model availability — there is no {@code t_model_provider} DB table; PRD's
 * mention of "registered providers" maps to {@link LlmProperties.ProviderConfig}).
 *
 * <p>Used by:
 * <ul>
 *   <li>{@code ModelCommandHandler} — INV-8 validation: {@code /model gpt-4} must
 *       resolve to either a configured {@code provider:model} pair or a bare
 *       model that exists under any provider's {@code models[]}.</li>
 *   <li>{@code ModelsCommandHandler} — listing.</li>
 * </ul>
 *
 * <p>The id format mirrors {@code LlmModelsController}: {@code "<provider>:<model>"};
 * a bare {@code "<model>"} is also accepted by {@link #isAvailable(String)} so users
 * who don't care about provider routing don't have to type the prefix.
 */
@Component
public class ModelCatalog {

    private final LlmProperties llmProperties;

    public ModelCatalog(LlmProperties llmProperties) {
        this.llmProperties = llmProperties;
    }

    public record ModelEntry(String providerName, String modelName, boolean isDefault) {

        public String fullId() {
            return providerName + ":" + modelName;
        }
    }

    /**
     * All known models across all providers, deduplicated by fullId. Order is
     * provider declaration order in YAML; default provider sorted first.
     */
    public List<ModelEntry> listAll() {
        Map<String, LlmProperties.ProviderConfig> providers = llmProperties.getProviders();
        if (providers == null || providers.isEmpty()) return List.of();
        String defaultProvider = llmProperties.getDefaultProvider();

        List<ModelEntry> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        // Default provider first (so /models shows it at the top).
        if (defaultProvider != null && providers.containsKey(defaultProvider)) {
            collect(defaultProvider, providers.get(defaultProvider), out, seen);
        }
        for (Map.Entry<String, LlmProperties.ProviderConfig> e : providers.entrySet()) {
            if (e.getKey().equals(defaultProvider)) continue;
            collect(e.getKey(), e.getValue(), out, seen);
        }
        return out;
    }

    private void collect(String providerName,
                         LlmProperties.ProviderConfig provider,
                         List<ModelEntry> out,
                         Set<String> seen) {
        if (provider == null) return;
        List<String> candidates = new ArrayList<>();
        if (provider.getModels() != null) candidates.addAll(provider.getModels());
        if (provider.getModel() != null && !provider.getModel().isBlank()) {
            candidates.add(provider.getModel());
        }
        String defaultModel = provider.getModel();
        boolean providerIsDefault = providerName.equals(llmProperties.getDefaultProvider());
        candidates.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .distinct()
                .forEach(model -> {
                    String id = providerName + ":" + model;
                    if (seen.add(id)) {
                        boolean isDefault = providerIsDefault && model.equals(defaultModel);
                        out.add(new ModelEntry(providerName, model, isDefault));
                    }
                });
    }

    /**
     * INV-8 validation. Accepts either:
     * <ul>
     *   <li>{@code "<provider>:<model>"} — exact match in catalog</li>
     *   <li>{@code "<model>"} — model name appearing under ANY provider</li>
     * </ul>
     */
    public boolean isAvailable(String modelId) {
        if (modelId == null || modelId.isBlank()) return false;
        String trimmed = modelId.trim();
        List<ModelEntry> all = listAll();
        if (trimmed.contains(":")) {
            return all.stream().anyMatch(e -> e.fullId().equals(trimmed));
        }
        return all.stream().anyMatch(e -> e.modelName().equals(trimmed));
    }

    /**
     * Returns the set of provider names that expose a given bare model name.
     * Helpful for callers who want to disambiguate.
     */
    public List<String> providersFor(String modelName) {
        if (modelName == null) return List.of();
        return listAll().stream()
                .filter(e -> e.modelName().equals(modelName))
                .map(ModelEntry::providerName)
                .collect(Collectors.toList());
    }
}
