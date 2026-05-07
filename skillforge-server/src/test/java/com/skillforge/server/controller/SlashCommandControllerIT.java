package com.skillforge.server.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.skillforge.server.dto.CommandResult;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.service.SessionService;
import com.skillforge.server.service.command.ExecutionContext;
import com.skillforge.server.service.command.SlashCommandService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * IT-level coverage for {@code POST /api/commands/execute} — exercises the
 * full Spring MVC pipeline: Jackson deserialization → bean validation
 * ({@code @Valid}/{@code @NotBlank}/{@code @NotNull}) → controller logic.
 *
 * <p>Why this exists: r1 review B1 found that the controller had been silently
 * 400-ing every real FE request because the JSON shape ({@code command, args})
 * did not match the record component name ({@code commandLine}). Pure unit tests
 * constructed the {@code ExecuteRequest} directly, bypassing Jackson, so the
 * mismatch was invisible. This IT plugs that gap by sending raw JSON through
 * MockMvc.
 */
@DisplayName("SlashCommandControllerIT — Jackson + @Valid integration (r2 B1 + W2)")
class SlashCommandControllerIT {

    private SlashCommandService slashCommandService;
    private SessionService sessionService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        slashCommandService = mock(SlashCommandService.class);
        sessionService = mock(SessionService.class);

        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

        SlashCommandController controller = new SlashCommandController(
                slashCommandService, sessionService);

        // LocalValidatorFactoryBean enables @Valid + @NotBlank/@NotNull semantics
        // in the standalone MockMvc setup (the default standalone setup has no
        // validator wired in).
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .setValidator(validator)
                .build();
    }

    // -------------------- B1 — wire-shape compatibility --------------------

    @Test
    @DisplayName("B1: FE shape {sessionId, command, args, userId} is accepted (no 400)")
    void b1_feShape_isAccepted() throws Exception {
        // Owner check passes
        SessionEntity s = ownedSession("sess-1", 7L);
        when(sessionService.getSession("sess-1")).thenReturn(s);
        when(slashCommandService.execute(eq(7L), eq("sess-1"), eq("/model gpt-4o"), any()))
                .thenReturn(CommandResult.toastWithModel("已切换", "gpt-4o"));

        String body = """
                {
                  "sessionId": "sess-1",
                  "command": "/model",
                  "args": "gpt-4o",
                  "userId": 7
                }
                """;

        mvc.perform(post("/api/commands/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.modelId").value("gpt-4o"));

        // The service receives the reconstructed commandLine:
        ArgumentCaptor<String> cmdLine = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<ExecutionContext> ctx = ArgumentCaptor.forClass(ExecutionContext.class);
        verify(slashCommandService).execute(eq(7L), eq("sess-1"), cmdLine.capture(), ctx.capture());
        assertThat(cmdLine.getValue()).isEqualTo("/model gpt-4o");
        assertThat(ctx.getValue().isWeb()).isTrue();
    }

    @Test
    @DisplayName("B1: command-only (no args field at all) reaches service as just '/help'")
    void b1_commandOnly_noArgsField_reachesServiceUnchanged() throws Exception {
        SessionEntity s = ownedSession("sess-1", 7L);
        when(sessionService.getSession("sess-1")).thenReturn(s);
        when(slashCommandService.execute(eq(7L), eq("sess-1"), eq("/help"), any()))
                .thenReturn(CommandResult.modal("ok", "# Help"));

        // 'args' field absent — Jackson maps the record component to null.
        String body = """
                {
                  "sessionId": "sess-1",
                  "command": "/help",
                  "userId": 7
                }
                """;

        mvc.perform(post("/api/commands/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.markdownBody").value("# Help"));

        verify(slashCommandService).execute(eq(7L), eq("sess-1"), eq("/help"), any());
    }

    @Test
    @DisplayName("B1: empty-string args is treated identically to missing args")
    void b1_emptyArgs_sameAsMissing() throws Exception {
        SessionEntity s = ownedSession("sess-1", 7L);
        when(sessionService.getSession("sess-1")).thenReturn(s);
        when(slashCommandService.execute(eq(7L), eq("sess-1"), eq("/help"), any()))
                .thenReturn(CommandResult.modal("ok", "# Help"));

        String body = """
                {
                  "sessionId": "sess-1",
                  "command": "/help",
                  "args": "",
                  "userId": 7
                }
                """;

        mvc.perform(post("/api/commands/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        verify(slashCommandService).execute(eq(7L), eq("sess-1"), eq("/help"), any());
    }

    // -------------------- W2 — @Valid bean validation --------------------

    @Test
    @DisplayName("W2: missing required field 'command' is rejected with 400 (no service call)")
    void w2_missingCommand_returns400() throws Exception {
        String body = """
                {
                  "sessionId": "sess-1",
                  "userId": 7
                }
                """;
        mvc.perform(post("/api/commands/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
        verify(slashCommandService, never())
                .execute(anyLong(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("W2: blank 'command' is rejected by @NotBlank")
    void w2_blankCommand_returns400() throws Exception {
        String body = """
                {
                  "sessionId": "sess-1",
                  "command": "   ",
                  "args": "",
                  "userId": 7
                }
                """;
        mvc.perform(post("/api/commands/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
        verify(slashCommandService, never())
                .execute(anyLong(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("W2: missing userId is rejected by @NotNull")
    void w2_missingUserId_returns400() throws Exception {
        String body = """
                {
                  "sessionId": "sess-1",
                  "command": "/help",
                  "args": ""
                }
                """;
        mvc.perform(post("/api/commands/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
        verify(slashCommandService, never())
                .execute(anyLong(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("W2: blank sessionId is rejected by @NotBlank")
    void w2_blankSessionId_returns400() throws Exception {
        String body = """
                {
                  "sessionId": "",
                  "command": "/help",
                  "args": "",
                  "userId": 7
                }
                """;
        mvc.perform(post("/api/commands/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    // -------------------- Ownership (re-verified through the full pipeline) --------------------

    @Test
    @DisplayName("Ownership: cross-user request returns 403, service never called")
    void ownership_crossUserReturns403() throws Exception {
        SessionEntity s = ownedSession("sess-1", 99L);
        when(sessionService.getSession("sess-1")).thenReturn(s);

        String body = """
                {
                  "sessionId": "sess-1",
                  "command": "/help",
                  "args": "",
                  "userId": 7
                }
                """;
        mvc.perform(post("/api/commands/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false));
        verify(slashCommandService, never())
                .execute(anyLong(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("Session not found: 404")
    void sessionNotFound_returns404() throws Exception {
        when(sessionService.getSession("sess-x"))
                .thenThrow(new RuntimeException("Session not found"));

        String body = """
                {
                  "sessionId": "sess-x",
                  "command": "/help",
                  "args": "",
                  "userId": 7
                }
                """;
        mvc.perform(post("/api/commands/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false));
    }

    private static SessionEntity ownedSession(String id, Long userId) {
        SessionEntity s = new SessionEntity();
        s.setId(id);
        s.setUserId(userId);
        return s;
    }
}
