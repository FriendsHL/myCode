package com.skillforge.server.improve.surface;

import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.PromptVersionEntity;
import com.skillforge.server.improve.PromptImproverService;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.repository.PromptVersionRepository;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * MULTI-SURFACE-FLYWHEEL V4 Phase 1.1 — Prompt surface adapter.
 *
 * <p>Pure adapter over V3 {@link PromptImproverService} /
 * {@link PromptVersionRepository}. Same Phase 1.1 contract as
 * {@link SkillSurface}: validates the interface shape, lives in the
 * {@link SurfaceRegistry}, defers full sandbox / canary wiring to Phase 1.2 /
 * 1.3.
 *
 * <p>Methods without a 1:1 V3 service entry point throw
 * {@link UnsupportedOperationException} — see per-method javadoc.
 */
@Component
public class PromptSurface implements OptimizableSurface<PromptVersionEntity> {

    public static final String SURFACE_TYPE = "prompt";

    private final PromptVersionRepository versionRepository;
    private final AgentRepository agentRepository;
    private final PromptImproverService improverService;

    /**
     * Phase 1.2 — session-scoped registry of which {@link PromptVersionEntity}
     * is currently injected for which sandbox session. Mirror of
     * {@code SkillSurface.injectedBySession} (concurrent because two
     * different-agent A/B runs may overlap; entries are agent-scoped via
     * session id which is per-A/B run).
     *
     * <p>Phase 1.2 caller — {@code PromptImproverService.runEvalSet} — does
     * <i>not</i> consume this map (it gets the {@code PromptVersionEntity}
     * directly as the {@code version} argument of {@code runEvalSet}). The
     * map exists for Phase 1.3+ surface-aware dispatch.
     */
    private final ConcurrentMap<String, PromptVersionEntity> injectedBySession = new ConcurrentHashMap<>();

    /*
     * @Lazy on improverService breaks the constructor-injection cycle:
     * PromptImproverService now extends AbstractAbEvalRunner<PromptVersionEntity>
     * and takes PromptSurface in its super() call. PromptSurface.promote() and
     * .createCandidate() — the only callers of improverService — are NOT
     * invoked by AbstractAbEvalRunner.run() in Phase 1.2 (the
     * PromptImproverService subclass overrides promoteIfNeeded to call its
     * own promotionService directly). The @Lazy proxy only resolves if
     * external Phase 1.3 dispatch calls PromptSurface.promote().
     */
    public PromptSurface(PromptVersionRepository versionRepository,
                         AgentRepository agentRepository,
                         @Lazy PromptImproverService improverService) {
        this.versionRepository = versionRepository;
        this.agentRepository = agentRepository;
        this.improverService = improverService;
    }

    @Override
    public String surfaceType() {
        return SURFACE_TYPE;
    }

    @Override
    public PromptVersionEntity loadActive(Long agentId) {
        if (agentId == null) return null;
        AgentEntity agent = agentRepository.findById(agentId).orElse(null);
        if (agent == null || agent.getActivePromptVersionId() == null) return null;
        return versionRepository.findById(agent.getActivePromptVersionId()).orElse(null);
    }

    @Override
    public PromptVersionEntity loadVersion(String versionId) {
        if (versionId == null || versionId.isBlank()) return null;
        return versionRepository.findById(versionId).orElse(null);
    }

    @Override
    public PromptVersionEntity createCandidate(PromptVersionEntity baseline,
                                                String improvementContext) {
        // V3 PromptImproverService.startImprovementFromAttribution is the
        // existing entry point for attribution-driven candidate creation. The
        // signatures don't line up cleanly with OptimizableSurface.createCandidate
        // (V3 needs eventId / agentId / attributedDescription / ownerId; we
        // only have a baseline + free-form improvementContext here). Mapping
        // this method to startImprovementFromAttribution would require
        // synthesizing eventId / ownerId values, which is wrong.
        //
        // Phase 1.2 will adjust either the V3 service signature or the V4
        // interface to bridge cleanly. Today: throw with a clear pointer.
        throw new UnsupportedOperationException(
                "PromptSurface.createCandidate: V3 PromptImproverService.startImprovementFromAttribution "
                        + "requires eventId + ownerId audit fields beyond the generic "
                        + "OptimizableSurface.createCandidate signature. Call PromptImproverService directly "
                        + "(or wait for Phase 1.2's AbstractAbEvalRunner integration).");
    }

    @Override
    public void injectForSandbox(SandboxContext ctx, PromptVersionEntity version) {
        // Phase 1.2: stash the version under the sandbox session id. The V3
        // per-version sandbox build inside AbEvalPipeline.run still owns the
        // actual AgentDefinition copy + systemPrompt substitution — that's
        // intentional: rebuilding the candidateDef inside the pipeline
        // preserves zero behavior drift vs the pre-V4 path. The map is
        // registry-only, no I/O.
        //
        // Passing version=null deletes the entry (used to tear down after a
        // sandbox session ends — Phase 1.3+).
        if (ctx == null || ctx.sessionId() == null || ctx.sessionId().isBlank()) {
            throw new IllegalArgumentException(
                    "SandboxContext.sessionId is required for injectForSandbox");
        }
        if (version == null) {
            injectedBySession.remove(ctx.sessionId());
        } else {
            injectedBySession.put(ctx.sessionId(), version);
        }
    }

    /**
     * Phase 1.2 helper — return the {@link PromptVersionEntity} most recently
     * injected for the given sandbox session, or {@code null} when no entry
     * exists. Not used by {@code AbstractAbEvalRunner.run()} in Phase 1.2.
     * Reserved for Phase 1.3 surface-aware dispatch.
     */
    public PromptVersionEntity getInjectedVersion(String sessionId) {
        return sessionId == null ? null : injectedBySession.get(sessionId);
    }

    @Override
    public void promote(PromptVersionEntity candidate) {
        // V3 PromptPromotionService.evaluateAndPromote takes (abRunId, agentId),
        // not a bare candidate — it owns gate logic (delta threshold, 24h
        // cooldown, decline tracking) that's tightly coupled to having an
        // ab_run row. Adapting that to "promote a bare candidate" would
        // either bypass the gates (wrong) or require recovering the abRunId
        // from the candidate (extra repo lookup with unclear semantics).
        //
        // Phase 1.2 will route AbstractAbEvalRunner.promoteIfNeeded through
        // the existing evaluateAndPromote path with the abRunId in hand.
        throw new UnsupportedOperationException(
                "PromptSurface.promote: V3 PromptPromotionService.evaluateAndPromote needs an "
                        + "abRunId to enforce gates (delta / cooldown / decline). Call "
                        + "PromptPromotionService.evaluateAndPromote(abRunId, agentId) directly.");
    }

    @Override
    public void rollback(PromptVersionEntity candidate) {
        // V3 has PromptPromotionService.rollbackToVersion(agent, target) —
        // takes a different shape. Phase 1.2 / 1.3 will bridge.
        throw new UnsupportedOperationException(
                "PromptSurface.rollback: V3 PromptPromotionService.rollbackToVersion takes "
                        + "(agent, target) — different shape. Phase 1.2 will bridge.");
    }
}
