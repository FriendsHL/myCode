package com.skillforge.server.service.scheduling;

import com.skillforge.server.entity.ScheduledTaskEntity;
import com.skillforge.server.repository.ScheduledTaskRepository;
import com.skillforge.server.service.event.ScheduledTaskDeletedEvent;
import com.skillforge.server.service.event.ScheduledTaskTriggerRequestedEvent;
import com.skillforge.server.service.event.ScheduledTaskUpsertedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.locks.ReentrantLock;

/**
 * P12 user-type task scheduler.
 *
 * <p>Wraps a {@link ThreadPoolTaskScheduler} with per-task register / unschedule /
 * reschedule lifecycle. Fires events from {@link com.skillforge.server.service.ScheduledTaskService}
 * are consumed via {@link EventListener} so this class never imports BE-1 service
 * directly (matches the BE-1 ↔ BE-2 boundary in the brief).
 *
 * <p>Concurrency invariants (INV-4 skip-if-running):
 * <ul>
 *   <li>{@link #runningTaskIds} is a {@link ConcurrentHashMap#newKeySet()} so the
 *       race between concurrent fires and finish callbacks is correct under the
 *       JMM (set is internally a CHM with volatile semantics).</li>
 *   <li>{@link #scheduledFutures} is also a CHM. Mutations happen inside a
 *       per-instance {@link ReentrantLock} only when we need read-modify-write
 *       atomicity (re-register replacing a previous future).</li>
 * </ul>
 *
 * <p>Shutdown (INV-7): {@code waitForTasksToCompleteOnShutdown=true} +
 * {@code awaitTerminationSeconds=30}. Long-running prompts past 30s are interrupted —
 * brief §10 acknowledges this trade-off.
 */
