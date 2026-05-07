package com.skillforge.server.service.command;

import com.skillforge.server.dto.CommandResult;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.service.AgentService;
import com.skillforge.server.service.SessionService;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * P10 {@code /models} — list every model registered in the LLM provider config,
 * highlighting the one currently in effect for this session.
 *
 * <p>"Current" resolution order (matches what the engine actually uses):
 * <ol>
 *   <li>{@code session.runtimeModelOverride} (set by {@code /model})</li>
 *   <li>{@code agent.modelId} (default)</li>
 * </ol>
 *
 * <p>INV-14 (read-only): no DB writes.
 *
 * <p>INV-15 (precise dispatch): registered as a SEPARATE handler from
 * {@code /model}; the dispatcher will not absorb {@code /models} into
 * {@code /model}'s args because it uses exact-match.
 */
@Component
public class ModelsCommandHandler implements SlashCommandHandler {

    private final ModelCatalog modelCatalog;
    private final SessionService sessionService;
    private final AgentService agentService;

    public ModelsCommandHandler(ModelCatalog modelCatalog,
                                SessionService sessionService,
                                AgentService agentService) {
        this.modelCatalog = modelCatalog;
        this.sessionService = sessionService;
        this.agentService = agentService;
    }

    @Override
    public String getName() {
        return "models";
    }

    @Override
    public String getDescription() {
        return "列出所有可用的 LLM 模型（含 provider 信息，标记当前会话使用的模型）";
    }

    @Override
    public String getUsage() {
        return "/models";
    }

    @Override
    public CommandResult execute(Long userId, String sessionId, String args, ExecutionContext context) {
        SessionEntity session = sessionService.getSession(sessionId);
        String currentModel = session.getRuntimeModelOverride();
        if (currentModel == null || currentModel.isBlank()) {
            AgentEntity agent = agentService.getAgent(session.getAgentId());
            currentModel = agent.getModelId();
        }

        List<ModelCatalog.ModelEntry> models = modelCatalog.listAll();
        StringBuilder md = new StringBuilder();
        md.append("# 可用模型\n\n");
        if (models.isEmpty()) {
            md.append("_当前未配置任何 LLM provider。请检查 application.yml 的 `skillforge.llm.providers`。_\n");
        } else {
            md.append("| | Provider | Model | 默认 |\n");
            md.append("|---|---|---|---|\n");
            for (ModelCatalog.ModelEntry e : models) {
                boolean isCurrent = isCurrentModel(currentModel, e);
                md.append("| ").append(isCurrent ? "✅" : "").append(" | ")
                        .append("`").append(e.providerName()).append("`").append(" | ")
                        .append("`").append(e.modelName()).append("`").append(" | ")
                        .append(e.isDefault() ? "✓" : "")
                        .append(" |\n");
            }
            md.append("\n_用 `/model <providerName:modelName>` 切换；未指定 provider 时自动匹配。_\n");
        }

        String summary = currentModel != null
                ? "当前模型: " + currentModel + "（共 " + models.size() + " 个可用）"
                : "当前未指定模型（共 " + models.size() + " 个可用）";
        return CommandResult.modal(summary, md.toString());
    }

    /**
     * Mark "current" if the user's setting matches either the full id
     * ({@code provider:model}) or the bare model name. Bare names disambiguate
     * to the default-provider entry first; if there's no clash that's good
     * enough for a UI hint.
     */
    private boolean isCurrentModel(String current, ModelCatalog.ModelEntry e) {
        if (current == null) return false;
        if (current.contains(":")) return current.equals(e.fullId());
        // Bare match — flag every provider that exposes this name. Visually
        // multiple ✅ are fine; the user explicitly asked for an ambiguous form.
        return current.equals(e.modelName());
    }
}
