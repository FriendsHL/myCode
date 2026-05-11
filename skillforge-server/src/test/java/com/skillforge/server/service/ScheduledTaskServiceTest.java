package com.skillforge.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.skillforge.server.dto.ScheduledTaskRequest;
import com.skillforge.server.entity.ScheduledTaskEntity;
import com.skillforge.server.entity.ScheduledTaskRunEntity;
import com.skillforge.server.exception.ScheduledTaskNotFoundException;
import com.skillforge.server.repository.ScheduledTaskRepository;
import com.skillforge.server.repository.ScheduledTaskRunRepository;
import com.skillforge.server.service.event.ScheduledTaskDeletedEvent;
import com.skillforge.server.service.event.ScheduledTaskTriggerRequestedEvent;
import com.skillforge.server.service.event.ScheduledTaskUpsertedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ScheduledTaskService")
class ScheduledTaskServiceTest {

    @Mock
    private ScheduledTaskRepository scheduledTaskRepository;
    @Mock
    private ScheduledTaskRunRepository scheduledTaskRunRepository;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private ScheduledTaskService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        service = new ScheduledTaskService(
                scheduledTaskRepository, scheduledTaskRunRepository, eventPublisher, objectMapper);
    }

    // ----- create -----

    @Test
    @DisplayName("create with cron persists entity, defaults timezone, publishes upserted event")
    void create_cron_succeeds() {
        when(scheduledTaskRepository.save(any(ScheduledTaskEntity.class)))
                .thenAnswer(inv -> {
                    ScheduledTaskEntity e = inv.getArgument(0);
                    e.setId(1L);
                    return e;
                });

        ScheduledTaskRequest req = new ScheduledTaskRequest();
        req.setName("daily-summary");
        req.setAgentId(42L);
        req.setPromptTemplate("summarize today");
        req.setCronExpr("0 0 9 * * *");

        ScheduledTaskEntity created = service.create(7L, req);

        assertThat(created.getId()).isEqualTo(1L);
        assertThat(created.getCreatorUserId()).isEqualTo(7L);
        assertThat(created.getCronExpr()).isEqualTo("0 0 9 * * *");
        assertThat(created.getOneShotAt()).isNull();
        assertThat(created.getTimezone()).isEqualTo("Asia/Shanghai");
        assertThat(created.getSessionMode()).isEqualTo("new");
        assertThat(created.isEnabled()).isTrue();

        ArgumentCaptor<Object> evt = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(evt.capture());
        assertThat(evt.getValue()).isInstanceOf(ScheduledTaskUpsertedEvent.class);
        assertThat(((ScheduledTaskUpsertedEvent) evt.getValue()).taskId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("create with one-shot persists entity")
    void create_oneShot_succeeds() {
        when(scheduledTaskRepository.save(any(ScheduledTaskEntity.class)))
                .thenAnswer(inv -> {
                    ScheduledTaskEntity e = inv.getArgument(0);
                    e.setId(2L);
                    return e;
                });

        Instant when = Instant.parse("2026-12-25T09:00:00Z");
        ScheduledTaskRequest req = new ScheduledTaskRequest();
        req.setName("xmas-greeting");
        req.setAgentId(42L);
        req.setPromptTemplate("greet me");
        req.setOneShotAt(when);

        ScheduledTaskEntity created = service.create(7L, req);

        assertThat(created.getOneShotAt()).isEqualTo(when);
        assertThat(created.getCronExpr()).isNull();
    }

    @Test
    @DisplayName("create rejects when both cron and one-shot are present")
    void create_bothTriggers_rejected() {
        ScheduledTaskRequest req = new ScheduledTaskRequest();
        req.setName("x");
        req.setAgentId(1L);
        req.setPromptTemplate("p");
        req.setCronExpr("0 0 9 * * *");
        req.setOneShotAt(Instant.parse("2026-12-25T09:00:00Z"));

        assertThatThrownBy(() -> service.create(1L, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mutually exclusive");

        verify(scheduledTaskRepository, never()).save(any());
    }

    @Test
    @DisplayName("create rejects when neither cron nor one-shot is present")
    void create_noTrigger_rejected() {
        ScheduledTaskRequest req = new ScheduledTaskRequest();
        req.setName("x");
        req.setAgentId(1L);
        req.setPromptTemplate("p");

        assertThatThrownBy(() -> service.create(1L, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one");
    }

    @Test
    @DisplayName("create rejects invalid timezone (INV-8)")
    void create_invalidTimezone_rejected() {
        ScheduledTaskRequest req = new ScheduledTaskRequest();
        req.setName("x");
        req.setAgentId(1L);
        req.setPromptTemplate("p");
        req.setCronExpr("0 0 9 * * *");
        req.setTimezone("Asia/NotARealCity");

        assertThatThrownBy(() -> service.create(1L, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid timezone");
    }

    @Test
    @DisplayName("create rejects invalid cron expression")
    void create_invalidCron_rejected() {
        ScheduledTaskRequest req = new ScheduledTaskRequest();
        req.setName("x");
        req.setAgentId(1L);
        req.setPromptTemplate("p");
        req.setCronExpr("not a cron");

        assertThatThrownBy(() -> service.create(1L, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid cron expression");
    }

    @Test
    @DisplayName("create rejects invalid sessionMode")
    void create_invalidSessionMode_rejected() {
        ScheduledTaskRequest req = new ScheduledTaskRequest();
        req.setName("x");
        req.setAgentId(1L);
        req.setPromptTemplate("p");
        req.setCronExpr("0 0 9 * * *");
        req.setSessionMode("bogus");

        assertThatThrownBy(() -> service.create(1L, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid sessionMode");
    }

    @Test
    @DisplayName("create with valid IANA timezone (UTC) succeeds")
    void create_validUtcTimezone_succeeds() {
        when(scheduledTaskRepository.save(any(ScheduledTaskEntity.class)))
                .thenAnswer(inv -> {
                    ScheduledTaskEntity e = inv.getArgument(0);
                    e.setId(99L);
                    return e;
                });

        ScheduledTaskRequest req = new ScheduledTaskRequest();
        req.setName("x");
        req.setAgentId(1L);
        req.setPromptTemplate("p");
        req.setCronExpr("0 0 9 * * *");
        req.setTimezone("UTC");

        ScheduledTaskEntity result = service.create(1L, req);
        // UTC normalizes to GMT in some JDKs — accept either, just no exception thrown.
        assertThat(result.getTimezone()).isIn("UTC", "GMT");
    }

    // ----- update -----

    @Test
    @DisplayName("update partial patch only touches set fields")
    void update_partialPatch_preservesUntouched() {
        ScheduledTaskEntity existing = baseExisting(10L, 7L);
        when(scheduledTaskRepository.findById(10L)).thenReturn(Optional.of(existing));
        when(scheduledTaskRepository.save(any(ScheduledTaskEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ScheduledTaskRequest patch = new ScheduledTaskRequest();
        patch.setName("renamed");
        // Bring along the existing trigger via the patch — applyTriggerFields is always called.
        patch.setCronExpr(existing.getCronExpr());

        ScheduledTaskEntity updated = service.update(10L, 7L, patch);

        assertThat(updated.getName()).isEqualTo("renamed");
        assertThat(updated.getPromptTemplate()).isEqualTo("p");
        assertThat(updated.getCronExpr()).isEqualTo(existing.getCronExpr());
        verify(eventPublisher).publishEvent(any(ScheduledTaskUpsertedEvent.class));
    }

    @Test
    @DisplayName("update cron→one-shot conversion clears cron and sets one-shot in one PUT (INV-3)")
    void update_cronToOneShot_clearsAndSets() {
        ScheduledTaskEntity existing = baseExisting(11L, 7L);
        when(scheduledTaskRepository.findById(11L)).thenReturn(Optional.of(existing));
        when(scheduledTaskRepository.save(any(ScheduledTaskEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        Instant target = Instant.parse("2026-12-25T09:00:00Z");
        ScheduledTaskRequest patch = new ScheduledTaskRequest();
        patch.setCronExpr(null);          // explicit clear
        patch.setOneShotAt(target);       // set new

        ScheduledTaskEntity updated = service.update(11L, 7L, patch);

        assertThat(updated.getCronExpr()).isNull();
        assertThat(updated.getOneShotAt()).isEqualTo(target);
    }

    @Test
    @DisplayName("update one-shot→cron conversion clears one-shot and sets cron in one PUT (INV-3)")
    void update_oneShotToCron_clearsAndSets() {
        ScheduledTaskEntity existing = baseExisting(12L, 7L);
        existing.setCronExpr(null);
        existing.setOneShotAt(Instant.parse("2026-12-25T09:00:00Z"));
        when(scheduledTaskRepository.findById(12L)).thenReturn(Optional.of(existing));
        when(scheduledTaskRepository.save(any(ScheduledTaskEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ScheduledTaskRequest patch = new ScheduledTaskRequest();
        patch.setOneShotAt(null);         // explicit clear
        patch.setCronExpr("0 0 9 * * *"); // set new

        ScheduledTaskEntity updated = service.update(12L, 7L, patch);

        assertThat(updated.getCronExpr()).isEqualTo("0 0 9 * * *");
        assertThat(updated.getOneShotAt()).isNull();
    }

    @Test
    @DisplayName("update cross-user → AccessDeniedException")
    void update_crossUser_forbidden() {
        ScheduledTaskEntity existing = baseExisting(13L, 7L); // owner=7
        when(scheduledTaskRepository.findById(13L)).thenReturn(Optional.of(existing));

        ScheduledTaskRequest patch = new ScheduledTaskRequest();
        patch.setName("hacked");

        assertThatThrownBy(() -> service.update(13L, 99L, patch))
                .isInstanceOf(com.skillforge.server.exception.ScheduledTaskAccessDeniedException.class)
                .hasMessageContaining("99");
        verify(scheduledTaskRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("update missing task → NotFound")
    void update_missing_throwsNotFound() {
        when(scheduledTaskRepository.findById(999L)).thenReturn(Optional.empty());
        ScheduledTaskRequest patch = new ScheduledTaskRequest();
        patch.setName("x");

        assertThatThrownBy(() -> service.update(999L, 7L, patch))
                .isInstanceOf(ScheduledTaskNotFoundException.class);
    }

    // ----- delete -----

    @Test
    @DisplayName("delete owner-checked, publishes deleted event")
    void delete_owner_publishesEvent() {
        ScheduledTaskEntity existing = baseExisting(14L, 7L);
        when(scheduledTaskRepository.findById(14L)).thenReturn(Optional.of(existing));

        service.delete(14L, 7L);

        verify(scheduledTaskRepository).delete(existing);
        ArgumentCaptor<ScheduledTaskDeletedEvent> evt = ArgumentCaptor.forClass(ScheduledTaskDeletedEvent.class);
        verify(eventPublisher).publishEvent(evt.capture());
        assertThat(evt.getValue().taskId()).isEqualTo(14L);
        assertThat(evt.getValue().creatorUserId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("delete cross-user → AccessDeniedException")
    void delete_crossUser_forbidden() {
        ScheduledTaskEntity existing = baseExisting(15L, 7L);
        when(scheduledTaskRepository.findById(15L)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.delete(15L, 99L))
                .isInstanceOf(com.skillforge.server.exception.ScheduledTaskAccessDeniedException.class);
        verify(scheduledTaskRepository, never()).delete(any(ScheduledTaskEntity.class));
        verify(eventPublisher, never()).publishEvent(any());
    }

    // ----- get / list -----

    @Test
    @DisplayName("get cross-user → AccessDeniedException")
    void get_crossUser_forbidden() {
        ScheduledTaskEntity existing = baseExisting(16L, 7L);
        when(scheduledTaskRepository.findById(16L)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.get(16L, 99L))
                .isInstanceOf(com.skillforge.server.exception.ScheduledTaskAccessDeniedException.class);
    }

    @Test
    @DisplayName("listForUser delegates to repository by creator id")
    void listForUser_callsRepository() {
        when(scheduledTaskRepository.findByCreatorUserIdOrderByIdDesc(7L))
                .thenReturn(java.util.List.of(baseExisting(1L, 7L), baseExisting(2L, 7L)));

        var rows = service.listForUser(7L);

        // 2 user rows + 0 system rows (default Mockito returns empty list for system query)
        assertThat(rows).hasSize(2);
    }

    @Test
    @DisplayName("E2E-2: listForUser merges user tasks + SYSTEM (creatorUserId=0) tasks")
    void listForUser_mergesSystemTasks() {
        ScheduledTaskEntity userTask = baseExisting(10L, 7L);
        ScheduledTaskEntity systemTask = baseExisting(99L, 0L);
        systemTask.setName("memory-curator-nightly");
        when(scheduledTaskRepository.findByCreatorUserIdOrderByIdDesc(7L))
                .thenReturn(java.util.List.of(userTask));
        when(scheduledTaskRepository.findByCreatorUserIdOrderByIdDesc(0L))
                .thenReturn(java.util.List.of(systemTask));

        var rows = service.listForUser(7L);

        assertThat(rows).hasSize(2);
        // SYSTEM tasks render first
        assertThat(rows.get(0).getId()).isEqualTo(99L);
        assertThat(rows.get(0).getCreatorUserId()).isZero();
        assertThat(rows.get(1).getId()).isEqualTo(10L);
        assertThat(rows.get(1).getCreatorUserId()).isEqualTo(7L);
    }

    // ----- SYSTEM task partial-permission -----

    @Test
    @DisplayName("SYSTEM task: any user may toggle enabled (no ownership check)")
    void updateSystemTask_enabledOnly_allowedForAnyUser() {
        ScheduledTaskEntity systemTask = baseExisting(3L, 0L);
        systemTask.setEnabled(false);
        when(scheduledTaskRepository.findById(3L)).thenReturn(Optional.of(systemTask));
        when(scheduledTaskRepository.save(any(ScheduledTaskEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        ScheduledTaskRequest req = new ScheduledTaskRequest();
        req.setEnabled(true);

        // user 1 is NOT the SYSTEM owner (0) but should be allowed for enabled-only update
        ScheduledTaskEntity result = service.update(3L, 1L, req);

        assertThat(result.isEnabled()).isTrue();
        verify(scheduledTaskRepository).save(systemTask);
        ArgumentCaptor<ScheduledTaskUpsertedEvent> evt = ArgumentCaptor.forClass(ScheduledTaskUpsertedEvent.class);
        verify(eventPublisher).publishEvent(evt.capture());
        assertThat(evt.getValue().taskId()).isEqualTo(3L);
    }

    @Test
    @DisplayName("SYSTEM task: non-enabled field edit denied with AccessDeniedException")
    void updateSystemTask_nonEnabledField_deniedForAnyUser() {
        ScheduledTaskEntity systemTask = baseExisting(3L, 0L);
        when(scheduledTaskRepository.findById(3L)).thenReturn(Optional.of(systemTask));

        ScheduledTaskRequest req = new ScheduledTaskRequest();
        req.setCronExpr("0 0 5 * * *"); // attempting to rewrite the cron

        assertThatThrownBy(() -> service.update(3L, 1L, req))
                .isInstanceOf(com.skillforge.server.exception.ScheduledTaskAccessDeniedException.class)
                .hasMessageContaining("only allows enabled field editing");
        verify(scheduledTaskRepository, never()).save(any(ScheduledTaskEntity.class));
    }

    @Test
    @DisplayName("SYSTEM task: delete always denied (even by SYSTEM user itself)")
    void deleteSystemTask_alwaysDenied() {
        ScheduledTaskEntity systemTask = baseExisting(3L, 0L);
        when(scheduledTaskRepository.findById(3L)).thenReturn(Optional.of(systemTask));

        assertThatThrownBy(() -> service.delete(3L, 1L))
                .isInstanceOf(com.skillforge.server.exception.ScheduledTaskAccessDeniedException.class)
                .hasMessageContaining("cannot be deleted via API");
        verify(scheduledTaskRepository, never()).delete(any(ScheduledTaskEntity.class));
        verify(eventPublisher, never()).publishEvent(any(ScheduledTaskDeletedEvent.class));
    }

    @Test
    @DisplayName("SYSTEM task: any user may manually trigger (skip ownership check)")
    void triggerSystemTask_allowedByAnyUser() {
        ScheduledTaskEntity systemTask = baseExisting(3L, 0L);
        when(scheduledTaskRepository.findById(3L)).thenReturn(Optional.of(systemTask));

        ScheduledTaskEntity result = service.triggerNow(3L, 1L);

        assertThat(result.getId()).isEqualTo(3L);
        ArgumentCaptor<ScheduledTaskTriggerRequestedEvent> evt =
                ArgumentCaptor.forClass(ScheduledTaskTriggerRequestedEvent.class);
        verify(eventPublisher).publishEvent(evt.capture());
        assertThat(evt.getValue().taskId()).isEqualTo(3L);
    }

    @Test
    @DisplayName("regression: regular task cross-user update still requires ownership (AccessDenied)")
    void updateRegularTask_crossUser_stillRequiresOwnership() {
        // user 1's task, user 2 trying to edit
        ScheduledTaskEntity userTask = baseExisting(10L, 1L);
        when(scheduledTaskRepository.findById(10L)).thenReturn(Optional.of(userTask));

        ScheduledTaskRequest req = new ScheduledTaskRequest();
        req.setEnabled(true);

        assertThatThrownBy(() -> service.update(10L, 2L, req))
                .isInstanceOf(com.skillforge.server.exception.ScheduledTaskAccessDeniedException.class);
        verify(scheduledTaskRepository, never()).save(any(ScheduledTaskEntity.class));
    }

    @Test
    @DisplayName("E2E-2: SYSTEM user (userId=0) queries don't double-count SYSTEM rows")
    void listForUser_systemUser_noDuplicate() {
        ScheduledTaskEntity systemTask = baseExisting(99L, 0L);
        when(scheduledTaskRepository.findByCreatorUserIdOrderByIdDesc(0L))
                .thenReturn(java.util.List.of(systemTask));

        var rows = service.listForUser(0L);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getId()).isEqualTo(99L);
        // Verify only one repository call (no SYSTEM merge for SYSTEM user itself)
        verify(scheduledTaskRepository, times(1))
                .findByCreatorUserIdOrderByIdDesc(0L);
    }

    // ----- triggerNow -----

    @Test
    @DisplayName("triggerNow publishes a trigger-requested event (bypass enabled flag, INV-10)")
    void triggerNow_publishesEvent() {
        ScheduledTaskEntity existing = baseExisting(17L, 7L);
        existing.setEnabled(false); // INV-10: manual trigger ignores enabled
        when(scheduledTaskRepository.findById(17L)).thenReturn(Optional.of(existing));

        ScheduledTaskEntity result = service.triggerNow(17L, 7L);

        assertThat(result.getId()).isEqualTo(17L);
        verify(eventPublisher).publishEvent(any(ScheduledTaskTriggerRequestedEvent.class));
    }

    // ----- markRun* (BE-2 hooks) -----

    @Test
    @DisplayName("markRunStart writes a running row tagged with manual flag")
    void markRunStart_writesRow() {
        ScheduledTaskEntity existing = baseExisting(20L, 7L);
        when(scheduledTaskRepository.findById(20L)).thenReturn(Optional.of(existing));
        when(scheduledTaskRunRepository.save(any(ScheduledTaskRunEntity.class)))
                .thenAnswer(inv -> {
                    ScheduledTaskRunEntity r = inv.getArgument(0);
                    r.setId(100L);
                    return r;
                });

        ScheduledTaskRunEntity run = service.markRunStart(20L, true);

        assertThat(run.getId()).isEqualTo(100L);
        assertThat(run.getStatus()).isEqualTo("running");
        assertThat(run.isManual()).isTrue();
        assertThat(run.getTriggeredAt()).isNotNull();
    }

    @Test
    @DisplayName("markRunFinish updates terminal status, error message, finishedAt")
    void markRunFinish_updatesRow() {
        ScheduledTaskRunEntity row = new ScheduledTaskRunEntity();
        row.setId(100L);
        row.setStatus("running");
        when(scheduledTaskRunRepository.findById(100L)).thenReturn(Optional.of(row));
        when(scheduledTaskRunRepository.save(any(ScheduledTaskRunEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        Instant t = Instant.parse("2026-05-07T01:00:00Z");
        ScheduledTaskRunEntity updated = service.markRunFinish(
                100L, "failure", "boom", t, "sess-abc");

        assertThat(updated.getStatus()).isEqualTo("failure");
        assertThat(updated.getErrorMessage()).isEqualTo("boom");
        assertThat(updated.getFinishedAt()).isEqualTo(t);
        assertThat(updated.getTriggeredSessionId()).isEqualTo("sess-abc");
    }

    @Test
    @DisplayName("markRunSkipped writes a skipped row in one shot (INV-4)")
    void markRunSkipped_writesRow() {
        when(scheduledTaskRunRepository.save(any(ScheduledTaskRunEntity.class)))
                .thenAnswer(inv -> {
                    ScheduledTaskRunEntity r = inv.getArgument(0);
                    r.setId(200L);
                    return r;
                });

        ScheduledTaskRunEntity skipped = service.markRunSkipped(20L, false);

        assertThat(skipped.getStatus()).isEqualTo("skipped");
        assertThat(skipped.getFinishedAt()).isNotNull();
    }

    // ----- findAllEnabled -----

    @Test
    @DisplayName("findAllEnabled delegates to repository (BE-2 startup recovery)")
    void findAllEnabled_delegates() {
        when(scheduledTaskRepository.findByEnabledTrue())
                .thenReturn(java.util.List.of(baseExisting(1L, 7L)));
        assertThat(service.findAllEnabled()).hasSize(1);
        verify(scheduledTaskRepository, times(1)).findByEnabledTrue();
    }

    // ----- helpers -----

    private static ScheduledTaskEntity baseExisting(Long id, Long creatorUserId) {
        ScheduledTaskEntity e = new ScheduledTaskEntity();
        e.setId(id);
        e.setCreatorUserId(creatorUserId);
        e.setName("base");
        e.setAgentId(42L);
        e.setCronExpr("0 0 9 * * *");
        e.setOneShotAt(null);
        e.setTimezone("Asia/Shanghai");
        e.setPromptTemplate("p");
        e.setSessionMode("new");
        e.setEnabled(true);
        e.setConcurrencyPolicy("skip-if-running");
        e.setStatus("idle");
        return e;
    }
}
