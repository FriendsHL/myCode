package com.skillforge.server.memory.hook;

import com.skillforge.core.engine.hook.BuiltInMethod;
import com.skillforge.core.engine.hook.HookExecutionContext;
import com.skillforge.core.engine.hook.HookRunResult;
import com.skillforge.server.entity.MemoryProposalEntity;
import com.skillforge.server.repository.MemoryProposalRepository;
import com.skillforge.server.websocket.UserWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * MEMORY-LLM-SYNTHESIS (V69 dogfood): SESSION_END lifecycle hook attached to the
 * seeded {@code memory-curator} system agent. After a memory-curator session
 * (master or sub) finishes, this counts pending {@code proposed} proposals and
 * broadcasts a notification to every connected user-WS client so the Memory
 * Pending Reflections tab can refresh without polling.
 *
 * <p>Why broadcast to every connected userId: SkillForge today has no admin-role
 * concept (single-tenant dev/internal system); the dashboard's admin tools are
 * accessible to whoever is logged in. The hook is intentionally idempotent —
 * firing once per sub-session (typically once per active user) is fine because
 * the payload only contains a count, which the FE uses to invalidate a query.
 *
 * <p>{@code ref()} lives in the reserved {@code builtin.*} namespace and is
 * collected by {@link com.skillforge.server.hook.BuiltInMethodRegistry} at
 * startup, referenced from the seeded agent's {@code lifecycle_hooks} JSON
 * column ({@link com.skillforge.core.engine.hook.HookHandler.MethodHandler}
 * with {@code methodRef = "builtin.memory.proposal-ready-broadcaster"}).
 */
@Component
public class MemoryProposalReadyBroadcaster implements BuiltInMethod {

    static final String REF = "builtin.memory.proposal-ready-broadcaster";
    static final String PAYLOAD_TYPE = "memory_proposals_pending";

    private static final Logger log = LoggerFactory.getLogger(MemoryProposalReadyBroadcaster.class);

    private final MemoryProposalRepository proposalRepository;
    private final UserWebSocketHandler userWebSocketHandler;

    public MemoryProposalReadyBroadcaster(MemoryProposalRepository proposalRepository,
                                          UserWebSocketHandler userWebSocketHandler) {
        this.proposalRepository = proposalRepository;
        this.userWebSocketHandler = userWebSocketHandler;
    }

    @Override
    public String ref() {
        return REF;
    }

    @Override
    public String displayName() {
        return "Broadcast Memory Proposals Pending";
    }

    @Override
    public String description() {
        return "Counts proposed-status t_memory_proposal rows and broadcasts a "
                + "memory_proposals_pending event to every connected user WS client.";
    }

    @Override
    public Map<String, String> argsSchema() {
        // No args — the hook fires unconditionally on SESSION_END.
        return Map.of();
    }

    @Override
    public HookRunResult execute(Map<String, Object> args, HookExecutionContext ctx) {
        long t0 = System.currentTimeMillis();
        long pendingCount;
        try {
            pendingCount = proposalRepository.countByStatus(MemoryProposalEntity.STATUS_PROPOSED);
        } catch (Exception e) {
            // DB error must not bubble — hook FailurePolicy.continue suppresses it anyway
            // but explicit log keeps ops diagnosable.
            log.warn("[MemoryProposalReadyBroadcaster] count query failed: {}", e.getMessage());
            return HookRunResult.failure("count query failed: " + e.getMessage(), elapsed(t0));
        }
        if (pendingCount == 0) {
            // No-op: nothing to surface. Don't spam every user's WS.
            return HookRunResult.ok("no proposals pending", elapsed(t0));
        }

        Set<Long> userIds = userWebSocketHandler.connectedUserIds();
        if (userIds.isEmpty()) {
            // Nobody connected — broadcast would be a no-op anyway. Avoid log noise.
            log.debug("[MemoryProposalReadyBroadcaster] {} proposals pending but no WS clients connected",
                    pendingCount);
            return HookRunResult.ok("no connected ws clients", elapsed(t0));
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", PAYLOAD_TYPE);
        payload.put("count", pendingCount);
        payload.put("message", pendingCount + " memory proposal(s) pending review");
        payload.put("sessionId", ctx != null ? ctx.sessionId() : null);

        int delivered = 0;
        for (Long userId : userIds) {
            try {
                userWebSocketHandler.broadcast(userId, payload);
                delivered++;
            } catch (Exception e) {
                log.warn("[MemoryProposalReadyBroadcaster] broadcast to userId={} failed: {}",
                        userId, e.getMessage());
            }
        }
        log.info("[MemoryProposalReadyBroadcaster] broadcast count={} delivered={}/{} sessionId={}",
                pendingCount, delivered, userIds.size(), ctx != null ? ctx.sessionId() : null);
        return HookRunResult.ok("broadcast count=" + pendingCount + " to " + delivered + " user(s)",
                elapsed(t0));
    }

    private static long elapsed(long t0) {
        return System.currentTimeMillis() - t0;
    }
}
