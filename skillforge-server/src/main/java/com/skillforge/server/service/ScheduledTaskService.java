package com.skillforge.server.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.dto.ScheduledTaskRequest;
import com.skillforge.server.entity.ScheduledTaskEntity;
import com.skillforge.server.entity.ScheduledTaskRunEntity;
import com.skillforge.server.exception.ScheduledTaskAccessDeniedException;
import com.skillforge.server.exception.ScheduledTaskNotFoundException;
import com.skillforge.server.repository.ScheduledTaskRepository;
import com.skillforge.server.repository.ScheduledTaskRunRepository;
import com.skillforge.server.service.event.ScheduledTaskDeletedEvent;
import com.skillforge.server.service.event.ScheduledTaskTriggerRequestedEvent;
import com.skillforge.server.service.event.ScheduledTaskUpsertedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * P12 user-type scheduled task service. Owns CRUD, ownership enforcement,
 * input validation, and run-history persistence; defers all scheduler / executor
 * concerns to BE-2 via Spring application events.
 *
 * <p>Why events instead of a direct {@code UserTaskScheduler} dependency:
 * keeps BE-1 deliverable independent (the brief calls this out — BE-1 must not
 * import BE-2). BE-2 listens for {@link ScheduledTaskUpsertedEvent} /
 * {@link ScheduledTaskDeletedEvent} / {@link ScheduledTaskTriggerRequestedEvent}
 * and calls its scheduler accordingly.
 *
 * <p>Validation contract:
 * <ul>
 *   <li>{@code cronExpr} ⊻ {@code oneShotAt} — exactly one set (DB CHECK + service)</li>
 *   <li>{@code timezone} parses via {@code TimeZone.getTimeZone(tz).getID()}; non-IANA
 *       inputs collapse to {@code GMT}, so we reject anything that doesn't round-trip
 *       its own ID — see {@link #validateTimezone}</li>
 *   <li>{@code cronExpr} parses via Spring's {@code CronExpression.parse}</li>
 *   <li>{@code sessionMode} ∈ ({@code new}, {@code reuse})</li>
 *   <li>Cross-user writes (create with someone else's id, update / delete / get
 *       a row whose creator differs) → {@link AccessDeniedException}</li>
 * </ul>
 */
@Service
public class ScheduledTaskService {

    private static final Logger log = LoggerFactory.getLogger(ScheduledTaskService.class);

    private static final Set<String> VALID_SESSION_MODES = Set.of(
            ScheduledTaskEntity.SESSION_MODE_NEW, ScheduledTaskEntity.SESSION_MODE_REUSE);

    private final ScheduledTaskRepository scheduledTaskRepository;
    private final ScheduledTaskRunRepository scheduledTaskRunRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    public ScheduledTaskService(ScheduledTaskRepository scheduledTaskRepository,
                                ScheduledTaskRunRepository scheduledTaskRunRepository,
                                ApplicationEventPublisher eventPublisher,
                                ObjectMapper objectMapper) {
        this.scheduledTaskRepository = scheduledTaskRepository;
        this.scheduledTaskRunRepository = scheduledTaskRunRepository;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    // -----------------------------------------------------------------------
    // CRUD
    // -----------------------------------------------------------------------

    @Transactional
    public ScheduledTaskEntity create(Long currentUserId, ScheduledTaskRequest req) {
        if (currentUserId == null) {
            throw new IllegalArgumentException("currentUserId is required");
        }
        if (req == null) {
            throw new IllegalArgumentException("request body is required");
        }
        ScheduledTaskEntity entity = new ScheduledTaskEntity();
        entity.setCreatorUserId(currentUserId);

        // Required fields on create
        if (req.getName() == null || req.getName().isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        if (req.getAgentId() == null) {
            throw new IllegalArgumentException("agentId is required");
        }
        if (req.getPromptTemplate() == null || req.getPromptTemplate().isBlank()) {
            throw new IllegalArgumentException("promptTemplate is required");
        }
        entity.setName(req.getName().trim());
        entity.setAgentId(req.getAgentId());
        entity.setPromptTemplate(req.getPromptTemplate());
        entity.setTimezone(req.getTimezone() != null
                ? validateTimezone(req.getTimezone())
                : ScheduledTaskEntity.DEFAULT_TIMEZONE);
        entity.setSessionMode(req.getSessionMode() != null
                ? validateSessionMode(req.getSessionMode())
                : ScheduledTaskEntity.SESSION_MODE_NEW);
        if (req.isChannelTargetPresent()) {
            entity.setChannelTarget(serializeChannelTarget(req.getChannelTarget()));
        }
        if (req.getEnabled() != null) {
            entity.setEnabled(req.getEnabled());
        }
        // cron / one-shot — exactly one must be supplied on create
        applyTriggerFields(entity, req, /* isCreate */ true);

        ScheduledTaskEntity saved = scheduledTaskRepository.save(entity);
        log.info("Scheduled task {} created by user {}", saved.getId(), currentUserId);
        eventPublisher.publishEvent(new ScheduledTaskUpsertedEvent(saved.getId()));
        return saved;
    }

    @Transactional(readOnly = true)
    public List<ScheduledTaskEntity> listForUser(Long currentUserId) {
        if (currentUserId == null) {
            throw new IllegalArgumentException("currentUserId is required");
        }
        return scheduledTaskRepository.findByCreatorUserIdOrderByIdDesc(currentUserId);
    }

    @Transactional(readOnly = true)
    public ScheduledTaskEntity get(Long id, Long currentUserId) {
        ScheduledTaskEntity task = scheduledTaskRepository.findById(id)
                .orElseThrow(() -> new ScheduledTaskNotFoundException(id));
        assertOwnership(task, currentUserId);
        return task;
    }

    @Transactional
    public ScheduledTaskEntity update(Long id, Long currentUserId, ScheduledTaskRequest req) {
        if (req == null) {
            throw new IllegalArgumentException("request body is required");
        }
        ScheduledTaskEntity existing = scheduledTaskRepository.findById(id)
                .orElseThrow(() -> new ScheduledTaskNotFoundException(id));
        assertOwnership(existing, currentUserId);

        if (req.getName() != null) {
            String trimmed = req.getName().trim();
            if (trimmed.isEmpty()) {
                throw new IllegalArgumentException("name cannot be blank");
            }
            existing.setName(trimmed);
        }
        if (req.getAgentId() != null) {
            existing.setAgentId(req.getAgentId());
        }
        if (req.getPromptTemplate() != null) {
            if (req.getPromptTemplate().isBlank()) {
                throw new IllegalArgumentException("promptTemplate cannot be blank");
            }
            existing.setPromptTemplate(req.getPromptTemplate());
        }
        if (req.getTimezone() != null) {
            existing.setTimezone(validateTimezone(req.getTimezone()));
        }
        if (req.getSessionMode() != null) {
            existing.setSessionMode(validateSessionMode(req.getSessionMode()));
        }
        if (req.isChannelTargetPresent()) {
            // Present-and-null => clear column; otherwise serialize map to canonical JSON.
            existing.setChannelTarget(serializeChannelTarget(req.getChannelTarget()));
        }
        if (req.getEnabled() != null) {
            existing.setEnabled(req.getEnabled());
        }
        // cron / one-shot patch — INV-3 supports clearing one and setting the other in the same PUT
        applyTriggerFields(existing, req, /* isCreate */ false);

        ScheduledTaskEntity saved = scheduledTaskRepository.save(existing);
        log.info("Scheduled task {} updated by user {}", saved.getId(), currentUserId);
        eventPublisher.publishEvent(new ScheduledTaskUpsertedEvent(saved.getId()));
        return saved;
    }

    @Transactional
    public void delete(Long id, Long currentUserId) {
        ScheduledTaskEntity existing = scheduledTaskRepository.findById(id)
                .orElseThrow(() -> new ScheduledTaskNotFoundException(id));
        assertOwnership(existing, currentUserId);
        scheduledTaskRepository.delete(existing);
        log.info("Scheduled task {} deleted by user {}", id, currentUserId);
        eventPublisher.publishEvent(new ScheduledTaskDeletedEvent(id, currentUserId));
    }

    /**
     * Manual trigger (INV-10): bypass the {@code enabled} flag and fire now.
     * This service does not actually run anything — it only verifies ownership
     * and publishes an event for BE-2's executor to consume.
     */
    @Transactional(readOnly = true)
    public ScheduledTaskEntity triggerNow(Long id, Long currentUserId) {
        ScheduledTaskEntity task = get(id, currentUserId);
        eventPublisher.publishEvent(new ScheduledTaskTriggerRequestedEvent(id));
        return task;
    }

    // -----------------------------------------------------------------------
    // BE-2 hooks
    // -----------------------------------------------------------------------

    /** BE-2 startup recovery — register every enabled task on boot (INV-1). */
    @Transactional(readOnly = true)
    public List<ScheduledTaskEntity> findAllEnabled() {
        return scheduledTaskRepository.findByEnabledTrue();
    }

    /** BE-2: insert a new run row when a fire begins. Returns the persisted row. */
    @Transactional
    public ScheduledTaskRunEntity markRunStart(Long taskId, boolean manual) {
        // Verify task exists — if it was deleted between scheduling and firing,
        // surface that explicitly so the caller doesn't accidentally orphan a run row.
        scheduledTaskRepository.findById(taskId)
                .orElseThrow(() -> new ScheduledTaskNotFoundException(taskId));
        ScheduledTaskRunEntity run = new ScheduledTaskRunEntity();
        run.setTaskId(taskId);
        run.setTriggeredAt(Instant.now());
        run.setStatus(ScheduledTaskRunEntity.STATUS_RUNNING);
        run.setManual(manual);
        return scheduledTaskRunRepository.save(run);
    }

    /**
     * BE-2: close out a run row when execution finishes (or is skipped).
     * Caller decides terminal status / error message / triggered session id.
     */
    @Transactional
    public ScheduledTaskRunEntity markRunFinish(Long runId,
                                                String status,
                                                String errorMessage,
                                                Instant finishedAt,
                                                String triggeredSessionId) {
        ScheduledTaskRunEntity run = scheduledTaskRunRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));
        run.setStatus(status);
        run.setErrorMessage(errorMessage);
        run.setFinishedAt(finishedAt != null ? finishedAt : Instant.now());
        if (triggeredSessionId != null) {
            run.setTriggeredSessionId(triggeredSessionId);
        }
        return scheduledTaskRunRepository.save(run);
    }

    /**
     * BE-2: attach a {@code sessionId} to a still-running run row (between
     * {@link #markRunStart} and {@link #markRunFinish}). Lets the run history
     * link to the running session in the FE without forcing a terminal status.
     * Idempotent — overwrites any prior value.
     */
    @Transactional
    public ScheduledTaskRunEntity attachRunSession(Long runId, String sessionId) {
        ScheduledTaskRunEntity run = scheduledTaskRunRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));
        run.setTriggeredSessionId(sessionId);
        return scheduledTaskRunRepository.save(run);
    }

    /**
     * Skip-shortcut helper (INV-4). Writes a {@code skipped} row directly without
     * a {@code running} ↦ {@code finish} transition.
     */
    @Transactional
    public ScheduledTaskRunEntity markRunSkipped(Long taskId, boolean manual) {
        ScheduledTaskRunEntity run = new ScheduledTaskRunEntity();
        run.setTaskId(taskId);
        run.setTriggeredAt(Instant.now());
        run.setFinishedAt(Instant.now());
        run.setStatus(ScheduledTaskRunEntity.STATUS_SKIPPED);
        run.setManual(manual);
        return scheduledTaskRunRepository.save(run);
    }

    /** Run history page, owner-checked. */
    @Transactional(readOnly = true)
    public List<ScheduledTaskRunEntity> listRuns(Long taskId, Long currentUserId,
                                                 int limit, int offset) {
        ScheduledTaskEntity task = get(taskId, currentUserId);
        int safeLimit = Math.max(1, Math.min(limit, 100));
        int safeOffset = Math.max(0, offset);
        // Pageable wants a "page index", not raw offset. Use offset/limit as page coordinates.
        // For simple offset-style pagination we use offset / limit as page number when offset is a
        // multiple of limit; otherwise fall back to skipping in memory. To keep it predictable for
        // the FE we expose offset semantics by computing a custom slice via PageRequest with
        // size=limit and stepping the page index.
        int pageIndex = safeOffset / safeLimit;
        // remainder rows we'd need to skip when offset is not aligned — we accept the small extra fetch.
        List<ScheduledTaskRunEntity> page = scheduledTaskRunRepository
                .findByTaskIdOrderByTriggeredAtDesc(task.getId(), PageRequest.of(pageIndex, safeLimit));
        int remainder = safeOffset - pageIndex * safeLimit;
        if (remainder > 0 && remainder < page.size()) {
            return page.subList(remainder, page.size());
        }
        return page;
    }

    // -----------------------------------------------------------------------
    // Validation helpers
    // -----------------------------------------------------------------------

    private void applyTriggerFields(ScheduledTaskEntity entity,
                                    ScheduledTaskRequest req,
                                    boolean isCreate) {
        // Apply patch semantics first…
        if (req.isCronExprPresent()) {
            String cron = req.getCronExpr();
            if (cron != null && !cron.isBlank()) {
                validateCron(cron);
                entity.setCronExpr(cron);
            } else {
                entity.setCronExpr(null);
            }
        }
        if (req.isOneShotAtPresent()) {
            entity.setOneShotAt(req.getOneShotAt());
        }

        // …then enforce the XOR invariant on the resulting state.
        boolean hasCron = entity.getCronExpr() != null && !entity.getCronExpr().isBlank();
        boolean hasOneShot = entity.getOneShotAt() != null;
        if (hasCron && hasOneShot) {
            throw new IllegalArgumentException(
                    "cronExpr and oneShotAt are mutually exclusive — set exactly one");
        }
        if (!hasCron && !hasOneShot) {
            // For create, this is always wrong (the row must specify a trigger).
            // For update, we also reject — there's no scheduler-meaningful task without a trigger.
            throw new IllegalArgumentException(
                    "exactly one of cronExpr or oneShotAt is required");
        }
        // If the result is a one-shot, scrub stale cron metadata; symmetrical clean-up.
        if (hasOneShot) {
            entity.setCronExpr(null);
        } else {
            entity.setOneShotAt(null);
        }
    }

    /**
     * INV-8: timezone validation. {@code TimeZone.getTimeZone(tz)} returns {@code GMT}
     * for unknown ids — silent fallback would let "Asia/NotARealCity" sneak in. We
     * verify the parsed id matches the input id and reject otherwise.
     */
    static String validateTimezone(String tz) {
        if (tz == null || tz.isBlank()) {
            throw new IllegalArgumentException("timezone cannot be blank");
        }
        TimeZone parsed = TimeZone.getTimeZone(tz);
        if (!parsed.getID().equals(tz)) {
            // Allow 'GMT' itself and 'UTC' through (TimeZone normalizes UTC to GMT in some JDKs)
            if (("GMT".equals(parsed.getID()) && ("UTC".equals(tz) || "GMT".equals(tz)))) {
                return parsed.getID();
            }
            throw new IllegalArgumentException("invalid timezone: " + tz);
        }
        return parsed.getID();
    }

    static String validateSessionMode(String mode) {
        if (!VALID_SESSION_MODES.contains(mode)) {
            throw new IllegalArgumentException(
                    "invalid sessionMode: " + mode + " (must be 'new' or 'reuse')");
        }
        return mode;
    }

    /**
     * Validate a cron expression using Spring's {@code CronExpression.parse}, which
     * matches the {@code CronTrigger} runtime BE-2 will use — same parser, same
     * semantics, no surprises at fire time.
     */
    static void validateCron(String cron) {
        try {
            CronExpression.parse(cron);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid cron expression: " + cron, e);
        }
    }

    /**
     * Serialize the wire-side {@code channelTarget} map (with camelCase keys
     * {@code channelType} / {@code channelId}, matching FE {@code ChannelTarget})
     * into a canonical JSON string for the entity TEXT column. {@code null} or
     * empty map clears the column. Same pattern as
     * {@code EvalController.toScenarioEntityMap} — keep wire types nested,
     * persist as JSON-encoded TEXT.
     */
    String serializeChannelTarget(Map<String, Object> channelTarget) {
        if (channelTarget == null || channelTarget.isEmpty()) {
            return null;
        }
        // Light validation — we don't want junk JSON in the DB. Don't over-validate
        // (e.g. don't reject unknown channelType values) so the FE can add new
        // platforms without server changes.
        Object channelType = channelTarget.get("channelType");
        Object channelId = channelTarget.get("channelId");
        if (!(channelType instanceof String) || ((String) channelType).isBlank()) {
            throw new IllegalArgumentException(
                    "channelTarget.channelType must be a non-blank string");
        }
        if (!(channelId instanceof String) || ((String) channelId).isBlank()) {
            throw new IllegalArgumentException(
                    "channelTarget.channelId must be a non-blank string");
        }
        try {
            return objectMapper.writeValueAsString(channelTarget);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "Failed to serialize channelTarget: " + e.getMessage(), e);
        }
    }

    private void assertOwnership(ScheduledTaskEntity task, Long currentUserId) {
        if (currentUserId == null) {
            throw new IllegalArgumentException("currentUserId is required");
        }
        if (!currentUserId.equals(task.getCreatorUserId())) {
            throw new ScheduledTaskAccessDeniedException(
                    "user " + currentUserId + " cannot access scheduled task " + task.getId());
        }
    }
}
