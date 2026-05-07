package com.skillforge.server.controller;

import com.skillforge.server.dto.CommandResult;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.service.SessionService;
import com.skillforge.server.service.command.ExecutionContext;
import com.skillforge.server.service.command.SlashCommandService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * P10 §4: REST entry point for dashboard slash-command execution.
 *
 * <p>Single endpoint {@code POST /api/commands/execute}. The wire shape mirrors
 * what the FE sends — split into {@code command} (name, with leading slash) and
 * {@code args} (everything after the first whitespace) — and the controller
 * recombines them into the {@code commandLine} string that
 * {@link SlashCommandService#execute} expects. This split-then-rejoin avoids
 * silent Jackson failures: r1 review B1 found that the previous
 * {@code commandLine} field was being deserialized to {@code null} for every
 * real FE call because the JSON keys did not match.
 *
 * <p>Ownership of {@code sessionId} is verified against {@code userId} before
 * any handler runs (INV-10) — symmetric with {@code ChatController}.
 *
 * <p>Channel-side execution does NOT go through this controller; see
 * {@code ChannelSessionRouter.routeInternal}.
 */
@RestController
@RequestMapping("/api/commands")
public class SlashCommandController {

    private static final Logger log = LoggerFactory.getLogger(SlashCommandController.class);

    private final SlashCommandService slashCommandService;
    private final SessionService sessionService;

    public SlashCommandController(SlashCommandService slashCommandService,
                                  SessionService sessionService) {
        this.slashCommandService = slashCommandService;
        this.sessionService = sessionService;
    }

    /**
     * Wire shape: {@code {sessionId, command, args, userId}}.
     *
     * <p>{@code command} carries the leading slash (FE sends {@code "/model"});
     * {@code args} may be empty or null when the user typed just the command
     * name (e.g. {@code /help}).
     */
    public record ExecuteRequest(
            @NotBlank String sessionId,
            @NotBlank String command,
            String args,
            @NotNull Long userId) {}

    @PostMapping("/execute")
    public ResponseEntity<CommandResult> execute(@Valid @RequestBody ExecuteRequest request) {
        // Defensive null check: @Valid handles @NotBlank/@NotNull but not a null
        // request body itself (Spring will normally reject that earlier). Keep a
        // belt-and-braces guard so we never hit NPE in the rebuild step.
        if (request == null) {
            return ResponseEntity.badRequest().body(
                    CommandResult.error("请求体不能为空"));
        }

        // Ownership check — must own session before executing any command on it.
        SessionEntity session;
        try {
            session = sessionService.getSession(request.sessionId());
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    CommandResult.error("会话不存在"));
        }
        if (session.getUserId() == null || !session.getUserId().equals(request.userId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    CommandResult.error("无权操作该会话"));
        }

        // Reassemble the parsed (command, args) shape the FE sent into the raw
        // commandLine that SlashCommandService.execute expects. Trimming on args
        // matches the dispatcher's tokenizer (it splits on whitespace and trims
        // the args side anyway).
        String args = request.args();
        String commandLine = (args == null || args.isBlank())
                ? request.command()
                : request.command() + " " + args.trim();

        CommandResult result = slashCommandService.execute(
                request.userId(),
                request.sessionId(),
                commandLine,
                ExecutionContext.web());
        return ResponseEntity.ok(result);
    }
}
