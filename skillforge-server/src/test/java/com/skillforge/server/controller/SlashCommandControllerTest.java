package com.skillforge.server.controller;

import com.skillforge.server.dto.CommandResult;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.service.SessionService;
import com.skillforge.server.service.command.ExecutionContext;
import com.skillforge.server.service.command.SlashCommandService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SlashCommandController — REST + INV-10 ownership + r2 B1 wire-shape")
class SlashCommandControllerTest {

    @Mock private SlashCommandService slashCommandService;
    @Mock private SessionService sessionService;

    private SlashCommandController controller;

    @BeforeEach
    void setUp() {
        controller = new SlashCommandController(slashCommandService, sessionService);
    }

    @Test
    @DisplayName("happy path: command + args reconstruct into commandLine for service")
    void happyPath_reconstructsCommandLine() {
        SessionEntity s = new SessionEntity();
        s.setId("sess-1");
        s.setUserId(7L);
        when(sessionService.getSession("sess-1")).thenReturn(s);
        when(slashCommandService.execute(eq(7L), eq("sess-1"), eq("/model gpt-4o"), any()))
                .thenReturn(CommandResult.toastWithModel("ok", "gpt-4o"));

        ResponseEntity<CommandResult> resp = controller.execute(
                new SlashCommandController.ExecuteRequest("sess-1", "/model", "gpt-4o", 7L));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().success()).isTrue();

        ArgumentCaptor<ExecutionContext> ctxCap = ArgumentCaptor.forClass(ExecutionContext.class);
        verify(slashCommandService).execute(eq(7L), eq("sess-1"), eq("/model gpt-4o"), ctxCap.capture());
        assertThat(ctxCap.getValue().isWeb()).isTrue();
    }

    @Test
    @DisplayName("empty args: only the command (no trailing space) is sent to service")
    void emptyArgs_passesCommandOnly() {
        SessionEntity s = new SessionEntity();
        s.setId("sess-1");
        s.setUserId(7L);
        when(sessionService.getSession("sess-1")).thenReturn(s);
        when(slashCommandService.execute(eq(7L), eq("sess-1"), eq("/help"), any()))
                .thenReturn(CommandResult.modal("ok", "# help"));

        controller.execute(new SlashCommandController.ExecuteRequest(
                "sess-1", "/help", "", 7L));

        verify(slashCommandService).execute(eq(7L), eq("sess-1"), eq("/help"), any());
    }

    @Test
    @DisplayName("null args: command-only call, no NPE")
    void nullArgs_isHandled() {
        SessionEntity s = new SessionEntity();
        s.setId("sess-1");
        s.setUserId(7L);
        when(sessionService.getSession("sess-1")).thenReturn(s);
        when(slashCommandService.execute(eq(7L), eq("sess-1"), eq("/new"), any()))
                .thenReturn(CommandResult.redirect("sess-2", "ok"));

        ResponseEntity<CommandResult> resp = controller.execute(
                new SlashCommandController.ExecuteRequest("sess-1", "/new", null, 7L));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(slashCommandService).execute(eq(7L), eq("sess-1"), eq("/new"), any());
    }

    @Test
    @DisplayName("INV-10: cross-user request returns 403, never invokes service")
    void inv10_crossUserForbidden() {
        SessionEntity s = new SessionEntity();
        s.setId("sess-1");
        s.setUserId(99L); // owned by user 99
        when(sessionService.getSession("sess-1")).thenReturn(s);

        ResponseEntity<CommandResult> resp = controller.execute(
                new SlashCommandController.ExecuteRequest("sess-1", "/new", "", 7L)); // attacker = 7

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().success()).isFalse();
        verify(slashCommandService, never())
                .execute(anyLong(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("session not found → 404")
    void sessionNotFound_returns404() {
        when(sessionService.getSession("sess-x")).thenThrow(new RuntimeException("Session not found"));

        ResponseEntity<CommandResult> resp = controller.execute(
                new SlashCommandController.ExecuteRequest("sess-x", "/new", "", 7L));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(slashCommandService, never())
                .execute(anyLong(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("session with null userId → 403 (do not let stale rows bypass ownership)")
    void nullSessionUserId_returns403() {
        SessionEntity s = new SessionEntity();
        s.setId("sess-1");
        s.setUserId(null);
        when(sessionService.getSession("sess-1")).thenReturn(s);

        ResponseEntity<CommandResult> resp = controller.execute(
                new SlashCommandController.ExecuteRequest("sess-1", "/new", "", 7L));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("null request body returns 400 (defensive guard)")
    void nullRequestBody_returns400() {
        ResponseEntity<CommandResult> resp = controller.execute(null);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(slashCommandService, never())
                .execute(anyLong(), anyString(), anyString(), any());
    }

    /*
     * Note: bean-validation (@Valid + @NotBlank/@NotNull) tests live in
     * SlashCommandControllerIT — those require Spring's MVC pipeline to fire
     * MethodArgumentNotValidException for missing / blank fields. Pure unit
     * tests at this layer cannot exercise that machinery.
     */
}
