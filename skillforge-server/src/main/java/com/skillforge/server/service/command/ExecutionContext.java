package com.skillforge.server.service.command;

/**
 * P10 §4: opaque platform context passed to every {@link SlashCommandHandler}.
 *
 * <p>{@code platform} is one of {@code "web"} (dashboard via REST) or any channel
 * platform name (e.g. {@code "feishu"}, {@code "telegram"}). Handlers SHOULD remain
 * platform-agnostic; the only behaviour that branches today is whether {@code /new}
 * needs an explicit dashboard redirect ({@code displayMode = "redirect"}) — channels
 * collapse that to a text reply at the router layer.
 *
 * <p>Kept as a record (not enum) so future fields (e.g. {@code conversationId} for
 * channel-side {@code /new} rebinding) can be added without ripple changes.
 */
public record ExecutionContext(String platform) {

    public static final String PLATFORM_WEB = "web";

    public static ExecutionContext web() {
        return new ExecutionContext(PLATFORM_WEB);
    }

    public static ExecutionContext channel(String platform) {
        return new ExecutionContext(platform);
    }

    public boolean isWeb() {
        return PLATFORM_WEB.equals(platform);
    }
}
