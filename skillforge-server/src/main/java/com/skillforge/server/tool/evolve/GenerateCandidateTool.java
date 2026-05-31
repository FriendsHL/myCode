package com.skillforge.server.tool.evolve;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import com.skillforge.server.entity.SkillDraftEntity;
import com.skillforge.server.improve.BehaviorRuleImproverService;
import com.skillforge.server.improve.ImprovementStartResult;
import com.skillforge.server.improve.PromptImproverService;
import com.skillforge.server.improve.SkillDraftService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AUTOEVOLVE-AGENT-FLYWHEEL Module C (FR-C2) — agent-callable thin wrapper that
 * generates an improvement candidate for one of the three optimisation surfaces
 * by delegating to the EXISTING improver services. This tool does NOT
 * re-implement the LLM-fill: it routes to the same candidate-generation path
 * {@code AttributionApprovalService} uses, threading the orchestrator-supplied
 * {@code targetAgentId}, and returns the persisted {@code candidateId}.
 *
 * <p><b>Surface routing</b> (mirrors {@code AttributionApprovalService}'s
 * dispatch methods):
 * <ul>
 *   <li>{@code prompt} → {@link PromptImproverService#startImprovementFromAttribution}
 *       ({@code candidateId} = the new prompt version id)</li>
 *   <li>{@code skill} → {@link SkillDraftService#createDraftFromAttribution}
 *       ({@code candidateId} = the new skill draft id)</li>
 *   <li>{@code behavior_rule} → {@link BehaviorRuleImproverService#startImprovementFromAttribution}
 *       ({@code candidateId} = the new behavior-rule version id)</li>
 * </ul>
 *
 * <p><b>eventId linkage (v1 FIRST-CUT, signature adaptation).</b> The existing
 * improver candidate-gen methods persist the candidate with a non-null
 * {@code eventId} FK (the {@code t_optimization_event} audit-trail link). The
 * evolve orchestrator works off an opt-report attribution {@code report} which
 * surfaces real optimization events, so the orchestrator threads the originating
 * {@code eventId} (and, for skill, {@code patternId} + {@code ownerId}) through
 * this tool. When the required linkage fields are absent the tool returns a clear
 * validation error rather than fabricating an event — keeping the wrapper thin
 * (no new persistence / no LLM-fill duplication). 主会话 may later add an
 * attribution-free improver entry point so the orchestrator can generate
 * candidates without a pre-existing opt-event; that is out of scope for this
 * thin v1 wrapper.
 *
 * <p><b>Recursion guard (invariant).</b> Registered ONLY in the main
 * {@code SkillRegistry} (see {@code SkillForgeConfig}); deliberately ABSENT from
 * {@code WorkflowSkillRegistryFactory} (the workflow sub-agent registry) — same
 * isolation invariant as the Module A/B tools. The orchestrator runs top-level.
 */
public class GenerateCandidateTool implements Tool {

    public static final String NAME = "GenerateCandidate";

    private static final Logger log = LoggerFactory.getLogger(GenerateCandidateTool.class);

    private final PromptImproverService promptImproverService;
    private final SkillDraftService skillDraftService;
    private final BehaviorRuleImproverService behaviorRuleImproverService;
    private final ObjectMapper objectMapper;

    public GenerateCandidateTool(PromptImproverService promptImproverService,
                                 SkillDraftService skillDraftService,
                                 BehaviorRuleImproverService behaviorRuleImproverService,
                                 ObjectMapper objectMapper) {
        this.promptImproverService = promptImproverService;
        this.skillDraftService = skillDraftService;
        this.behaviorRuleImproverService = behaviorRuleImproverService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "Generate an improvement candidate for the target agent on one optimisation "
                + "surface, reusing the existing improver service (one-shot LLM fill). Inputs:\n"
                + "- \"surface\": one of \"prompt\", \"skill\", \"behavior_rule\".\n"
                + "- \"issue\": the issue / failure pattern description (from the opt-report "
                + "attribution report) the candidate should address (text or JSON).\n"
                + "- \"targetAgentId\": the agent being evolved (numeric agent id).\n"
                + "- \"eventId\": the originating optimization-event id from the report (required "
                + "for all surfaces — the candidate is linked to it for audit).\n"
                + "- \"patternId\" (skill only): the originating pattern id from the report.\n"
                + "- \"ownerId\" (skill only): the owner user id for the new skill draft.\n"
                + "Returns a \"candidateId\" (prompt version id / skill draft id / behavior-rule "
                + "version id) you then pass to TriggerAbEval.";
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("surface", Map.of(
                "type", "string",
                "enum", java.util.List.of(EvolveSurface.PROMPT.wire(),
                        EvolveSurface.SKILL.wire(),
                        EvolveSurface.BEHAVIOR_RULE.wire()),
                "description", "Optimisation surface: \"prompt\", \"skill\", or \"behavior_rule\"."
        ));
        properties.put("issue", Map.of(
                "type", "string",
                "description", "The issue / failure pattern description (text or JSON) the "
                        + "candidate should address."
        ));
        properties.put("targetAgentId", Map.of(
                "type", "string",
                "description", "The agent being evolved (numeric agent id)."
        ));
        properties.put("eventId", Map.of(
                "type", "string",
                "description", "Originating optimization-event id from the report (required)."
        ));
        properties.put("patternId", Map.of(
                "type", "string",
                "description", "Originating pattern id (required for surface=skill)."
        ));
        properties.put("ownerId", Map.of(
                "type", "string",
                "description", "Owner user id for the new skill draft (required for surface=skill)."
        ));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", java.util.List.of("surface", "issue", "targetAgentId", "eventId"));
        return new ToolSchema(getName(), getDescription(), schema);
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        try {
            if (input == null || input.isEmpty()) {
                return SkillResult.validationError(
                        "input is required (surface, issue, targetAgentId, eventId)");
            }
            EvolveSurface surface = EvolveSurface.fromWire(trimToNull(input.get("surface")));
            if (surface == null) {
                return SkillResult.validationError(
                        "surface is required and must be one of: " + EvolveSurface.acceptedValues());
            }
            String issue = trimToNull(input.get("issue"));
            if (issue == null) {
                return SkillResult.validationError("issue is required");
            }
            String targetAgentId = trimToNull(input.get("targetAgentId"));
            if (targetAgentId == null) {
                return SkillResult.validationError("targetAgentId is required");
            }
            Long eventId = parseLong(input.get("eventId"));
            if (eventId == null) {
                return SkillResult.validationError(
                        "eventId is required (the originating optimization-event id from the "
                                + "report; the candidate is linked to it for audit)");
            }

            // SECURITY note: targetAgentId is threaded to the improver service,
            // which validates the agent exists. The improvers persist the
            // candidate against that agent, so the candidate is owned by it by
            // construction (TriggerAbEval / PromoteCandidate re-validate ownership).
            String candidateId = switch (surface) {
                case PROMPT -> {
                    ImprovementStartResult r = promptImproverService.startImprovementFromAttribution(
                            eventId, targetAgentId, issue, ownerIdOrNull(input));
                    yield r.promptVersionId();
                }
                case BEHAVIOR_RULE -> {
                    ImprovementStartResult r = behaviorRuleImproverService.startImprovementFromAttribution(
                            eventId, targetAgentId, issue, ownerIdOrNull(input));
                    yield r.promptVersionId();
                }
                case SKILL -> generateSkillDraft(input, issue, eventId);
            };

            log.info("[GenerateCandidate] surface={} targetAgentId={} eventId={} -> candidateId={}",
                    surface.wire(), targetAgentId, eventId, candidateId);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("candidateId", candidateId);
            response.put("surface", surface.wire());
            response.put("status", "generated");
            response.put("message", "Candidate generated. Pass candidateId to TriggerAbEval "
                    + "(surface=" + surface.wire() + ") to start the A/B.");
            return SkillResult.success(objectMapper.writeValueAsString(response));
        } catch (IllegalArgumentException e) {
            // Bad agent / missing required field / unparsable number → LLM fixes + retries.
            return SkillResult.validationError(e.getMessage());
        } catch (IllegalStateException e) {
            // Surface precondition not met (e.g. agent has empty system_prompt for the
            // prompt genesis-baseline path) — surface the message to the agent.
            return SkillResult.error(e.getMessage());
        } catch (Exception e) {
            log.error("GenerateCandidate execute failed", e);
            return SkillResult.error("GenerateCandidate error: " + e.getMessage());
        }
    }

    /**
     * Skill surface needs patternId + ownerId (the existing
     * {@code createDraftFromAttribution} signature). A deterministic suggested
     * name mirrors {@code AttributionApprovalService.dispatchSkillSurface}.
     */
    private String generateSkillDraft(Map<String, Object> input, String issue, Long eventId) {
        Long patternId = parseLong(input.get("patternId"));
        if (patternId == null) {
            throw new IllegalArgumentException(
                    "patternId is required for surface=skill (the originating pattern id from the report)");
        }
        Long ownerId = ownerIdOrNull(input);
        if (ownerId == null) {
            throw new IllegalArgumentException(
                    "ownerId is required for surface=skill (owner user id for the new skill draft)");
        }
        String suggestedSkillName = "EvolveSkill" + patternId + "_" + eventId;
        SkillDraftEntity draft = skillDraftService.createDraftFromAttribution(
                eventId,
                patternId,
                issue,
                null,   // expectedImpact — unknown at evolve time; service tolerates null
                null,   // changeType — unknown at evolve time; service tolerates null
                ownerId,
                suggestedSkillName);
        return draft.getId();
    }

    private Long ownerIdOrNull(Map<String, Object> input) {
        return parseLong(input.get("ownerId"));
    }

    private static Long parseLong(Object value) {
        String s = trimToNull(value);
        if (s == null) {
            return null;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("expected a numeric id but got: " + s);
        }
    }

    private static String trimToNull(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }
}