@Component
public class UserTaskScheduler implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(UserTaskScheduler.class);
    private static final int POOL_SIZE = 4;
    private static final int SHUTDOWN_AWAIT_SECONDS = 30;

    private final ScheduledTaskRepository repository;
    private final ObjectProvider<ScheduledTaskExecutor> executorProvider;

    private final ThreadPoolTaskScheduler taskScheduler;
    private final ConcurrentHashMap<Long, ScheduledFuture<?>> scheduledFutures = new ConcurrentHashMap<>();
    private final Set<Long> runningTaskIds = ConcurrentHashMap.newKeySet();
    /** Mutex around (un)schedule of a specific taskId so reschedule is read-modify-write atomic. */
    private final ConcurrentHashMap<Long, ReentrantLock> taskLocks = new ConcurrentHashMap<>();

    public UserTaskScheduler(ScheduledTaskRepository repository,
                             ObjectProvider<ScheduledTaskExecutor> executorProvider) {
        this.repository = repository;
        this.executorProvider = executorProvider;
        this.taskScheduler = new ThreadPoolTaskScheduler();
        this.taskScheduler.setPoolSize(POOL_SIZE);
        this.taskScheduler.setThreadNamePrefix("user-task-scheduler-");
        this.taskScheduler.setWaitForTasksToCompleteOnShutdown(true);
        this.taskScheduler.setAwaitTerminationSeconds(SHUTDOWN_AWAIT_SECONDS);
        this.taskScheduler.initialize();
    }

    // ---------- in-memory state visibility (executor + tests) ----------

    /** True when this taskId currently has a fire in-flight. INV-4. */
    public boolean isRunning(long taskId) {
        return runningTaskIds.contains(taskId);
    }

    /** Try to mark the task as running. Returns true if the marker was added (caller should fire). */
    public boolean tryMarkRunning(long taskId) {
        return runningTaskIds.add(taskId);
    }

    /** Clear the running marker (called from executor when the run finishes). */
    public void clearRunning(long taskId) {
        runningTaskIds.remove(taskId);
    }

    // ---------- schedule lifecycle ----------

    /**
     * Register all enabled tasks once the application is fully bootstrapped (INV-1).
     * Using {@link ApplicationReadyEvent} (not {@code @PostConstruct}) avoids racing
     * with bean wiring — by the time this fires every dependency is available.
     */
    @EventListener
    public void onApplicationReady(ApplicationReadyEvent event) {
        List<ScheduledTaskEntity> enabled = repository.findByEnabledTrue();
        log.info("UserTaskScheduler startup: registering {} enabled task(s)", enabled.size());
        for (ScheduledTaskEntity t : enabled) {
            try {
                registerInternal(t);
            } catch (Exception e) {
                log.error("Failed to register task {} on startup: {}", t.getId(), e.getMessage(), e);
            }
        }
    }

    /** External register: load entity, schedule it. Idempotent (replaces any prior future). */
    public void register(long taskId) {
        ScheduledTaskEntity task = repository.findById(taskId).orElse(null);
        if (task == null) {
            log.warn("UserTaskScheduler.register: task {} not found", taskId);
            return;
        }
        if (!task.isEnabled()) {
            // Brief INV-1: disabled task is not registered. If a previously-enabled
            // schedule got toggled off, drop the future.
            unschedule(taskId);
            return;
        }
        registerInternal(task);
    }

    public void unschedule(long taskId) {
        ReentrantLock lock = taskLocks.computeIfAbsent(taskId, k -> new ReentrantLock());
        lock.lock();
        try {
            ScheduledFuture<?> future = scheduledFutures.remove(taskId);
            if (future != null) {
                // mayInterruptIfRunning=false: in-flight fires complete naturally; we
                // only stop further scheduling. INV-7 (graceful shutdown) applies at
                // pool shutdown time, not on unschedule of a single task.
                future.cancel(false);
            }
        } finally {
            lock.unlock();
        }
    }

    /** Reschedule = unschedule + register (with the latest entity state). */
    public void reschedule(long taskId) {
        unschedule(taskId);
        register(taskId);
    }

    private void registerInternal(ScheduledTaskEntity task) {
        ReentrantLock lock = taskLocks.computeIfAbsent(task.getId(), k -> new ReentrantLock());
        lock.lock();
        try {
            // Drop any prior future for the same task before installing a new one.
            ScheduledFuture<?> prior = scheduledFutures.remove(task.getId());
            if (prior != null) {
                prior.cancel(false);
            }

            Runnable fire = () -> {
                try {
                    executorProvider.getObject().fire(task.getId(), false);
                } catch (Exception e) {
                    // Defensive — must never bubble out of the scheduler thread, or
                    // ThreadPoolTaskScheduler will silently stop firing this trigger.
                    log.error("ScheduledTaskExecutor.fire threw for task {}: {}",
                            task.getId(), e.getMessage(), e);
                }
            };

            ScheduledFuture<?> future;
            if (task.getCronExpr() != null && !task.getCronExpr().isBlank()) {
                CronTrigger trigger = new CronTrigger(task.getCronExpr(),
                        TimeZone.getTimeZone(task.getTimezone()));
                future = taskScheduler.schedule(fire, trigger);
                // Eagerly compute next fire time for FE display (INV: keep next_fire_at fresh).
                Instant next = computeNextFire(task.getCronExpr(), task.getTimezone());
                if (next != null) {
                    task.setNextFireAt(next);
                    repository.save(task);
                }
            } else if (task.getOneShotAt() != null) {
                Instant trigger = task.getOneShotAt();
                if (trigger.isBefore(Instant.now())) {
                    // r2 W1: One-shot in the past — typically server was down at the
                    // intended fire time. Mark a 'skipped' run and auto-disable the
                    // task so it doesn't sit enabled=true forever (without this, every
                    // restart re-evaluates the same past trigger and never schedules
                    // it, leaving a phantom row in the FE listing).
                    log.info("One-shot task {} fire time {} is in the past — marking as missed",
                            task.getId(), trigger);
                    try {
                        executorProvider.getObject().handleOneShotMissed(task);
                    } catch (Exception e) {
                        log.error("Failed to handleOneShotMissed for task {}: {}",
                                task.getId(), e.getMessage(), e);
                    }
                    return;
                }
                future = taskScheduler.schedule(fire, trigger);
                task.setNextFireAt(trigger);
                repository.save(task);
            } else {
                log.warn("Task {} has neither cronExpr nor oneShotAt — skipping register",
                        task.getId());
                return;
            }
            scheduledFutures.put(task.getId(), future);
        } finally {
            lock.unlock();
        }
    }

    private Instant computeNextFire(String cronExpr, String timezone) {
        try {
            CronExpression expr = CronExpression.parse(cronExpr);
            ZoneId zoneId = TimeZone.getTimeZone(timezone).toZoneId();
            ZonedDateTime now = ZonedDateTime.now(zoneId);
            ZonedDateTime next = expr.next(now);
            return next == null ? null : next.toInstant();
        } catch (Exception e) {
            log.warn("Failed to compute next fire for cron='{}' tz='{}': {}",
                    cronExpr, timezone, e.getMessage());
            return null;
        }
    }

    // ---------- event listeners (BE-1 → BE-2) ----------
    //
    // r2 W3: upserted / deleted bind to AFTER_COMMIT phase. Reasoning:
    //   - Default @EventListener fires synchronously inside the publisher's
    //     transaction. For ScheduledTaskService.delete() that means the unschedule
    //     here happens BEFORE the DELETE has hit the DB. A cron fire firing in the
    //     ~ms window between "unschedule()" and "commit" would race-fetch the
    //     still-existing row, attempt to write a run row, and either get a
    //     phantom run (commit OK) or violate the FK CASCADE invariant in flight.
    //     AFTER_COMMIT delays the listener so the DB is the source of truth.
    //   - For upserted, AFTER_COMMIT also guarantees registerInternal re-reads
    //     the post-commit state — avoids reading uncommitted-rolled-back data
    //     if the publisher's transaction later rolls back (listener simply
    //     never fires, scheduler keeps the prior schedule, which is correct).
    //   - Trigger requested keeps default @EventListener: manual fire-now
    //     intentionally fires synchronously (REST returns 202 immediately and
    //     the user expects the trigger to attempt right away; service side
    //     publishes from a readOnly tx so there's no commit race).

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUpserted(ScheduledTaskUpsertedEvent event) {
        reschedule(event.taskId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDeleted(ScheduledTaskDeletedEvent event) {
        unschedule(event.taskId());
        runningTaskIds.remove(event.taskId());
        taskLocks.remove(event.taskId());
    }

    @EventListener
    public void onTriggerRequested(ScheduledTaskTriggerRequestedEvent event) {
        // INV-10: manual trigger bypasses enabled flag; fire on the same scheduler
        // pool so we don't compete for chat-loop threads.
        Date inAMoment = new Date(System.currentTimeMillis() + 10);
        taskScheduler.schedule(() -> {
            try {
                executorProvider.getObject().fire(event.taskId(), true);
            } catch (Exception e) {
                log.error("Manual trigger executor.fire threw for task {}: {}",
                        event.taskId(), e.getMessage(), e);
            }
        }, inAMoment.toInstant());
    }

    // ---------- lifecycle ----------

    @Override
    public void destroy() {
        log.info("UserTaskScheduler shutting down — waiting up to {}s for in-flight tasks",
                SHUTDOWN_AWAIT_SECONDS);
        taskScheduler.shutdown();
    }

    /** Test hook: returns immutable snapshot of currently-scheduled task ids. */
    Set<Long> scheduledTaskIds() {
        return Set.copyOf(scheduledFutures.keySet());
    }

    /** Test hook: time the pool will wait at shutdown — checked in unit tests. */
    Duration shutdownAwaitDuration() {
        return Duration.ofSeconds(SHUTDOWN_AWAIT_SECONDS);
    }

    /**
     * Test hook: aggressive shutdown for unit tests so {@code @AfterEach} cleanup
     * doesn't block on the production 30s grace window. Production code uses
     * {@link #destroy()}.
     */
    void shutdownNowForTests() {
        taskScheduler.getScheduledExecutor().shutdownNow();
    }
}
