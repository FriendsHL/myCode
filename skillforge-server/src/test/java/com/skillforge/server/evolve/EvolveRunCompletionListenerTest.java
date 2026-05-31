package com.skillforge.server.evolve;

import com.skillforge.server.flywheel.run.FlywheelRunEntity;
import com.skillforge.server.flywheel.run.FlywheelRunRepository;
import com.skillforge.server.flywheel.run.FlywheelRunService;
import com.skillforge.server.service.event.SessionLoopFinishedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * AUTOEVOLVE-AGENT-FLYWHEEL Module C — {@link EvolveRunCompletionListener}: marks
 * the evolve run terminal from the orchestrator session's finish status, and
 * leaves non-evolve / already-terminal / waiting-user runs alone.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EvolveRunCompletionListener")
class EvolveRunCompletionListenerTest {

    @Mock private FlywheelRunRepository flywheelRunRepository;
    @Mock private FlywheelRunService flywheelRunService;

    private EvolveRunCompletionListener listener;

    @BeforeEach
    void setUp() {
        listener = new EvolveRunCompletionListener(flywheelRunRepository, flywheelRunService);
    }

    private FlywheelRunEntity run(String id, String loopKind, String status, String generatorSessionId) {
        FlywheelRunEntity r = new FlywheelRunEntity();
        r.setId(id);
        r.setLoopKind(loopKind);
        r.setStatus(status);
        r.setGeneratorSessionId(generatorSessionId);
        return r;
    }

    private SessionLoopFinishedEvent event(String sessionId, String finalStatus, String finalMessage) {
        return new SessionLoopFinishedEvent(sessionId, finalMessage, finalStatus, 0L);
    }

    @Test
    @DisplayName("clean completion → markCompleted with the summary as contentMd")
    void completed_marksCompleted() {
        when(flywheelRunRepository.findFirstByGeneratorSessionId("sess-1"))
                .thenReturn(Optional.of(run("run-1", FlywheelRunEntity.LOOP_KIND_EVOLVE, "running", "sess-1")));

        listener.onSessionLoopFinished(event("sess-1", "completed", "## summary"));

        verify(flywheelRunService).markCompleted("run-1", "## summary", null);
        verify(flywheelRunService, never()).markError(any(), any());
    }

    @Test
    @DisplayName("error finish → markError, no retry/resurrection")
    void error_marksError() {
        when(flywheelRunRepository.findFirstByGeneratorSessionId("sess-1"))
                .thenReturn(Optional.of(run("run-1", FlywheelRunEntity.LOOP_KIND_EVOLVE, "running", "sess-1")));

        listener.onSessionLoopFinished(event("sess-1", "error", null));

        verify(flywheelRunService).markError(eq("run-1"), org.mockito.ArgumentMatchers.contains("error"));
        verify(flywheelRunService, never()).markCompleted(any(), any(), any());
    }

    @Test
    @DisplayName("aborted_by_hook finish → markError")
    void abortedByHook_marksError() {
        when(flywheelRunRepository.findFirstByGeneratorSessionId("sess-1"))
                .thenReturn(Optional.of(run("run-1", FlywheelRunEntity.LOOP_KIND_EVOLVE, "running", "sess-1")));

        listener.onSessionLoopFinished(event("sess-1", "aborted_by_hook", null));

        verify(flywheelRunService).markError(eq("run-1"), org.mockito.ArgumentMatchers.contains("aborted_by_hook"));
    }

    @Test
    @DisplayName("waiting_user → no-op (loop will resume)")
    void waitingUser_noOp() {
        when(flywheelRunRepository.findFirstByGeneratorSessionId("sess-1"))
                .thenReturn(Optional.of(run("run-1", FlywheelRunEntity.LOOP_KIND_EVOLVE, "running", "sess-1")));

        listener.onSessionLoopFinished(event("sess-1", "waiting_user", null));

        verify(flywheelRunService, never()).markCompleted(any(), any(), any());
        verify(flywheelRunService, never()).markError(any(), any());
    }

    @Test
    @DisplayName("non-evolve generator session (opt_report) → untouched")
    void nonEvolveRun_untouched() {
        when(flywheelRunRepository.findFirstByGeneratorSessionId("sess-1"))
                .thenReturn(Optional.of(run("run-1", FlywheelRunEntity.LOOP_KIND_OPT_REPORT, "running", "sess-1")));

        listener.onSessionLoopFinished(event("sess-1", "completed", "x"));

        verifyNoInteractions(flywheelRunService);
    }

    @Test
    @DisplayName("already terminal evolve run → idempotent no-op")
    void alreadyTerminal_noOp() {
        when(flywheelRunRepository.findFirstByGeneratorSessionId("sess-1"))
                .thenReturn(Optional.of(run("run-1", FlywheelRunEntity.LOOP_KIND_EVOLVE, "completed", "sess-1")));

        listener.onSessionLoopFinished(event("sess-1", "completed", "x"));

        verifyNoInteractions(flywheelRunService);
    }

    @Test
    @DisplayName("no run for the session → no-op (the common case)")
    void noRun_noOp() {
        when(flywheelRunRepository.findFirstByGeneratorSessionId("sess-x"))
                .thenReturn(Optional.empty());

        listener.onSessionLoopFinished(event("sess-x", "completed", "x"));

        verifyNoInteractions(flywheelRunService);
    }

    @Test
    @DisplayName("null event / null sessionId → guarded no-op")
    void nullGuards_noOp() {
        listener.onSessionLoopFinished(null);
        listener.onSessionLoopFinished(event(null, "completed", "x"));

        verifyNoInteractions(flywheelRunRepository, flywheelRunService);
    }
}
