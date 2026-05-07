package com.skillforge.server.service.command;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.SkillDefinition;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.server.dto.CommandResult;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.service.AgentService;
import com.skillforge.server.service.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * P10 {@code /skill} — list every (zip-package) Skill the current agent has enabled,
 * resolved through {@link SkillRegistry#getSkillDefinition(String)}.
 *
 * <p>Missing-skill tolerance: if {@code agent.skill_ids} references a name no
 * longer present in the registry (e.g. removed by user), the row is rendered
 * as {@code (missing) <skillId>} instead of failing the command.
 *
 * <p>INV-14 (read-only): no DB writes. Only consults registry + agent config.
 */
@Component
public class SkillCommandHandler implements SlashCommandHandler {

    private static final Logger log = LoggerFactory.getLogger(SkillCommandHandler.class);

    private final SessionService sessionService;
    private final AgentService agentService;
    private final SkillRegistry skillRegistry;
    private final ObjectMapper objectMapper;

    public SkillCommandHandler(SessionService sessionService,
                               AgentService agentService,
                               SkillRegistry skillRegistry,
                               ObjectMapper objectMapper) {
        this.sessionService = sessionService;
        this.agentService = agentService;
        this.skillRegistry = skillRegistry;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "skill";
    }

    @Override
    public String getDescription() {
        return "列出当前 agent 启用的 skill（zip 包资产）";
    }

    @Override
    public String getUsage() {
        return "/skill";
    }

    @Override
    public CommandResult execute(Long userId, String sessionId, String args, ExecutionContext context) {
        SessionEntity session = sessionService.getSession(sessionId);
        AgentEntity agent = agentService.getAgent(session.getAgentId());

        List<String> skillIds = parseStringList(agent.getSkillIds());
        StringBuilder md = new StringBuilder();
        md.append("# Agent skills\n\n");
        md.append("**Agent**: ").append(agent.getName()).append("\n\n");
        if (skillIds.isEmpty()) {
            md.append("_当前 agent 未启用任何 skill。_\n");
            return CommandResult.modal("Skill 数: 0", md.toString());
        }
        md.append("| | Skill | Description |\n");
        md.append("|---|---|---|\n");
        int found = 0;
        for (String id : skillIds) {
            var def = skillRegistry.getSkillDefinition(id).orElse(null);
            String name;
            String desc;
            if (def == null) {
                name = "(missing) " + id;
                desc = "_skill not found in registry_";
            } else {
                found++;
                name = displayName(def);
                desc = nullSafe(def.getDescription());
            }
            md.append("| 🧩 | `").append(escapePipe(name)).append("` | ")
                    .append(escapePipe(desc)).append(" |\n");
        }
        return CommandResult.modal("Skill 数: " + found + "/" + skillIds.size(), md.toString());
    }

    private List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse JSON list: {}", json, e);
            return new ArrayList<>();
        }
    }

    private static String displayName(SkillDefinition def) {
        return def.getName() != null ? def.getName() : "(unnamed)";
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    /** Markdown table cells: pipes break the column boundary. */
    private static String escapePipe(String s) {
        return s == null ? "" : s.replace("|", "\\|").replace("\n", " ");
    }
}
