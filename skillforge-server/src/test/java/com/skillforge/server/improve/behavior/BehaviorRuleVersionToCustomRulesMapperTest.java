package com.skillforge.server.improve.behavior;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.skillforge.core.model.AgentDefinition.BehaviorRulesConfig.CustomRule;
import com.skillforge.core.model.AgentDefinition.BehaviorRulesConfig.Severity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BehaviorRuleVersionToCustomRulesMapper")
class BehaviorRuleVersionToCustomRulesMapperTest {

    private BehaviorRuleVersionToCustomRulesMapper mapper;

    @BeforeEach
    void setUp() {
        // Mirror Spring's default ObjectMapper (JavaTimeModule registered per
        // java.md footgun #1) so behavior under tests matches production.
        ObjectMapper om = new ObjectMapper().registerModule(new JavaTimeModule());
        mapper = new BehaviorRuleVersionToCustomRulesMapper(om);
    }

    @Nested
    @DisplayName("priority → severity mapping")
    class PriorityMapping {

        @Test
        @DisplayName("P0 → MUST")
        void p0_maps_to_must() {
            List<CustomRule> rules = mapper.toCustomRules("""
                    [{"id":"r1","priority":"P0","when":"X","then":"Y"}]
                    """);
            assertThat(rules).hasSize(1);
            assertThat(rules.get(0).getSeverity()).isEqualTo(Severity.MUST);
        }

        @Test
        @DisplayName("P1 → MUST")
        void p1_maps_to_must() {
            List<CustomRule> rules = mapper.toCustomRules("""
                    [{"id":"r1","priority":"P1","when":"X","then":"Y"}]
                    """);
            assertThat(rules.get(0).getSeverity()).isEqualTo(Severity.MUST);
        }

        @Test
        @DisplayName("P2 → SHOULD")
        void p2_maps_to_should() {
            List<CustomRule> rules = mapper.toCustomRules("""
                    [{"id":"r1","priority":"P2","when":"X","then":"Y"}]
                    """);
            assertThat(rules.get(0).getSeverity()).isEqualTo(Severity.SHOULD);
        }

        @Test
        @DisplayName("P3 → MAY")
        void p3_maps_to_may() {
            List<CustomRule> rules = mapper.toCustomRules("""
                    [{"id":"r1","priority":"P3","when":"X","then":"Y"}]
                    """);
            assertThat(rules.get(0).getSeverity()).isEqualTo(Severity.MAY);
        }

        @Test
        @DisplayName("unknown priority → MAY (defensive default)")
        void unknown_maps_to_may() {
            List<CustomRule> rules = mapper.toCustomRules("""
                    [{"id":"r1","priority":"WEIRD","when":"X","then":"Y"}]
                    """);
            assertThat(rules.get(0).getSeverity()).isEqualTo(Severity.MAY);
        }
    }

    @Nested
    @DisplayName("constructor argument order (r1-FIX BLOCKER)")
    class ConstructorArgOrder {

        // ★ This test is the explicit r1-FIX regression net:
        // If anyone accidentally writes `new CustomRule(text, severity)`, the
        // severity field would be set to SHOULD (fromString fallback) and the
        // text field would receive the Severity enum's toString — these
        // assertions would catch both inversions.
        @Test
        @DisplayName("CustomRule constructed as (Severity, String) — severity & text not swapped")
        void constructor_arg_order_not_swapped() {
            List<CustomRule> rules = mapper.toCustomRules("""
                    [{"id":"r1","priority":"P0","when":"foo","then":"bar"}]
                    """);
            CustomRule r = rules.get(0);
            assertThat(r.getSeverity()).isEqualTo(Severity.MUST);
            assertThat(r.getText()).isEqualTo("When foo, bar");
            // Defence in depth: if args were swapped, severity would be the
            // text string parsed back to SHOULD (via Severity.fromString
            // fallback) AND text would equal "MUST".
            assertThat(r.getText()).isNotEqualTo("MUST");
            assertThat(r.getSeverity()).isNotEqualTo(Severity.SHOULD);
        }
    }

    @Nested
    @DisplayName("text composition")
    class TextComposition {

        @Test
        @DisplayName("when present + then present → 'When {when}, {then}'")
        void when_and_then_both_present() {
            List<CustomRule> rules = mapper.toCustomRules("""
                    [{"id":"r1","priority":"P1","when":"editing files","then":"prefer Edit over Write"}]
                    """);
            assertThat(rules.get(0).getText()).isEqualTo("When editing files, prefer Edit over Write");
        }

        @Test
        @DisplayName("when blank → text = then alone")
        void when_blank_uses_then_only() {
            List<CustomRule> rules = mapper.toCustomRules("""
                    [{"id":"r1","priority":"P1","when":"","then":"always quote paths"}]
                    """);
            assertThat(rules.get(0).getText()).isEqualTo("always quote paths");
        }

        @Test
        @DisplayName("when missing → text = then alone")
        void when_missing_uses_then_only() {
            List<CustomRule> rules = mapper.toCustomRules("""
                    [{"id":"r1","priority":"P1","then":"always quote paths"}]
                    """);
            assertThat(rules.get(0).getText()).isEqualTo("always quote paths");
        }
    }

    @Nested
    @DisplayName("edge cases")
    class EdgeCases {

        @Test
        @DisplayName("null input → empty list")
        void null_returns_empty() {
            assertThat(mapper.toCustomRules(null)).isEmpty();
        }

        @Test
        @DisplayName("blank input → empty list")
        void blank_returns_empty() {
            assertThat(mapper.toCustomRules("   ")).isEmpty();
        }

        @Test
        @DisplayName("'[]' empty array → empty list")
        void empty_array_returns_empty() {
            assertThat(mapper.toCustomRules("[]")).isEmpty();
        }

        @Test
        @DisplayName("malformed JSON does NOT throw — returns empty list (resilient A/B)")
        void malformed_returns_empty_no_throw() {
            // Don't crash A/B run on a bad rulesJson — log + degrade to no
            // candidate rule (delta ≈ 0).
            assertThat(mapper.toCustomRules("not json at all")).isEmpty();
            assertThat(mapper.toCustomRules("{")).isEmpty();
        }

        @Test
        @DisplayName("row with blank 'then' → filtered out")
        void blank_then_filtered() {
            List<CustomRule> rules = mapper.toCustomRules("""
                    [{"id":"r1","priority":"P1","when":"X","then":""},
                     {"id":"r2","priority":"P1","when":"X","then":"keep me"}]
                    """);
            assertThat(rules).hasSize(1);
            assertThat(rules.get(0).getText()).isEqualTo("When X, keep me");
        }

        @Test
        @DisplayName("multiple rows preserved in order")
        void multiple_rows_in_order() {
            List<CustomRule> rules = mapper.toCustomRules("""
                    [{"id":"r1","priority":"P0","when":"a","then":"1"},
                     {"id":"r2","priority":"P2","when":"b","then":"2"},
                     {"id":"r3","priority":"P3","when":"c","then":"3"}]
                    """);
            assertThat(rules).hasSize(3);
            assertThat(rules.get(0).getSeverity()).isEqualTo(Severity.MUST);
            assertThat(rules.get(1).getSeverity()).isEqualTo(Severity.SHOULD);
            assertThat(rules.get(2).getSeverity()).isEqualTo(Severity.MAY);
            assertThat(rules.get(0).getText()).isEqualTo("When a, 1");
            assertThat(rules.get(1).getText()).isEqualTo("When b, 2");
            assertThat(rules.get(2).getText()).isEqualTo("When c, 3");
        }
    }
}
