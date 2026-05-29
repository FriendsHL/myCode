package com.skillforge.workflow.engine;

import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.core.skill.Tool;
import com.skillforge.server.tool.GetAgentConfigTool;
import com.skillforge.server.tool.GetTraceTool;
import com.skillforge.server.tool.optreport.LoadSessionBatchTool;
import com.skillforge.server.tool.optreport.RecordBatchAnnotationsTool;
import com.skillforge.server.tool.sessionannotation.AnnotateSessionTool;
import com.skillforge.server.tool.sessionannotation.SpanBehaviorStatsTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AUTOEVOLVING V1 Sprint 3 (B1): {@link WorkflowSkillRegistryFactory} must register
 * the full OPT-REPORT tool subset (self-check risk #1 — "registry 漏 tool 致 agent
 * 跑不了") and nothing privileged beyond it (least privilege).
 */
class WorkflowSkillRegistryFactoryTest {

    private WorkflowSkillRegistryFactory factory;

    private static Tool toolNamed(Class<? extends Tool> type, String name) {
        Tool t = mock(type);
        when(t.getName()).thenReturn(name);
        return t;
    }

    @BeforeEach
    void setUp() {
        factory = new WorkflowSkillRegistryFactory(
                (LoadSessionBatchTool) toolNamed(LoadSessionBatchTool.class, "LoadSessionBatch"),
                (GetAgentConfigTool) toolNamed(GetAgentConfigTool.class, "GetAgentConfig"),
                (GetTraceTool) toolNamed(GetTraceTool.class, "GetTrace"),
                (SpanBehaviorStatsTool) toolNamed(SpanBehaviorStatsTool.class, "SpanBehaviorStats"),
                (AnnotateSessionTool) toolNamed(AnnotateSessionTool.class, "AnnotateSession"),
                (RecordBatchAnnotationsTool) toolNamed(RecordBatchAnnotationsTool.class, "RecordBatchAnnotations"));
    }

    @Test
    @DisplayName("registers exactly the 6 OPT-REPORT tools by name")
    void registersAllSixOptReportTools() {
        SkillRegistry registry = factory.workflowRegistry();

        List<String> names = registry.getAllTools().stream().map(Tool::getName).sorted().toList();
        assertThat(names).containsExactlyInAnyOrder(
                "LoadSessionBatch", "GetAgentConfig", "GetTrace",
                "SpanBehaviorStats", "AnnotateSession", "RecordBatchAnnotations");

        // Every agent that the opt-report workflow dispatches can resolve its tools.
        for (String n : names) {
            assertThat(registry.getTool(n)).isPresent();
        }
    }

    @Test
    @DisplayName("does NOT register privileged / out-of-scope tools (least privilege)")
    void excludesPrivilegedTools() {
        SkillRegistry registry = factory.workflowRegistry();
        for (String forbidden : List.of("WriteOptReport", "SubAgent", "Bash", "FileWrite")) {
            assertThat(registry.getTool(forbidden)).as(forbidden + " must not be registered").isEmpty();
        }
    }

    @Test
    @DisplayName("returns the same shared read-only registry instance")
    void registryIsShared() {
        assertThat(factory.workflowRegistry()).isSameAs(factory.workflowRegistry());
    }
}
