package com.skillforge.server.service.scheduling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.channel.ChannelConfigService;
import com.skillforge.server.channel.delivery.ReplyDeliveryService;
import com.skillforge.server.channel.registry.ChannelAdapterRegistry;
import com.skillforge.server.channel.spi.ChannelAdapter;
import com.skillforge.server.channel.spi.ChannelConfigDecrypted;
import com.skillforge.server.channel.spi.ChannelReply;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * P12: pushes scheduled-task results to a configured channel
 * (currently feishu only — MVP scope; the registry-based design lets V2 add
 * more platforms without changes here).
 *
 * <p>Why a dedicated component instead of going through {@link ReplyDeliveryService}
 * directly: scheduled-task pushes are not session-conversation replies. There's
 * no inbound message id, no conversation id from a webhook. We synthesize a
 * stable-ish id from the run id so the persistence row in {@code t_channel_delivery}
 * still has a key. This keeps the existing persistence + retry pattern working.
 *
 * <p>{@link #pushResult} is intentionally exception-swallowing — INV-9 says push
 * failure must NOT change task status. Caller passes the already-formatted
 * message text; this class only resolves channel + delivers.
 */
@Component
public class SchedulerChannelDispatcher {

    private static final Logger log = LoggerFactory.getLogger(SchedulerChannelDispatcher.class);

    private final ChannelAdapterRegistry adapterRegistry;
    private final ChannelConfigService configService;
    private final ReplyDeliveryService deliveryService;
    private final ObjectMapper objectMapper;

    public SchedulerChannelDispatcher(ChannelAdapterRegistry adapterRegistry,
                                      ChannelConfigService configService,
                                      ReplyDeliveryService deliveryService,
                                      ObjectMapper objectMapper) {
        this.adapterRegistry = adapterRegistry;
        this.configService = configService;
        this.deliveryService = deliveryService;
        this.objectMapper = objectMapper;
    }

    /**
     * Push a result to the channel. Never throws — INV-9 says channel push
     * failure must not affect task state. Returns a short error hint string when
     * delivery couldn't even be attempted (caller writes it to {@code run.error_message});
     * returns {@code null} on a clean attempt.
     *
     * @param channelTargetJson raw JSON {@code {"channelType":"feishu","channelId":"oc_xxx"}}
     *                          or {@code null} when no channel is configured
     * @param sessionId         the session that produced the message (for delivery row tracing)
     * @param runId             the {@code t_scheduled_task_run.id} (synthesized into inboundMessageId)
     * @param text              the formatted message text to deliver
     */
    public String pushResult(String channelTargetJson, String sessionId, Long runId, String text) {
        if (channelTargetJson == null || channelTargetJson.isBlank()) {
            return null; // no channel configured — silent no-op (INV-9, brief §6)
        }
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            Map<?, ?> parsed = objectMapper.readValue(channelTargetJson, Map.class);
            Object channelTypeRaw = parsed.get("channelType");
            Object channelIdRaw = parsed.get("channelId");
            if (!(channelTypeRaw instanceof String channelType) || channelType.isBlank()) {
                return "channelTarget.channelType missing or invalid";
            }
            if (!(channelIdRaw instanceof String channelId) || channelId.isBlank()) {
                return "channelTarget.channelId missing or invalid";
            }
            Optional<ChannelAdapter> adapter = adapterRegistry.get(channelType);
            if (adapter.isEmpty()) {
                return "no adapter registered for channelType=" + channelType;
            }
            Optional<ChannelConfigDecrypted> config = configService.getDecryptedConfig(channelType);
            if (config.isEmpty()) {
                return "no active channel config for platform=" + channelType;
            }
            // Synthesize a stable inbound message id so the t_channel_delivery row
            // has something searchable. Format: scheduler:<runId>:<short uuid>.
            String synthetic = "scheduler:" + (runId != null ? runId : "manual")
                    + ":" + UUID.randomUUID().toString().substring(0, 8);
            ChannelReply reply = new ChannelReply(
                    synthetic,
                    channelType,
                    channelId,
                    text,
                    false,   // useRichFormat=false: keep MVP simple, plain text
                    null
            );
            deliveryService.deliver(reply, adapter.get(), config.get(), sessionId);
            return null;
        } catch (Throwable e) {
            // r2 W5: catch Throwable, not just Exception. INV-9 has to be airtight —
            // an unchecked Error (OOM / AssertionError / NoClassDefFoundError) bubbling
            // up through the SessionLoopFinishedEvent listener would leave the run row
            // in 'running' status and runningTaskIds permanently polluted, blocking
            // every future fire of that task (skip-if-running). We don't try to
            // "handle" Errors meaningfully — only stop them at this boundary so the
            // task lifecycle stays consistent. The Error itself is logged at error
            // level + the JVM may still die naturally on OOM, which is fine.
            log.error("Scheduled task channel push failed (sessionId={}, runId={}): {}",
                    sessionId, runId, e.toString(), e);
            return "channel push error: " + e.getMessage();
        }
    }
}
