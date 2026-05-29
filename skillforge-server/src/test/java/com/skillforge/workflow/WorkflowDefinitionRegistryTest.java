package com.skillforge.workflow;

import com.skillforge.workflow.exception.WorkflowMetaException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task F — {@link WorkflowDefinitionRegistry} meta parsing + pure-literal
 * validation (FR-1.3).
 */
class WorkflowDefinitionRegistryTest {

    private final WorkflowDefinitionRegistry registry = new WorkflowDefinitionRegistry();

    private static final String VALID =
            "export const meta = {\n" +
            "  name: 'hello-world',\n" +
            "  description: 'smoke',\n" +
            "  phases: [ { title: 'Greet', detail: 'hi' }, { title: 'Bye' } ]\n" +
            "}\n" +
            "phase('Greet');\n" +
            "log('hello');\n" +
            "var r = agent('hi', { agentSlug: 'worker' });\n" +
            "return { ok: true, r: r };";

    @Test
    @DisplayName("parses pure-literal meta: name/description/phases + meta-free jsSource + stable sourceHash")
    void parsesValidMeta() {
        WorkflowDefinition def = registry.parse("hello-world.workflow.js", VALID);

        assertThat(def.name()).isEqualTo("hello-world");
        assertThat(def.description()).isEqualTo("smoke");
        assertThat(def.phases()).hasSize(2);
        assertThat(def.phases().get(0).title()).isEqualTo("Greet");
        assertThat(def.phases().get(0).detail()).isEqualTo("hi");
        assertThat(def.phases().get(1).title()).isEqualTo("Bye");
        assertThat(def.phases().get(1).detail()).isNull();

        // jsSource is meta-free + export-free so WorkflowEvaluator can run it.
        assertThat(def.jsSource()).doesNotContain("export");
        assertThat(def.jsSource()).contains("phase('Greet')");
        assertThat(def.jsSource()).contains("agent('hi'");

        // sourceHash stable for identical source.
        assertThat(def.sourceHash()).isNotBlank();
        assertThat(registry.parse("x.js", VALID).sourceHash()).isEqualTo(def.sourceHash());
    }

    @Test
    @DisplayName("rejects dynamic meta: variable reference in a field")
    void rejectsVariableReference() {
        String src = "var X = 'n';\nexport const meta = { name: X, description: 'd' }\nreturn 1;";
        assertThatThrownBy(() -> registry.parse("bad.js", src))
                .isInstanceOf(WorkflowMetaException.class)
                .hasMessageContaining("pure literal");
    }

    @Test
    @DisplayName("rejects dynamic meta: function call in a field")
    void rejectsFunctionCall() {
        String src = "export const meta = { name: 'n', description: build() }\nreturn 1;";
        assertThatThrownBy(() -> registry.parse("bad.js", src))
                .isInstanceOf(WorkflowMetaException.class);
    }

    @Test
    @DisplayName("rejects dynamic meta: template string in a field")
    void rejectsTemplateString() {
        String src = "export const meta = { name: `dyn-${1}`, description: 'd' }\nreturn 1;";
        assertThatThrownBy(() -> registry.parse("bad.js", src))
                .isInstanceOf(WorkflowMetaException.class);
    }

    @Test
    @DisplayName("rejects missing meta declaration")
    void rejectsMissingMeta() {
        assertThatThrownBy(() -> registry.parse("bad.js", "phase('x');\nreturn 1;"))
                .isInstanceOf(WorkflowMetaException.class)
                .hasMessageContaining("missing");
    }

    @Test
    @DisplayName("rejects meta.name missing")
    void rejectsMissingName() {
        String src = "export const meta = { description: 'd' }\nreturn 1;";
        assertThatThrownBy(() -> registry.parse("bad.js", src))
                .isInstanceOf(WorkflowMetaException.class)
                .hasMessageContaining("name");
    }

    @Test
    @DisplayName("accepts nested literals: booleans, numbers, nested objects/arrays")
    void acceptsNestedLiterals() {
        String src =
                "export const meta = {\n" +
                "  name: 'n', description: 'd',\n" +
                "  phases: [ { title: 'A' } ],\n" +
                "  flags: { enabled: true, retries: 3, ratio: -0.5, tags: ['x', 'y'] }\n" +
                "}\n" +
                "return 1;";
        WorkflowDefinition def = registry.parse("ok.js", src);
        assertThat(def.name()).isEqualTo("n");
        assertThat(def.phases()).hasSize(1);
    }
}
