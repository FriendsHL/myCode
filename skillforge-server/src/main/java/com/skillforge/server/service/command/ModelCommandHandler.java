package com.skillforge.server.service.command;

import com.skillforge.server.dto.CommandResult;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.service.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * P10 {@code /model <modelId>} — switch the LLM model for THIS session only.
 *
 * <p>INV-4: writes ONLY {@code t_session.runtime_model_override}; never touches
 * {@code t_agent.model_id}. Next {@code chatAsync} loop reads the override (see
 * {@code ChatService.runLoop} / {@code completeConfirmedTool}).
 *
 * <p>INV-8: rejects model ids that are not configured in any LLM provider. The
 * catalog accepts either {@code provider:model} or a bare model name (the
 * existing {@code AgentLoopEngine.resolveProvider} already supports both
 * formats so no parsing change is needed downstream).
 */
@Component
public class ModelCommandHandler implements SlashCommandHandler {

    private static final Logger log = LoggerFactory.getLogger(ModelCommandHandler.class);

    private final SessionService sessionService;
    private final ModelCatalog modelCatalog;

    public ModelCommandHandler(SessionService sessionService, ModelCatalog modelCatalog) {
        this.sessionService = sessionService;
        this.modelCatalog = modelCatalog;
    }

    @Override
    public String getName() {
        return "model";
    }

    @Override
    public String getDescription() {
        return "切换当前会话使用的模型（仅本会话生效，不修改 agent 默认值）";
    }

    @Override
    public String getUsage() {
        return "/model <modelId>";
    }

    @Override
    @Transactional
    public CommandResult execute(Long userId, String sessionId, String args, ExecutionContext context) {
        if (args == null || args.isBlank()) {
            return CommandResult.error("用法：/model <modelId>，例如 /model claude:claude-sonnet-4-20250514");
        }
        String modelId = args.trim();
        if (!modelCatalog.isAvailable(modelId)) {
            return CommandResult.error("未找到该 model：" + modelId
                    + "（请用 /models 查看可用列表）");
        }
        SessionEntity session = sessionService.getSession(sessionId);
        session.setRuntimeModelOverride(modelId);
        sessionService.saveSession(session);
        log.info("/model switched session {} to '{}'", sessionId, modelId);
        return CommandResult.toastWithModel("已切换为 " + modelId, modelId);
    }
}
