package com.skillforge.workflow.bindings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.flywheel.run.FlywheelRunService;
import com.skillforge.server.flywheel.run.FlywheelRunStepEntity;
import com.skillforge.workflow.WorkflowContext;
import com.skillforge.workflow.exception.WorkflowPausedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link HostHumanApprove} first-pause path — verifies the persisted
 * {@code step_input_json} carries the current phase (so the FE DAG groups the
 * gate under its phase, not "(unphased)").
 *
 * <p>{@code call(...)} is exercised with an empty {@code args[]} so {@code payload}
 * is {@code null} and no Rhino {@code Context} is needed for {@code JsConversions}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("HostHumanApprove")
class HostHumanApproveTest {

    @Mock private FlywheelRunService runService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private WorkflowContext ctx;

    @BeforeEach
    void setUp() {
        ctx = new WorkflowContext("run-1", null, null);
        ctx.setObjectMapper(objectMapper);
        ctx.setFlywheelRunService(runService);
        // broadcaster left null → no WS broadcast in the unit test
    }

    @Test
    @DisplayName("records the current phase into step_input_json")
    void recordsCurrentPhase() throws Exception {
        ctx.recordPhase("Load");
        ctx.recordPhase("Approve");  // most-recent phase → the gate's phase
        when(runService.appendStep(eq("run-1"), any(), eq(FlywheelRunStepEntity.STEP_KIND_HUMAN_APPROVE), any()))
                .thenReturn("step-1");

        HostHumanApprove binding = new HostHumanApprove(ctx);

        assertThatThrownBy(() -> binding.call(null, null, null, new Object[0]))
                .isInstanceOf(WorkflowPausedException.class);

        ArgumentCaptor<String> jsonCap = ArgumentCaptor.forClass(String.class);
        verify(runService).appendStep(eq("run-1"), jsonCap.capture(),
                eq(FlywheelRunStepEntity.STEP_KIND_HUMAN_APPROVE), any());
        JsonNode node = objectMapper.readTree(jsonCap.getValue());
        assertThat(node.get("phase").asText()).isEqualTo("Approve");
        assertThat(node.get("stepKind").asText()).isEqualTo("human_approve");
    }

    @Test
    @DisplayName("omits phase when no phase() has run yet")
    void omitsPhaseWhenNone() throws Exception {
        when(runService.appendStep(eq("run-1"), any(), eq(FlywheelRunStepEntity.STEP_KIND_HUMAN_APPROVE), any()))
                .thenReturn("step-1");

        HostHumanApprove binding = new HostHumanApprove(ctx);

        assertThatThrownBy(() -> binding.call(null, null, null, new Object[0]))
                .isInstanceOf(WorkflowPausedException.class);

        ArgumentCaptor<String> jsonCap = ArgumentCaptor.forClass(String.class);
        verify(runService).appendStep(eq("run-1"), jsonCap.capture(),
                eq(FlywheelRunStepEntity.STEP_KIND_HUMAN_APPROVE), any());
        JsonNode node = objectMapper.readTree(jsonCap.getValue());
        assertThat(node.has("phase")).isFalse();  // key omitted when no phase recorded
    }
}
