package com.skillforge.server.memory.llmsynth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.compact.TokenEstimator;
import com.skillforge.core.llm.LlmProvider;
import com.skillforge.core.llm.LlmProviderFactory;
import com.skillforge.core.llm.LlmRequest;
import com.skillforge.core.llm.LlmResponse;
import com.skillforge.core.model.Message;
import com.skillforge.server.config.LlmProperties;
import com.skillforge.server.config.MemoryProperties;
import com.skillforge.server.entity.MemoryEntity;
import com.skillforge.server.entity.MemoryProposalEntity;
import com.skillforge.server.repository.MemoryProposalRepository;
import com.skillforge.server.repository.MemoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MEMORY-LLM-SYNTHESIS (V68): orchestrates the 3-phase LLM call per cluster.
 *
 * <p>Per tech-design D7, each cluster gets 3 independent LLM calls (dedup / reflection /
 * optimize) — each with its own try/catch so a single phase failure does not abort the
 * others. Per-call hard cap 8K input / 4K output (D11); while-loop trim until in budget.
 *
 * <p>NO LLM result writes directly to {@code t_memory} — only to {@code t_memory_proposal}.
 * Apply path is {@code MemoryProposalService.approve}.
 *
 * <p>Pricing for DeepSeek-V3 used as estimator: $0.27/M input, $1.10/M output (matches
 * PRD's example numbers; actual provider may differ — figure logged as estimate only).
 */
@Component
public class LlmMemorySynthesizer {

    private static final Logger log = LoggerFactory.getLogger(LlmMemorySynthesizer.class);
    private static final Pattern MARKDOWN_FENCE = Pattern.compile("```(?:json)?\\s*([\\s\\S]+?)\\s*```");

    private static final int MAX_INPUT_TOKENS = 8000;
    private static final int MAX_OUTPUT_TOKENS = 4000;
    private static final int REASONING_MAX_LEN = 200;
    private static final int RESPONSE_EXCERPT_MAX_LEN = 500;
    private static final int DEDUP_MAX_SOURCE_IDS = 5;
    private static final Set<String> VALID_IMPORTANCE = Set.of("high", "medium", "low");

    // DeepSeek-V3 pricing per 1M tokens (USD).
    private static final double USD_PER_M_INPUT = 0.27;
    private static final double USD_PER_M_OUTPUT = 1.10;

    private final MemoryRepository memoryRepository;
    private final MemoryProposalRepository proposalRepository;
    private final LlmProviderFactory llmProviderFactory;
    private final LlmProperties llmProperties;
    private final MemoryProperties memoryProperties;
    private final MemoryClusterer clusterer;
    private final ObjectMapper objectMapper;

    public LlmMemorySynthesizer(MemoryRepository memoryRepository,
                                MemoryProposalRepository proposalRepository,
                                LlmProviderFactory llmProviderFactory,
                                LlmProperties llmProperties,
                                MemoryProperties memoryProperties,
                                MemoryClusterer clusterer,
                                ObjectMapper objectMapper) {
        this.memoryRepository = memoryRepository;
        this.proposalRepository = proposalRepository;
        this.llmProviderFactory = llmProviderFactory;
        this.llmProperties = llmProperties;
        this.memoryProperties = memoryProperties;
        this.clusterer = clusterer;
        this.objectMapper = objectMapper;
    }

    /** Main entry — one synthesis run for one user. */
    public SynthesisRunResult synthesize(Long userId) {
        if (userId == null) {
            return SynthesisRunResult.skipped("null_user_id");
        }
        int cap = memoryProperties.getLlmSynthesis().getMaxCandidatesPerRun();
        if (cap <= 0) cap = 50;
        List<MemoryEntity> candidates = memoryRepository.findTopActiveByUserId(
                userId, PageRequest.of(0, cap));
        if (candidates.size() < MemoryClusterer.MIN_CLUSTER_SIZE) {
            log.debug("LlmMemorySynthesizer: userId={} insufficient candidates ({})",
                    userId, candidates.size());
            return SynthesisRunResult.skipped("not_enough_candidates");
        }

        List<MemoryCluster> clusters = clusterer.cluster(candidates);
        if (clusters.isEmpty()) {
            return SynthesisRunResult.skipped("no_viable_cluster");
        }

        String runId = "synth-" + UUID.randomUUID();
        long totalInput = 0L;
        long totalOutput = 0L;
        int dedupCnt = 0, reflectionCnt = 0, optimizeCnt = 0, contradictionCnt = 0;

        for (MemoryCluster cluster : clusters) {
            // Dedup phase (may also emit contradiction)
            try {
                PhaseOutcome d = runDedupPhase(userId, cluster, runId);
                totalInput += d.inputTokens;
                totalOutput += d.outputTokens;
                dedupCnt += d.dedupCount;
                contradictionCnt += d.contradictionCount;
            } catch (Exception e) {
                log.warn("LlmMemorySynthesizer dedup phase failed userId={} cluster={}: {}",
                        userId, cluster.id(), e.getMessage());
            }
            try {
                PhaseOutcome r = runReflectionPhase(userId, cluster, runId);
                totalInput += r.inputTokens;
                totalOutput += r.outputTokens;
                reflectionCnt += r.reflectionCount;
            } catch (Exception e) {
                log.warn("LlmMemorySynthesizer reflection phase failed userId={} cluster={}: {}",
                        userId, cluster.id(), e.getMessage());
            }
            try {
                PhaseOutcome o = runOptimizePhase(userId, cluster, runId);
                totalInput += o.inputTokens;
                totalOutput += o.outputTokens;
                optimizeCnt += o.optimizeCount;
            } catch (Exception e) {
                log.warn("LlmMemorySynthesizer optimize phase failed userId={} cluster={}: {}",
                        userId, cluster.id(), e.getMessage());
            }
        }

        double estimatedUsd = totalInput * USD_PER_M_INPUT / 1_000_000.0
                + totalOutput * USD_PER_M_OUTPUT / 1_000_000.0;
        log.info("LlmMemorySynthesizer done userId={} runId={} clusters={} dedup={} reflection={} "
                        + "optimize={} contradiction={} inputTokens={} outputTokens={} estimatedUsd={}",
                userId, runId, clusters.size(), dedupCnt, reflectionCnt, optimizeCnt,
                contradictionCnt, totalInput, totalOutput, String.format("%.6f", estimatedUsd));
        return SynthesisRunResult.success(runId, clusters.size(), dedupCnt, reflectionCnt,
                optimizeCnt, contradictionCnt, totalInput, totalOutput, estimatedUsd);
    }

    private PhaseOutcome runDedupPhase(Long userId, MemoryCluster cluster, String runId) throws Exception {
        String user = MemorySynthesisLlmPromptBuilder.buildClusterUserMessage(userId, cluster, objectMapper);
        LlmCallOutput call = callLlm(MemorySynthesisLlmPromptBuilder.DEDUP_SYSTEM_PROMPT, user);
        if (call == null) {
            return PhaseOutcome.empty();
        }
        Optional<LlmProposalResponse> parsed = parseJsonResponse(call.content);
        int dedupOk = 0;
        int contradictionOk = 0;
        if (parsed.isPresent() && parsed.get().proposals() != null) {
            for (LlmProposalResponse.RawProposal raw : parsed.get().proposals()) {
                MemoryProposalEntity saved = persistDedupOrContradiction(userId, runId, cluster, raw, call);
                if (saved != null) {
                    if (MemoryProposalEntity.TYPE_DEDUP.equals(saved.getProposalType())) {
                        dedupOk++;
                    } else if (MemoryProposalEntity.TYPE_CONTRADICTION.equals(saved.getProposalType())) {
                        contradictionOk++;
                    }
                }
            }
        }
        return new PhaseOutcome(call.inputTokens, call.outputTokens, dedupOk, 0, 0, contradictionOk);
    }

    private PhaseOutcome runReflectionPhase(Long userId, MemoryCluster cluster, String runId) throws Exception {
        String user = MemorySynthesisLlmPromptBuilder.buildClusterUserMessage(userId, cluster, objectMapper);
        LlmCallOutput call = callLlm(MemorySynthesisLlmPromptBuilder.REFLECTION_SYSTEM_PROMPT, user);
        if (call == null) {
            return PhaseOutcome.empty();
        }
        Optional<LlmProposalResponse> parsed = parseJsonResponse(call.content);
        int reflOk = 0;
        if (parsed.isPresent() && parsed.get().proposals() != null) {
            for (LlmProposalResponse.RawProposal raw : parsed.get().proposals()) {
                MemoryProposalEntity saved = persistReflection(userId, runId, cluster, raw, call);
                if (saved != null) reflOk++;
            }
        }
        return new PhaseOutcome(call.inputTokens, call.outputTokens, 0, reflOk, 0, 0);
    }

    private PhaseOutcome runOptimizePhase(Long userId, MemoryCluster cluster, String runId) throws Exception {
        long inputTokens = 0L, outputTokens = 0L;
        int optOk = 0;
        for (MemoryEntity m : cluster.memberMemories()) {
            try {
                String user = MemorySynthesisLlmPromptBuilder.buildOptimizeUserMessage(userId, m, objectMapper);
                LlmCallOutput call = callLlm(MemorySynthesisLlmPromptBuilder.OPTIMIZE_SYSTEM_PROMPT, user);
                if (call == null) continue;
                inputTokens += call.inputTokens;
                outputTokens += call.outputTokens;
                Optional<LlmProposalResponse> parsed = parseJsonResponse(call.content);
                if (parsed.isEmpty() || parsed.get().proposals() == null) continue;
                for (LlmProposalResponse.RawProposal raw : parsed.get().proposals()) {
                    // restrict source set to the single memory we sent
                    MemoryCluster oneShot = new MemoryCluster(cluster.id(), List.of(m),
                            Set.of(m.getId()), cluster.dominantType(), cluster.sharedTags());
                    MemoryProposalEntity saved = persistOptimize(userId, runId, oneShot, raw, call);
                    if (saved != null) optOk++;
                }
            } catch (Exception e) {
                log.warn("LlmMemorySynthesizer optimize sub-call failed userId={} memoryId={}: {}",
                        userId, m.getId(), e.getMessage());
            }
        }
        return new PhaseOutcome(inputTokens, outputTokens, 0, 0, optOk, 0);
    }

    private MemoryProposalEntity persistDedupOrContradiction(Long userId, String runId,
                                                             MemoryCluster cluster,
                                                             LlmProposalResponse.RawProposal raw,
                                                             LlmCallOutput call) {
        String type = lower(raw.type());
        if (!MemoryProposalEntity.TYPE_DEDUP.equals(type)
                && !MemoryProposalEntity.TYPE_CONTRADICTION.equals(type)) {
            log.warn("LlmMemorySynthesizer: dedup phase emitted unknown type={} runId={}", type, runId);
            return null;
        }
        List<Long> sourceIds = sanitizeSourceIds(raw.sourceMemoryIds(), cluster.memberIds());
        if (sourceIds.size() < 2) {
            log.warn("LlmMemorySynthesizer: dedup phase sourceIds too few size={} runId={}",
                    sourceIds.size(), runId);
            return null;
        }
        if (MemoryProposalEntity.TYPE_DEDUP.equals(type) && sourceIds.size() > DEDUP_MAX_SOURCE_IDS) {
            log.warn("LlmMemorySynthesizer: dedup sourceIds size {} > {} — dropping (mass-delete guard)",
                    sourceIds.size(), DEDUP_MAX_SOURCE_IDS);
            return null;
        }
        Long winnerId = raw.winnerMemoryId();
        if (MemoryProposalEntity.TYPE_DEDUP.equals(type)) {
            if (winnerId == null || !sourceIds.contains(winnerId)) {
                log.warn("LlmMemorySynthesizer: dedup winner null or not in sourceIds runId={}", runId);
                return null;
            }
        }
        return saveProposal(userId, runId, type, sourceIds, winnerId,
                null, null, null, raw.reasoning(), call);
    }

    private MemoryProposalEntity persistReflection(Long userId, String runId, MemoryCluster cluster,
                                                   LlmProposalResponse.RawProposal raw,
                                                   LlmCallOutput call) {
        if (!MemoryProposalEntity.TYPE_REFLECTION.equals(lower(raw.type()))) {
            log.warn("LlmMemorySynthesizer: reflection phase emitted non-reflection type={}", raw.type());
            return null;
        }
        if (raw.suggestedContent() == null || raw.suggestedContent().isBlank()) {
            log.warn("LlmMemorySynthesizer: reflection missing suggestedContent runId={}", runId);
            return null;
        }
        List<Long> sourceIds = sanitizeSourceIds(raw.sourceMemoryIds(), cluster.memberIds());
        if (sourceIds.size() < 2) {
            log.warn("LlmMemorySynthesizer: reflection sourceIds too few size={}", sourceIds.size());
            return null;
        }
        String importance = raw.suggestedImportance();
        if (importance != null && !VALID_IMPORTANCE.contains(importance.toLowerCase())) {
            importance = "medium";
        }
        return saveProposal(userId, runId, MemoryProposalEntity.TYPE_REFLECTION,
                sourceIds, null, truncate(raw.suggestedTitle(), 256),
                truncate(raw.suggestedContent(), 4000), importance, raw.reasoning(), call);
    }

    private MemoryProposalEntity persistOptimize(Long userId, String runId, MemoryCluster cluster,
                                                 LlmProposalResponse.RawProposal raw,
                                                 LlmCallOutput call) {
        if (!MemoryProposalEntity.TYPE_OPTIMIZE.equals(lower(raw.type()))) {
            log.warn("LlmMemorySynthesizer: optimize phase emitted non-optimize type={}", raw.type());
            return null;
        }
        if (raw.suggestedContent() == null || raw.suggestedContent().isBlank()) {
            return null;
        }
        List<Long> sourceIds = sanitizeSourceIds(raw.sourceMemoryIds(), cluster.memberIds());
        if (sourceIds.size() != 1) {
            log.warn("LlmMemorySynthesizer: optimize requires exactly 1 sourceMemoryId, got {}",
                    sourceIds.size());
            return null;
        }
        return saveProposal(userId, runId, MemoryProposalEntity.TYPE_OPTIMIZE,
                sourceIds, null, truncate(raw.suggestedTitle(), 256),
                truncate(raw.suggestedContent(), 4000), null, raw.reasoning(), call);
    }

    private MemoryProposalEntity saveProposal(Long userId, String runId, String type,
                                              List<Long> sourceIds, Long winnerMemoryId,
                                              String suggestedTitle, String suggestedContent,
                                              String suggestedImportance, String rawReasoning,
                                              LlmCallOutput call) {
        try {
            String sourceJson = objectMapper.writeValueAsString(sourceIds);
            // Cross-run dedup: skip if a proposed/approved proposal already references the same
            // sourceMemoryIds set with the same type. Cheap GIN-backed lookup per-id.
            if (alreadyHasEquivalentProposal(userId, sourceIds, type)) {
                log.debug("LlmMemorySynthesizer: skipping duplicate proposal type={} sourceIds={}",
                        type, sourceIds);
                return null;
            }

            MemoryProposalEntity p = new MemoryProposalEntity();
            p.setUserId(userId);
            p.setSynthesisRunId(runId);
            p.setProposalType(type);
            p.setSourceMemoryIds(sourceJson);
            p.setWinnerMemoryId(winnerMemoryId);
            p.setSuggestedTitle(suggestedTitle);
            p.setSuggestedContent(suggestedContent);
            p.setSuggestedImportance(suggestedImportance);
            // F-N4: reasoning truncation centralized here.
            p.setReasoning(truncate(rawReasoning, REASONING_MAX_LEN));
            p.setLlmResponseExcerpt(truncate(call.content, RESPONSE_EXCERPT_MAX_LEN));
            p.setStatus(MemoryProposalEntity.STATUS_PROPOSED);
            return proposalRepository.save(p);
        } catch (Exception e) {
            log.warn("LlmMemorySynthesizer: persist failed userId={} type={}: {}",
                    userId, type, e.getMessage());
            return null;
        }
    }

    private boolean alreadyHasEquivalentProposal(Long userId, List<Long> sourceIds, String type) {
        if (sourceIds.isEmpty()) return false;
        try {
            // Find candidate proposals that reference the first sourceId, then post-filter exact match.
            String firstIdJsonArray = "[" + sourceIds.get(0) + "]";
            List<MemoryProposalEntity> candidates =
                    proposalRepository.findReferencingMemoryId(userId, firstIdJsonArray);
            for (MemoryProposalEntity c : candidates) {
                if (!type.equals(c.getProposalType())) continue;
                List<Long> existing = parseSourceIds(c.getSourceMemoryIds());
                if (existing.size() == sourceIds.size()
                        && existing.containsAll(sourceIds)
                        && sourceIds.containsAll(existing)) {
                    return true;
                }
            }
        } catch (Exception e) {
            // Best-effort dedup: tolerate native query failure (e.g. embedded H2 in tests).
            log.debug("LlmMemorySynthesizer: cross-run dedup check failed (ignored): {}", e.getMessage());
        }
        return false;
    }

    private List<Long> parseSourceIds(String json) {
        try {
            return objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<List<Long>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────────
    // LLM call helper
    // ─────────────────────────────────────────────────────────────────────────────────

    /**
     * Single LLM call with token-budget enforcement. Returns null when the cluster is
     * so dense it cannot fit in the budget (caller logs + skips).
     */
    LlmCallOutput callLlm(String systemPrompt, String userMessage) {
        String providerName = resolveProviderName();
        LlmProvider provider = llmProviderFactory.getProvider(providerName);
        if (provider == null) {
            throw new IllegalStateException("LLM provider unavailable: " + providerName);
        }

        // W-2 fix: while-loop trim until under input budget OR give up.
        String trimmed = userMessage;
        int iter = 0;
        while (TokenEstimator.estimateString(trimmed) > MAX_INPUT_TOKENS) {
            iter++;
            if (iter > 8 || trimmed.length() < 200) {
                throw new IllegalStateException("cluster too dense to fit token budget");
            }
            trimmed = trimToHalf(trimmed);
        }

        LlmRequest req = new LlmRequest();
        req.setSystemPrompt(systemPrompt);
        List<Message> msgs = new ArrayList<>();
        msgs.add(Message.user(trimmed));
        req.setMessages(msgs);
        req.setMaxTokens(MAX_OUTPUT_TOKENS);
        req.setTemperature(0.3);

        LlmResponse resp = provider.chat(req);
        if (resp == null || resp.getContent() == null) {
            return null;
        }
        int in = 0, out = 0;
        if (resp.getUsage() != null) {
            in = resp.getUsage().getInputTokens();
            out = resp.getUsage().getOutputTokens();
        }
        return new LlmCallOutput(resp.getContent(), in, out);
    }

    private String resolveProviderName() {
        String configured = memoryProperties.getLlmSynthesis().getProvider();
        if (configured != null && !configured.isBlank()) return configured;
        String fallback = llmProperties.getDefaultProvider();
        return fallback != null ? fallback : "bailian";
    }

    Optional<LlmProposalResponse> parseJsonResponse(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        String json = raw.trim();
        Matcher m = MARKDOWN_FENCE.matcher(json);
        if (m.find()) {
            json = m.group(1).trim();
        }
        try {
            return Optional.of(objectMapper.readValue(json, LlmProposalResponse.class));
        } catch (Exception e) {
            log.warn("LlmMemorySynthesizer: invalid JSON from LLM: {}", e.getMessage());
            log.debug("LlmMemorySynthesizer raw: {}", raw);
            return Optional.empty();
        }
    }

    private static String trimToHalf(String s) {
        if (s == null || s.length() < 4) return s;
        int half = s.length() / 2;
        return s.substring(0, half);
    }

    /** Filter LLM-supplied source ids down to the actual cluster member set (no escapes). */
    private List<Long> sanitizeSourceIds(List<Long> raw, Set<Long> allowed) {
        if (raw == null || raw.isEmpty()) return List.of();
        Set<Long> seen = new LinkedHashSet<>();
        for (Long id : raw) {
            if (id != null && allowed.contains(id)) {
                seen.add(id);
            }
        }
        return new ArrayList<>(seen);
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return null;
        if (maxLen <= 0) return "";
        if (s.length() <= maxLen) return s;
        // UTF-16 surrogate-safe: don't slice in the middle of a surrogate pair.
        int end = maxLen;
        if (Character.isHighSurrogate(s.charAt(end - 1))) {
            end--;
        }
        if (end <= 0) return "";
        return s.substring(0, end);
    }

    private static String lower(String s) {
        return s == null ? null : s.toLowerCase();
    }

    /** Token + content tuple returned by {@link #callLlm}. */
    record LlmCallOutput(String content, int inputTokens, int outputTokens) {
    }

    /** Per-phase counters returned by run<Phase>Phase. */
    private static final class PhaseOutcome {
        final long inputTokens;
        final long outputTokens;
        final int dedupCount;
        final int reflectionCount;
        final int optimizeCount;
        final int contradictionCount;

        PhaseOutcome(long inputTokens, long outputTokens, int dedupCount,
                     int reflectionCount, int optimizeCount, int contradictionCount) {
            this.inputTokens = inputTokens;
            this.outputTokens = outputTokens;
            this.dedupCount = dedupCount;
            this.reflectionCount = reflectionCount;
            this.optimizeCount = optimizeCount;
            this.contradictionCount = contradictionCount;
        }

        static PhaseOutcome empty() {
            return new PhaseOutcome(0L, 0L, 0, 0, 0, 0);
        }
    }
}
