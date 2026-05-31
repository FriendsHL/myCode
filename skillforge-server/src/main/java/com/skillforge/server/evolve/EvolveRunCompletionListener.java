package com.skillforge.server.evolve;

import com.skillforge.server.flywheel.run.FlywheelRunEntity;
import com.skillforge.server.flywheel.run.FlywheelRunRepository;
import com.skillforge.server.flywheel.run.FlywheelRunService;
import com.skillforge.server.service.event.SessionLoopFinishedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * AUTOEVOLVE-AGENT-FLYWHEEL Module C (run lifecycle) — closes the evolve run when
 * its top-level orchestrator session loop finishes.
 *
 * <p><b>Why this exists.</b> {@code EvolveController} starts an evolve run
 * ({@code FlywheelRun}, loop_kind=evolve) in {@code running} and kicks the
 * orchestrator session. Nothing else moves the run to a terminal state — so when
 * the orchestrator finishes its summary (or dies on a transient LLM error), the
 * run stays {@code running} forever. That both leaves a misleading "in progress"
 * row AND 409-blocks every future evolve run for the agent (the
 * {@code hasActiveEvolveRun} in-flight guard). This listener marks the run
 * terminal from the orchestrator session's own terminal status — no retry, no
 * resurrection: a failed orchestrator simply yields an {@code error} run the
 * operator can re-trigger past.
 *
 * <p><b>Pattern.</b> Mirrors {@code SkillCreatorEvalCoordinator}: an
 * {@code @Async @TransactionalEventListener(AFTER_COMMIT, fallbackExecution=true)}
 * with its own {@code REQUIRES_NEW} tx and a defensive catch — {@code ChatService}
 * publishes {@link SessionLoopFinishedEvent} from a non-transactional loop
 * teardown, so {@code fallbackExecution=true} is required or the listener never
 * fires.
 */
@Component
public class EvolveRunCompletionListener {

    private static final Logger log = LoggerFactory.getLogger(EvolveRunCompletionListener.class);

    /** finalStatus value (SessionLoopFinishedEvent) for a clean completion. */
    private static final String FINAL_COMPLETED = "completed";
    /** finalStatus value for a paused-on-ask loop that will resume — must NOT terminate the run. */
    private static final String FINAL_WAITING_USER = "waiting_user";

    /** Cap the summary stored as the run's contentMd (defensive; finalMessage can be large). */
    private static final int MAX_CONTENT_MD = 16_000;

    private final FlywheelRunRepository flywheelRunRepository;
    private final FlywheelRunService flywheelRunService;

    public EvolveRunCompletionListener(FlywheelRunRepository flywheelRunRepository,
                                       FlywheelRunService flywheelRunService) {
        this.flywheelRunRepository = flywheelRunRepository;
        this.flywheelRunService = flywheelRunService;
    }

    @Async
    @TransactionalEventListener(
            phase = TransactionPhase.AFTER_COMMIT,
            // ChatService.runLoop publishes the event in a non-@Transactional
            // teardown — without fallbackExecution the AFTER_COMMIT listener is
            // silently dropped when no tx is active (see SkillCreatorEvalCoordinator).
            fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onSessionLoopFinished(SessionLoopFinishedEvent event) {
        if (event == null || event.sessionId() == null) {
            return;
        }
        try {
            handleFinish(event);
        } catch (RuntimeException ex) {
            // Defense-in-depth: the publishing tx is already committed; never let a
            // failure here surface back. Worst case the run is left running and a
            // future trigger 409s — recoverable, not data loss.
            log.error("[EvolveRunCompletionListener] failed to finalize evolve run for "
                    + "sessionId={} — run may be left 'running'", event.sessionId(), ex);
        }
    }

    private void handleFinish(SessionLoopFinishedEvent event) {
        FlywheelRunEntity run = flywheelRunRepository
                .findFirstByGeneratorSessionId(event.sessionId())
                .orElse(null);
        if (run == null) {
            // Vast majority of session-finished events are NOT evolve orchestrators.
            return;
        }
        if (!FlywheelRunEntity.LOOP_KIND_EVOLVE.equals(run.getLoopKind())) {
            // The generator session drives some other loop kind (e.g. opt_report) —
            // those have their own completion path; don't touch them.
            return;
        }

        String status = run.getStatus();
        if (FlywheelRunEntity.STATUS_COMPLETED.equals(status)
                || FlywheelRunEntity.STATUS_ERROR.equals(status)) {
            // Already terminal (idempotent on a duplicate / replayed event).
            return;
        }

        String finalStatus = event.finalStatus();
        if (FINAL_WAITING_USER.equals(finalStatus)) {
            // The orchestrator paused on an ask; the loop will resume and finish
            // later. Leave the run running so it can complete on the next event.
            return;
        }

        if (FINAL_COMPLETED.equals(finalStatus)) {
            flywheelRunService.markCompleted(run.getId(), truncate(event.finalMessage()), null);
            log.info("[EvolveRunCompletionListener] evolve run {} -> completed (orchestrator session {} finished)",
                    run.getId(), event.sessionId());
        } else {
            // error / aborted_by_hook / cancelled / anything non-clean → error.
            // No retry, no resurrection — a failed orchestrator yields an error run.
            flywheelRunService.markError(run.getId(),
                    "orchestrator session ended: " + finalStatus);
            log.info("[EvolveRunCompletionListener] evolve run {} -> error (orchestrator session {} finalStatus={})",
                    run.getId(), event.sessionId(), finalStatus);
        }
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= MAX_CONTENT_MD ? s : s.substring(0, MAX_CONTENT_MD);
    }
}
