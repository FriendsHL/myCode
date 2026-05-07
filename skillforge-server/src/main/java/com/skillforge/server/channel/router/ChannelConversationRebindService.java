package com.skillforge.server.channel.router;

import com.skillforge.server.entity.ChannelConversationEntity;
import com.skillforge.server.repository.ChannelConversationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * P10 INV-6 — atomic close-and-replace of the active
 * {@link ChannelConversationEntity} when the user runs {@code /new} from a
 * channel-backed session.
 *
 * <p>The two operations (close existing active row + insert new active row
 * pointing to the freshly created session) MUST be in the same transaction.
 * If they aren't and the {@code save} fails after the {@code closeById}
 * succeeds, the channel ends up with no active conversation row and all
 * subsequent inbound messages will trigger a new session creation instead of
 * landing on the {@code /new} session. R1 review W1 identified this risk.
 *
 * <p>This is a separate {@code @Service} bean (not a method on
 * {@link ChannelSessionRouter}) so the {@code @Transactional} advice is
 * applied via the Spring AOP proxy. Calling a {@code @Transactional} method
 * via {@code this.} from another method on the same bean would bypass the
 * proxy — same trap that motivated extracting
 * {@link ChannelConversationResolver}.
 */
@Service
public class ChannelConversationRebindService {

    private static final Logger log = LoggerFactory.getLogger(ChannelConversationRebindService.class);

    private final ChannelConversationRepository conversationRepo;

    public ChannelConversationRebindService(ChannelConversationRepository conversationRepo) {
        this.conversationRepo = conversationRepo;
    }

    /**
     * Close the currently active row for {@code (platform, conversationId)} (if
     * any) and insert a fresh row pointing to {@code newSessionId}, all in one
     * transaction.
     *
     * @param platform        channel platform name (e.g. {@code "feishu"})
     * @param conversationId  platform-side conversation id
     * @param channelConfigId id of the {@code t_channel_config} row
     * @param newSessionId    id of the freshly created session
     */
    @Transactional
    public void rebind(String platform,
                       String conversationId,
                       Long channelConfigId,
                       String newSessionId) {
        conversationRepo.findActiveForUpdate(platform, conversationId)
                .ifPresent(active -> conversationRepo.closeById(active.getId(), Instant.now()));
        ChannelConversationEntity newConv = new ChannelConversationEntity();
        newConv.setPlatform(platform);
        newConv.setConversationId(conversationId);
        newConv.setSessionId(newSessionId);
        newConv.setChannelConfigId(channelConfigId);
        conversationRepo.save(newConv);
        log.info("Rebound channel conversation: platform={} conv={} newSessionId={}",
                platform, conversationId, newSessionId);
    }
}
