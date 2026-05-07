package com.skillforge.server.service.command;

import com.skillforge.server.dto.CommandResult;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.service.AgentService;
import com.skillforge.server.service.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * P10 {@code /new [agentName?]} — create a new session.
 *
 * <ul>
 *   <li>No args → reuse the current session's agent (default behaviour from PRD).</li>
 *   <li>One arg → look up the agent by name owned by the user (or public). The new
 *       session inherits that agent.</li>
 * </ul>
 *
 * <p>Always returns {@code displayMode = "redirect"} with the new {@code sessionId}.
 * Channel router collapses redirect into a text reply + rebinds
 * {@code t_channel_conversation.session_id} (INV-6).
 */
@Component
public class NewCommandHandler implements SlashCommandHandler {

    private static final Logger log = LoggerFactory.getLogger(NewCommandHandler.class);

    private final SessionService sessionService;
    private final AgentService agentService;

    public NewCommandHandler(SessionService sessionService, AgentService agentService) {
        this.sessionService = sessionService;
        this.agentService = agentService;
    }

    @Override
    public String getName() {
        return "new";
    }

    @Override
    public String getDescription() {
        return "开启一个新的会话（默认沿用当前 agent，可选 /new <agentName> 切换）";
    }

    @Override
    public String getUsage() {
        return "/new [agentName]";
    }

    @Override
    public CommandResult execute(Long userId, String sessionId, String args, ExecutionContext context) {
        SessionEntity current = sessionService.getSession(sessionId);
        Long targetAgentId;
        if (args == null || args.isBlank()) {
            targetAgentId = current.getAgentId();
        } else {
            // Search agents owned by user; fall back to public agents matching by name.
            String name = args.trim();
            targetAgentId = agentService.listAgents(userId).stream()
                    .filter(a -> name.equals(a.getName()))
                    .map(AgentEntity::getId)
                    .findFirst()
                    .orElseGet(() -> agentService.listPublicAgents().stream()
                            .filter(a -> name.equals(a.getName()))
                            .map(AgentEntity::getId)
                            .findFirst()
                            .orElse(null));
            if (targetAgentId == null) {
                return CommandResult.error("未找到 agent: " + name);
            }
        }

        SessionEntity created = sessionService.createSession(userId, targetAgentId);
        log.info("/new created session {} for user {} (agentId={})",
                created.getId(), userId, targetAgentId);
        return CommandResult.redirect(created.getId(), "已开启新对话");
    }
}
