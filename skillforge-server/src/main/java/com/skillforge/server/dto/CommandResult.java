package com.skillforge.server.dto;

/**
 * P10 §4: result of {@code POST /api/commands/execute} dashboard call and the
 * platform-agnostic return type from {@link com.skillforge.server.service.command.SlashCommandService}.
 *
 * <p>{@code displayMode} tells the dashboard FE how to render this result:
 * <ul>
 *   <li>{@code "redirect"} — {@code /new} → navigate to {@code /sessions/{newSessionId}}</li>
 *   <li>{@code "toast"}    — {@code /model} / {@code /compact} → message.success(message)</li>
 *   <li>{@code "modal"}    — {@code /skill} / {@code /tool} / {@code /context} / {@code /help}
 *                            / {@code /models} → render markdownBody in a Modal</li>
 * </ul>
 *
 * <p>For channel adapters (feishu / telegram), the router collapses all three
 * display modes into a single text reply (markdownBody if present, else message,
 * else error) so users without a GUI still see useful output.
 *
 * @param success      whether the command executed without error
 * @param message      short human-readable feedback text (toast / channel reply)
 * @param displayMode  one of {@code "redirect" | "toast" | "modal"} (only consulted on success)
 * @param newSessionId set by {@code /new} — dashboard navigates here
 * @param modelId      set by {@code /model} — current modelId after switch
 * @param markdownBody set by modal-display commands ({@code /skill} / {@code /tool} / etc.)
 * @param error        present on failure; FE renders as toast error
 */
public record CommandResult(
        boolean success,
        String message,
        String displayMode,
        String newSessionId,
        String modelId,
        String markdownBody,
        String error) {

    public static CommandResult redirect(String newSessionId, String message) {
        return new CommandResult(true, message, "redirect", newSessionId, null, null, null);
    }

    public static CommandResult toast(String message) {
        return new CommandResult(true, message, "toast", null, null, null, null);
    }

    public static CommandResult toastWithModel(String message, String modelId) {
        return new CommandResult(true, message, "toast", null, modelId, null, null);
    }

    public static CommandResult modal(String message, String markdownBody) {
        return new CommandResult(true, message, "modal", null, null, markdownBody, null);
    }

    public static CommandResult error(String error) {
        return new CommandResult(false, null, null, null, null, null, error);
    }
}
