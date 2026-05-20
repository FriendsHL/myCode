package com.skillforge.server.flywheel;

import java.time.Instant;

/**
 * FLYWHEEL-PER-RUN — wire shape for {@link FlywheelController#listRuns} responses.
 *
 * <p>One row per {@link com.skillforge.server.entity.OptimizationEventEntity}
 * with the small set of joined context the per-run sidebar needs (agent name,
 * pattern signature snippet). The FE consumes this in the Per-Run mode toggle
 * of the Flywheel observability panel — see
 * {@code docs/requirements/active/FLYWHEEL-PER-RUN/index.md}.
 *
 * <h3>Field contract (Java → TS, java.md footgun #6)</h3>
 * <ul>
 *   <li>{@code Long}/{@code Long?} → {@code number}/{@code number | null}</li>
 *   <li>{@code String}/{@code String?} → {@code string}/{@code string | null}</li>
 *   <li>{@code Instant} → ISO-8601 string (Spring's primary ObjectMapper has
 *       {@code JavaTimeModule} registered + {@code WRITE_DATES_AS_TIMESTAMPS}
 *       disabled, per java.md footgun #1)</li>
 * </ul>
 *
 * <p>{@code errorLabel} is derived in {@link FlywheelRunsService} from
 * {@code currentStage} so the FE doesn't need to re-implement stage→label
 * mapping. {@code null} means "no error in this run state."
 *
 * <p>{@code patternSignature} is the truncated pattern signature ({@link FlywheelRunsService#SIGNATURE_SNIPPET_MAX}
 * characters max with ellipsis when truncated) — full signature is fetched
 * from the pattern detail endpoint on demand. {@code null} only when the
 * pattern row no longer exists (defensive — shouldn't happen since pattern is
 * the parent of optimization_event by FK).
 *
 * <p>{@code candidateSkillDraftUuid} / {@code abRunId} are exposed raw so the
 * FE can deep-link into the draft / A/B run detail pages. We intentionally
 * don't enrich with their downstream status here — that would push the
 * endpoint into N+1 territory and the per-run sidebar only needs IDs for
 * deep-links (not status badges). FE can call the existing draft / abRun
 * endpoints separately if a status badge is wanted later.
 *
 * <p>{@code description} surfaces the full {@code t_optimization_event.description}
 * text the attribution-curator (or operator on reject) writes when the
 * proposal/A-B run reaches a terminal state. For {@code proposal_rejected}
 * it carries the rejection rationale (e.g. "rejected: suspect_surface=other
 * (infrastructure credential failure)..."); for {@code candidate_failed} it
 * carries the LLM/generation error stack tail. Exposed so the Drawer can
 * render it as "原因详情" without operators needing to psql the row.
 * {@code null} when the column wasn't populated (mostly aggregate / pending
 * stages that don't carry a narrative).
 */
public record FlywheelRunDto(
        Long optEventId,
        Long agentId,
        String agentName,
        String surface,
        Long patternId,
        String patternSignature,
        String currentStage,
        String errorLabel,
        Instant startedAt,
        Instant lastUpdatedAt,
        String candidateSkillDraftUuid,
        Long abRunId,
        String description) {
}
