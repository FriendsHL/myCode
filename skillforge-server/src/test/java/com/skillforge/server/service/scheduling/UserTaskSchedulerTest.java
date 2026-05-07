package com.skillforge.server.service.scheduling;

import com.skillforge.server.entity.ScheduledTaskEntity;
import com.skillforge.server.repository.ScheduledTaskRepository;
import com.skillforge.server.service.event.ScheduledTaskDeletedEvent;
import com.skillforge.server.service.event.ScheduledTaskTriggerRequestedEvent;
import com.skillforge.server.service.event.ScheduledTaskUpsertedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link UserTaskScheduler}. Uses real
 * {@link org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler} so we
 * exercise actual cron / one-shot triggers, but binds them to a far-future fire
 * time to keep tests fast and deterministic — we assert state transitions on
 * register / unschedule, not actual fire timing.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("UserTaskScheduler")
class UserTaskSchedulerTest {

    @Mock
    private ScheduledTaskRepository repository;

    private UserTaskScheduler scheduler;
    private ScheduledTaskExecutor executor;
    private AtomicReference<UserTaskScheduler> schedulerRef;

    private UserTaskScheduler newSchedulerWithExecutor() {
        executor = mock(ScheduledTaskExecutor.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ScheduledTaskExecutor> executorProvider = mock(ObjectProvider.class);
        when(executorProvider.getObject()).thenReturn(executor);
        UserTaskScheduler s = new UserTaskScheduler(repository, executorProvider);
        return s;
    }

    @AfterEach
    void tearDown() {
        if (scheduler != null) {
            // Aggressive shutdown so the 30s production grace window doesn't slow tests.
            scheduler.shutdownNowForTests();
        }
    }

    private ScheduledTaskEntity newCronTask(long id, String cron) {
        ScheduledTaskEntity t = new ScheduledTaskEntity();
        t.setId(id);
        t.setName("t" + id);
        t.setCreatorUserId(7L);
        t.setAgentId(42L);
        t.setCronExpr(cron);
        t.setTimezone("Asia/Shanghai");
        t.setPromptTemplate("p");
        t.setSessionMode("new");
        t.setEnabled(true);
        t.setStatus("idle");
        t.setConcurrencyPolicy("skip-if-running");
        return t;
    }

    private ScheduledTaskEntity newOneShotTask(long id, Instant when) {
        ScheduledTaskEntity t = newCronTask(id, null);
        t.setCronExpr(null);
        t.setOneShotAt(when);
        return t;
    }

    @Test
    @DisplayName("register installs a cron future and remembers the task id")
    void register_cron_installsFuture() {
        scheduler = newSchedulerWithExecutor();
        ScheduledTaskEntity task = newCronTask(1L, "0 0 9 * * *");
        when(repository.findById(1L)).thenReturn(Optional.of(task));
        when(repository.save(any(ScheduledTaskEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        scheduler.register(1L);

        assertThat(scheduler.scheduledTaskIds()).containsExactly(1L);
        // next_fire_at must be populated for FE display.
        assertThat(task.getNextFireAt()).isNotNull();
    }

    @Test
    @DisplayName("register skips disabled tasks and unschedules them if previously scheduled")
    void register_disabled_doesNotInstall() {
        scheduler = newSchedulerWithExecutor();
        ScheduledTaskEntity enabled = newCronTask(1L, "0 0 9 * * *");
        when(repository.findById(1L)).thenReturn(Optional.of(enabled));
        when(repository.save(any(ScheduledTaskEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        scheduler.register(1L);
        assertThat(scheduler.scheduledTaskIds()).contains(1L);

        // Toggle off and re-register.
        ScheduledTaskEntity disabled = newCronTask(1L, "0 0 9 * * *");
        disabled.setEnabled(false);
        when(repository.findById(1L)).thenReturn(Optional.of(disabled));
        scheduler.register(1L);

        assertThat(scheduler.scheduledTaskIds()).doesNotContain(1L);
    }

    @Test
    @DisplayName("unschedule cancels future and removes id from tracking")
    void unschedule_removesFuture() {
        scheduler = newSchedulerWithExecutor();
        ScheduledTaskEntity task = newCronTask(2L, "0 0 9 * * *");
        when(repository.findById(2L)).thenReturn(Optional.of(task));
        when(repository.save(any(ScheduledTaskEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        scheduler.register(2L);
        assertThat(scheduler.scheduledTaskIds()).contains(2L);

        scheduler.unschedule(2L);

        assertThat(scheduler.scheduledTaskIds()).doesNotContain(2L);
    }

    @Test
    @DisplayName("reschedule replaces the prior future for the same task id")
    void reschedule_replacesFuture() {
        scheduler = newSchedulerWithExecutor();
        ScheduledTaskEntity task = newCronTask(3L, "0 0 9 * * *");
        when(repository.findById(3L)).thenReturn(Optional.of(task));
        when(repository.save(any(ScheduledTaskEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        scheduler.register(3L);
        assertThat(scheduler.scheduledTaskIds()).contains(3L);

        // Reschedule with new cron — should still be present (new future replacing old).
        task.setCronExpr("0 30 10 * * *");
        scheduler.reschedule(3L);

        assertThat(scheduler.scheduledTaskIds()).contains(3L);
    }

    @Test
    @DisplayName("tryMarkRunning is idempotent: second call returns false (skip-if-running)")
    void tryMarkRunning_skipIfRunning() {
        scheduler = newSchedulerWithExecutor();
        assertThat(scheduler.tryMarkRunning(7L)).isTrue();
        assertThat(scheduler.tryMarkRunning(7L)).isFalse();
        assertThat(scheduler.isRunning(7L)).isTrue();
        scheduler.clearRunning(7L);
        assertThat(scheduler.isRunning(7L)).isFalse();
        assertThat(scheduler.tryMarkRunning(7L)).isTrue();
    }

    @Test
    @DisplayName("event listener: ScheduledTaskUpsertedEvent triggers reschedule")
    void onUpserted_reschedules() {
        scheduler = newSchedulerWithExecutor();
        ScheduledTaskEntity task = newCronTask(4L, "0 0 9 * * *");
        when(repository.findById(4L)).thenReturn(Optional.of(task));
        when(repository.save(any(ScheduledTaskEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        scheduler.onUpserted(new ScheduledTaskUpsertedEvent(4L));

        assertThat(scheduler.scheduledTaskIds()).contains(4L);
        verify(repository, atLeastOnce()).findById(4L);
    }

    @Test
    @DisplayName("event listener: ScheduledTaskDeletedEvent unschedules and clears running marker")
    void onDeleted_clearsState() {
        scheduler = newSchedulerWithExecutor();
        ScheduledTaskEntity task = newCronTask(5L, "0 0 9 * * *");
        when(repository.findById(5L)).thenReturn(Optional.of(task));
        when(repository.save(any(ScheduledTaskEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        scheduler.register(5L);
        scheduler.tryMarkRunning(5L);

        scheduler.onDeleted(new ScheduledTaskDeletedEvent(5L, 99L));

        assertThat(scheduler.scheduledTaskIds()).doesNotContain(5L);
        assertThat(scheduler.isRunning(5L)).isFalse();
    }

    @Test
    @DisplayName("event listener: ScheduledTaskTriggerRequestedEvent fires manual=true via executor")
    void onTriggerRequested_firesManual() throws Exception {
        scheduler = newSchedulerWithExecutor();
        scheduler.onTriggerRequested(new ScheduledTaskTriggerRequestedEvent(6L));
        // Pool fires after a 10ms delay — wait briefly then verify.
        Thread.sleep(120);
        verify(executor).fire(6L, true);
    }

    @Test
    @DisplayName("startup recovery: enabled tasks are re-registered on ApplicationReadyEvent")
    void onApplicationReady_registersEnabled() {
        scheduler = newSchedulerWithExecutor();
        ScheduledTaskEntity a = newCronTask(8L, "0 0 9 * * *");
        ScheduledTaskEntity b = newCronTask(9L, "0 0 10 * * *");
        when(repository.findByEnabledTrue()).thenReturn(List.of(a, b));
        when(repository.save(any(ScheduledTaskEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        scheduler.onApplicationReady(null);

        assertThat(scheduler.scheduledTaskIds()).contains(8L, 9L);
    }

    @Test
    @DisplayName("r2 W1: one-shot in the past delegates to executor.handleOneShotMissed (not silently dropped)")
    void register_oneShot_inPast_delegatesToMissedHandler() {
        scheduler = newSchedulerWithExecutor();
        ScheduledTaskEntity task = newOneShotTask(10L, Instant.now().minusSeconds(60));
        when(repository.findById(10L)).thenReturn(Optional.of(task));

        scheduler.register(10L);

        assertThat(scheduler.scheduledTaskIds()).doesNotContain(10L);
        verify(executor).handleOneShotMissed(task);
    }

    @Test
    @DisplayName("shutdown await is configured to 30s (INV-7 graceful waiting)")
    void shutdown_awaitConfigured() {
        scheduler = newSchedulerWithExecutor();
        assertThat(scheduler.shutdownAwaitDuration()).isEqualTo(Duration.ofSeconds(30));
    }
}
