package com.skillforge.server.channel.router;

import com.skillforge.server.channel.event.ChannelSessionOutputEvent;
import com.skillforge.server.channel.registry.ChannelAdapterRegistry;
import com.skillforge.server.channel.spi.ChannelConfigDecrypted;
import com.skillforge.server.channel.spi.ChannelMessage;
import com.skillforge.server.dto.CommandResult;
import com.skillforge.server.service.ChatService;
import com.skillforge.server.service.command.ExecutionContext;
import com.skillforge.server.service.command.SlashCommandService;
import com.skillforge.server.websocket.ChatWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Resolves (platform, conversation) → SkillForge session and dispatches to ChatService.
 * <p>
 * The transactional find-or-create lives on {@link ChannelConversationResolver} so that
 * {@code @Transactional} is applied via the Spring proxy. Calling it via {@code this.}
 * from the {@code @Async routeAsync} entrypoint would bypass the proxy and nullify the
 * PESSIMISTIC_WRITE lock that serializes concurrent "none exists → create" races.
 * <p>
 * B2-H2: {@link ChatWebSocketHandler#registerChannelTurn} is invoked on every turn
 * (not only at session creation) so each AgentLoop finish publishes the current turn's
 * platformMessageId.
 */
@Service
public class ChannelSessionRouter {

    private static final Logger log = LoggerFactory.getLogger(ChannelSessionRouter.class);

    private final ChannelConversationResolver resolver;
    private final ChatService chatService;
    private final ChatWebSocketHandler chatWebSocketHandler;
    private final ChannelAdapterRegistry adapterRegistry;
    private final SlashCommandService slashCommandService;
    private final ApplicationEventPublisher eventPublisher;
    private final ChannelConversationRebindService rebindService;

    @Autowired
    public ChannelSessionRouter(
            ChannelConversationResolver resolver,
            ChatService chatService,
            ChatWebSocketHandler chatWebSocketHandler,
            @Lazy ChannelAdapterRegistry adapterRegistry,
            SlashCommandService slashCommandService,
            ApplicationEventPublisher eventPublisher,
            ChannelConversationRebindService rebindService) {
        this.resolver = resolver;
        this.chatService = chatService;
        this.chatWebSocketHandler = chatWebSocketHandler;
        this.adapterRegistry = adapterRegistry;
        this.slashCommandService = slashCommandService;
        this.eventPublisher = eventPublisher;
        this.rebindService = rebindService;
    }

    @Async("channelRouterExecutor")
    public void routeAsync(ChannelMessage msg, ChannelConfigDecrypted config) {
        try {
            routeInternal(msg, config);
        } catch (Exception e) {
            log.error("Channel routing failed [{}] msg [{}]: {}",
                    msg.platform(), msg.platformMessageId(), e.getMessage(), e);
        }
    }

    private void routeInternal(ChannelMessage msg, ChannelConfigDecrypted config) {
        Long mappedUserId = resolver.resolveUser(msg);
        // resolveSession is @Transactional; retrying here (outside that transaction)
        // gives a fresh Hibernate session — required because a poisoned session from a
        // DataIntegrityViolationException cannot be reused within the same transaction.
        SessionRouteResult route;
        try {
            route = resolver.resolveSession(msg, config, mappedUserId);
        } catch (DataIntegrityViolationException race) {
            log.warn("Concurrent conversation creation hit unique constraint, retrying [{}] conv [{}]",
                    msg.platform(), msg.conversationId());
            route = resolver.resolveSession(msg, config, mappedUserId);
        }

        String text = msg.text() != null ? msg.text() : "";

        // P10 INV-5: slash-command interception MUST happen before chatService.chatAsync,
        // otherwise "/foo" would be sent to the LLM as a normal user message.
        if (text.trim().startsWith("/")) {
            handleSlashCommand(msg, config, route, text);
            return;
        }

        // Add typing-indicator reaction; reactionId is carried through to the listener
        // which removes it just before delivering the final reply.
        String ackReactionId = adapterRegistry.get(msg.platform())
                .map(adapter -> adapter.sendAck(msg, config))
                .orElse(null);

        // Register per-turn context before triggering the loop, so
        // sessionStatus("idle") finds the correct platformMessageId and ackReactionId.
        chatWebSocketHandler.registerChannelTurn(
                route.sessionId(), msg.platformMessageId(), ackReactionId, msg.platformUserId());

        chatService.chatAsync(route.sessionId(), text, route.skillforgeUserId());
    }

    /**
     * P10 INV-5 + INV-6: execute a slash command without entering the agent loop.
     *
     * <p>For {@code /new}: rebind {@code t_channel_conversation} so the next
     * inbound message lands on the freshly created session — close the existing
     * active row and insert a new one pointing to {@code newSessionId}.
     *
     * <p>For all other commands: just publish the result text via the channel
     * reply pipeline. {@code displayMode = "modal"} (markdown lists) collapses
     * to {@code markdownBody}; everything else uses {@code message} / {@code error}.
     */
    private void handleSlashCommand(ChannelMessage msg,
                                    ChannelConfigDecrypted config,
                                    SessionRouteResult route,
                                    String text) {
        String currentSessionId = route.sessionId();
        Long userId = route.skillforgeUserId();
        CommandResult result = slashCommandService.execute(
                userId,
                currentSessionId,
                text.trim(),
                ExecutionContext.channel(msg.platform()));

        // For /new in a channel session: rebind conversation → newSessionId so future
        // inbound messages on the same conversation hit the fresh session (INV-6).
        // The close-and-insert pair runs inside rebindService.rebind under
        // @Transactional — if `save` fails after `closeById` succeeds, the
        // whole tx rolls back and the original active row is preserved (W1).
        if (result.success() && "redirect".equals(result.displayMode())
                && result.newSessionId() != null) {
            try {
                rebindService.rebind(msg.platform(), msg.conversationId(),
                        config.id(), result.newSessionId());
            } catch (RuntimeException ex) {
                log.warn("Failed to rebind channel conversation for /new: platform={} conv={}",
                        msg.platform(), msg.conversationId(), ex);
            }
        }

        String reply = renderReplyText(result);
        if (reply != null && !reply.isBlank()) {
            eventPublisher.publishEvent(new ChannelSessionOutputEvent(
                    currentSessionId,
                    msg.platformMessageId(),
                    null,                  // no ack reaction was added — slash command is fire-and-confirm
                    reply));
        }
    }

    /**
     * Collapse a {@link CommandResult} into a single channel-reply text (channels
     * have no GUI for "modal" / "redirect" / "toast" display modes).
     */
    static String renderReplyText(CommandResult result) {
        if (result == null) return null;
        if (!result.success()) {
            return result.error() != null && !result.error().isBlank()
                    ? "❌ " + result.error()
                    : "❌ 命令执行失败";
        }
        if (result.markdownBody() != null && !result.markdownBody().isBlank()) {
            return result.markdownBody();
        }
        if (result.message() != null && !result.message().isBlank()) {
            return result.message();
        }
        return "✅ 已执行";
    }
}
