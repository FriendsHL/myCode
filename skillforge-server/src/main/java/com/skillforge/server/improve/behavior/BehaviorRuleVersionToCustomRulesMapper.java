package com.skillforge.server.improve.behavior;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.AgentDefinition.BehaviorRulesConfig.CustomRule;
import com.skillforge.core.model.AgentDefinition.BehaviorRulesConfig.Severity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * BEHAVIOR-RULE-AB-EVAL V1 — single-responsibility mapper that converts a
 * {@code BehaviorRuleVersionEntity.rulesJson} string (V4 storage shape:
 * {@code [{id, priority, when, then, rationale}]}) into a list of
 * {@link CustomRule} instances suitable for injecting into an
 * {@code AgentDefinition.behaviorRules.customRules} list so the
 * {@code SystemPromptBuilder.appendBehaviorRules} call site renders the rule
 * text in the candidate side of an A/B run.
 *
 * <p>Severity is derived from V4 priority:
 * <ul>
 *   <li>{@code P0}, {@code P1} → {@link Severity#MUST}</li>
 *   <li>{@code P2} → {@link Severity#SHOULD}</li>
 *   <li>Anything else (incl. {@code P3}, unknown, null) → {@link Severity#MAY}</li>
 * </ul>
 *
 * <p><b>r1-FIX (BLOCKER, architect review)</b>: AgentDefinition's
 * {@link CustomRule#CustomRule(Severity, String)} constructor takes
 * {@code (Severity, String)} — NOT {@code (String, Severity)}. Confirmed at
 * {@code AgentDefinition.java:100}. The original r0 draft inverted these and
 * would have silently miscoupled severity/text fields if not for the explicit
 * type check Java provides on Severity vs String.
 *
 * <p>Failure semantics: malformed JSON returns {@link List#of()} + warn log.
 * The A/B run still proceeds (candidate then effectively has no extra rules
 * over baseline → delta ≈ 0). Crashing here would block the run unnecessarily.
 */
@Component
public final class BehaviorRuleVersionToCustomRulesMapper {

    private static final Logger log = LoggerFactory.getLogger(BehaviorRuleVersionToCustomRulesMapper.class);

    private final ObjectMapper objectMapper;

    public BehaviorRuleVersionToCustomRulesMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Convert a V4 rulesJson string to a list of CustomRules. Returns
     * {@link List#of()} for null/blank/empty-array input or on parse failure.
     */
    public List<CustomRule> toCustomRules(String rulesJson) {
        if (rulesJson == null || rulesJson.isBlank() || "[]".equals(rulesJson.trim())) {
            return List.of();
        }
        try {
            List<Map<String, Object>> rows = objectMapper.readValue(
                    rulesJson, new TypeReference<List<Map<String, Object>>>() {});
            return rows.stream()
                    .map(this::toCustomRule)
                    .filter(Objects::nonNull)
                    .toList();
        } catch (JsonProcessingException ex) {
            log.warn("BehaviorRuleVersion rulesJson parse failed, returning empty CustomRule list: {}",
                    ex.getMessage());
            return List.of();
        }
    }

    private CustomRule toCustomRule(Map<String, Object> row) {
        if (row == null) {
            return null;
        }
        Object thenRaw = row.get("then");
        String then = thenRaw == null ? null : String.valueOf(thenRaw);
        if (then == null || then.isBlank()) {
            return null;
        }
        Object whenRaw = row.get("when");
        String when = whenRaw == null ? null : String.valueOf(whenRaw);
        String text = (when == null || when.isBlank()) ? then : "When " + when + ", " + then;
        String priority = String.valueOf(row.getOrDefault("priority", "P3"));
        Severity severity = switch (priority.toUpperCase()) {
            case "P0", "P1" -> Severity.MUST;
            case "P2"       -> Severity.SHOULD;
            default         -> Severity.MAY;
        };
        // ★ r1-FIX: (Severity, String) — NOT (String, Severity).
        return new CustomRule(severity, text);
    }
}
