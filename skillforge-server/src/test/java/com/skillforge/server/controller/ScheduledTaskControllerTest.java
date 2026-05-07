package com.skillforge.server.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.skillforge.server.entity.ScheduledTaskEntity;
import com.skillforge.server.entity.ScheduledTaskRunEntity;
import com.skillforge.server.service.ScheduledTaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REST shape + ownership tests for the P12 schedule endpoints. Uses MockMvc
 * standalone setup (no full Spring context) — same pattern as
 * SessionSpansAuthIT in this project.
 */
@EnableWebMvc
@DisplayName("ScheduledTaskController")
class ScheduledTaskControllerTest {

    private ScheduledTaskService scheduledTaskService;
    private ObjectMapper objectMapper;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        scheduledTaskService = mock(ScheduledTaskService.class);
        // Match the Spring-managed ObjectMapper config: JavaTimeModule + write Instants as
        // ISO-8601 strings (not numeric timestamps) — see java.md footgun #1.
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        ScheduledTaskController controller = new ScheduledTaskController(
                scheduledTaskService, objectMapper);

        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    // ----- create -----

    @Test
    @DisplayName("POST /api/schedules returns 201 with persisted shape")
    void create_returns201() throws Exception {
        ScheduledTaskEntity created = newTask(1L, 7L);
        when(scheduledTaskService.create(eq(7L), any())).thenReturn(created);

        String body = """
                {
                  "name": "daily-summary",
                  "agentId": 42,
                  "promptTemplate": "summarize today",
                  "cronExpr": "0 0 9 * * *"
                }
                """;

        mvc.perform(post("/api/schedules")
                        .param("userId", "7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.creatorUserId").value(7))
                .andExpect(jsonPath("$.cronExpr").value("0 0 9 * * *"));
    }

    @Test
    @DisplayName("POST /api/schedules returns 400 when service rejects (invalid cron)")
    void create_invalidInput_returns400() throws Exception {
        when(scheduledTaskService.create(eq(7L), any()))
                .thenThrow(new IllegalArgumentException("invalid cron expression: bogus"));

        mvc.perform(post("/api/schedules")
                        .param("userId", "7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"agentId\":1,\"promptTemplate\":\"p\",\"cronExpr\":\"bogus\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    // ----- list -----

    @Test
    @DisplayName("GET /api/schedules returns array for current user")
    void list_returnsArray() throws Exception {
        when(scheduledTaskService.listForUser(7L))
                .thenReturn(List.of(newTask(1L, 7L), newTask(2L, 7L)));

        mvc.perform(get("/api/schedules").param("userId", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[1].id").value(2));
    }

    // ----- get -----

    @Test
    @DisplayName("GET /api/schedules/{id} returns task when owner matches")
    void get_owner_returns200() throws Exception {
        when(scheduledTaskService.get(1L, 7L)).thenReturn(newTask(1L, 7L));
        mvc.perform(get("/api/schedules/{id}", 1L).param("userId", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    @DisplayName("GET /api/schedules/{id} returns 403 for non-owner")
    void get_nonOwner_returns403() throws Exception {
        when(scheduledTaskService.get(1L, 99L))
                .thenThrow(new com.skillforge.server.exception.ScheduledTaskAccessDeniedException(
                        "user 99 cannot access scheduled task 1"));

        mvc.perform(get("/api/schedules/{id}", 1L).param("userId", "99"))
                .andExpect(status().isForbidden());
    }

    // ----- update -----

    @Test
    @DisplayName("PUT /api/schedules/{id} cron→one-shot patch returns updated entity")
    void update_cronToOneShot_returns200() throws Exception {
        ScheduledTaskEntity updated = newTask(1L, 7L);
        updated.setCronExpr(null);
        Instant when = Instant.parse("2026-12-25T09:00:00Z");
        updated.setOneShotAt(when);
        when(scheduledTaskService.update(eq(1L), eq(7L), any())).thenReturn(updated);

        String body = """
                {
                  "cronExpr": null,
                  "oneShotAt": "2026-12-25T09:00:00Z"
                }
                """;

        mvc.perform(put("/api/schedules/{id}", 1L)
                        .param("userId", "7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cronExpr").doesNotExist())  // null serializes to absent for primitive Object
                .andExpect(jsonPath("$.oneShotAt").value("2026-12-25T09:00:00Z"));
    }

    @Test
    @DisplayName("PUT /api/schedules/{id} returns 403 for non-owner")
    void update_nonOwner_returns403() throws Exception {
        when(scheduledTaskService.update(eq(1L), eq(99L), any()))
                .thenThrow(new com.skillforge.server.exception.ScheduledTaskAccessDeniedException(
                        "user 99 cannot access scheduled task 1"));

        mvc.perform(put("/api/schedules/{id}", 1L)
                        .param("userId", "99")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\"}"))
                .andExpect(status().isForbidden());
    }

    // ----- delete -----

    @Test
    @DisplayName("DELETE /api/schedules/{id} returns 204")
    void delete_owner_returns204() throws Exception {
        mvc.perform(delete("/api/schedules/{id}", 1L).param("userId", "7"))
                .andExpect(status().isNoContent());
        verify(scheduledTaskService).delete(1L, 7L);
    }

    @Test
    @DisplayName("DELETE /api/schedules/{id} returns 403 for non-owner")
    void delete_nonOwner_returns403() throws Exception {
        doThrow(new com.skillforge.server.exception.ScheduledTaskAccessDeniedException("nope"))
                .when(scheduledTaskService).delete(1L, 99L);

        mvc.perform(delete("/api/schedules/{id}", 1L).param("userId", "99"))
                .andExpect(status().isForbidden());
    }

    // ----- trigger -----

    @Test
    @DisplayName("POST /api/schedules/{id}/trigger returns 202 (async)")
    void trigger_returns202() throws Exception {
        when(scheduledTaskService.triggerNow(1L, 7L)).thenReturn(newTask(1L, 7L));

        mvc.perform(post("/api/schedules/{id}/trigger", 1L).param("userId", "7"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.taskId").value(1))
                .andExpect(jsonPath("$.status").value("trigger_requested"));
    }

    // ----- runs -----

    @Test
    @DisplayName("GET /api/schedules/{id}/runs returns paginated history")
    void listRuns_returnsArray() throws Exception {
        ScheduledTaskRunEntity run = new ScheduledTaskRunEntity();
        run.setId(100L);
        run.setTaskId(1L);
        run.setTriggeredAt(Instant.parse("2026-05-07T00:00:00Z"));
        run.setStatus("success");
        when(scheduledTaskService.listRuns(eq(1L), eq(7L), eq(20), eq(0)))
                .thenReturn(List.of(run));

        mvc.perform(get("/api/schedules/{id}/runs", 1L)
                        .param("userId", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(100))
                .andExpect(jsonPath("$[0].status").value("success"));
    }

    // ----- helpers -----

    private static ScheduledTaskEntity newTask(Long id, Long creatorUserId) {
        ScheduledTaskEntity e = new ScheduledTaskEntity();
        e.setId(id);
        e.setCreatorUserId(creatorUserId);
        e.setName("daily-summary");
        e.setAgentId(42L);
        e.setCronExpr("0 0 9 * * *");
        e.setTimezone("Asia/Shanghai");
        e.setPromptTemplate("summarize today");
        e.setSessionMode("new");
        e.setEnabled(true);
        e.setStatus("idle");
        e.setConcurrencyPolicy("skip-if-running");
        return e;
    }
}
