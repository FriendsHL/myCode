package com.skillforge.server.service.command;

import com.skillforge.server.dto.CommandResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P10 §4: dispatcher that routes a slash-command line to the matching
 * {@link SlashCommandHandler} via EXACT name match (INV-15).
 *
 * <p>Handlers are auto-collected from the Spring context. The registry is a
 * {@link LinkedHashMap} so iteration order matches injection order (deterministic
 * for {@code /help} listing and for tests).
 *
 * <p>Exact-match (not prefix) is critical: with prefix matching {@code /model gpt-4}
 * could be absorbed by a {@code /models} handler if iteration happened to land on
 * it first. PRD INV-15 + tech-design §4 both call this out.
 *
 * <p>Unknown command behaviour (INV-9): {@code success=false} with a friendly
 * error message; the slash-command pipeline NEVER falls through to
 * {@code chatService.chatAsync} to avoid sending {@code /typo} to the LLM.
 */
@Service
public class SlashCommandService {

    private static final Logger log = LoggerFactory.getLogger(SlashCommandService.class);

    private final Map<String, SlashCommandHandler> handlers = new LinkedHashMap<>();

    public SlashCommandService(List<SlashCommandHandler> discovered) {
        for (SlashCommandHandler h : discovered) {
            String name = h.getName();
            if (name == null || name.isBlank()) {
                throw new IllegalStateException(
                        "SlashCommandHandler has blank name: " + h.getClass().getName());
            }
            if (handlers.containsKey(name)) {
                throw new IllegalStateException(
                        "Duplicate slash command name '" + name + "': "
                                + handlers.get(name).getClass().getName()
                                + " vs " + h.getClass().getName());
            }
            handlers.put(name, h);
        }
        log.info("Registered {} slash commands: {}", handlers.size(), handlers.keySet());
    }

    /**
     * Read-only view used by {@code HelpCommandHandler} (INV-11 — registry-based)
     * and tests.
     */
    public Collection<SlashCommandHandler> registeredHandlers() {
        return handlers.values();
    }

    /**
     * Parse and execute a slash-command line (e.g. {@code "/model gpt-4o"} or
     * {@code "/new"}).
     *
     * <p>Strips the leading {@code /}, splits on the FIRST whitespace into
     * {@code (token, args)} and dispatches by exact-match on token. Unknown
     * commands return a {@link CommandResult#error} (NOT thrown — INV-9).
     *
     * @param userId      caller's user id (controller / router has already
     *                    verified ownership of {@code sessionId})
     * @param sessionId   current session in scope
     * @param commandLine raw text including the leading slash, e.g.
     *                    {@code "/model gpt-4o"}
     * @param context     platform context (web or channel)
     */
    public CommandResult execute(Long userId,
                                 String sessionId,
                                 String commandLine,
                                 ExecutionContext context) {
        if (commandLine == null) {
            return CommandResult.error("空命令");
        }
        String trimmed = commandLine.trim();
        if (trimmed.isEmpty() || trimmed.charAt(0) != '/') {
            return CommandResult.error("命令必须以 / 开头");
        }

        // Drop the leading slash, split on first whitespace.
        String body = trimmed.substring(1);
        String token;
        String args;
        int sp = indexOfWhitespace(body);
        if (sp < 0) {
            token = body;
            args = "";
        } else {
            token = body.substring(0, sp);
            args = body.substring(sp + 1).trim();
        }
        // Normalize: command names are matched lowercase (FE may send mixed case).
        token = token.toLowerCase();

        if (token.isEmpty()) {
            return CommandResult.error("未知命令");
        }

        SlashCommandHandler handler = handlers.get(token);
        if (handler == null) {
            return CommandResult.error("未知命令 /" + token);
        }
        try {
            return handler.execute(userId, sessionId, args, context);
        } catch (RuntimeException ex) {
            // INV-9: never crash the caller (REST / channel) — convert into
            // a structured error result. Detailed cause stays in server log.
            log.warn("Slash command '/{}' failed for session {}: {}",
                    token, sessionId, ex.toString(), ex);
            String msg = ex.getMessage();
            return CommandResult.error(msg != null && !msg.isBlank()
                    ? msg
                    : "命令执行失败：" + ex.getClass().getSimpleName());
        }
    }

    private static int indexOfWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) return i;
        }
        return -1;
    }
}
