package com.skillforge.server.service.command;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.core.skill.Tool;
import com.skillforge.server.dto.CommandResult;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.service.AgentService;
import com.skillforge.server.service.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * P10 {@code /tool} — list every Tool the current agent can call, INCLUDING
 * system built-ins (INV-12). Mirrors the filtering applied by
 * {@code AgentLoopEngine.collectTools}: when {@code agent.toolIds} is null or
 * empty the LLM sees all registered tools; when set, only those names are
 * available.
 *
 * <p>Display columns:
 * <ul>
 *   <li>name — Tool#getName()</li>
 *   <li>type — {@code system} (registered at boot) or {@code user} (added later
 *       via SkillRegistry — currently rare, kept as future-proof)</li>
 *   <li>description — Tool#getDescription() (truncated for readability)</li>
 * </ul>
 *
 * <p>"system" classification leans on a hardcoded snapshot of the names
 * registered in {@link com.skillforge.server.config.SkillForgeConfig#skillRegistry}
 * — the registry doesn't tag tools by origin so this is the only way without
 * adding a new contract field. If that list drifts the worst symptom is a Tool
 * displayed as "user" — purely cosmetic.
 *
 * <p>INV-14 (read-only).
 */
@Component
public class ToolCommandHandler implements SlashCommandHandler {

    private static final Logger log = LoggerFactory.getLogger(ToolCommandHandler.class);

    /**
     * Names registered as "system" tools in {@code SkillForgeConfig.skillRegistry()}.
     * Update when adding / removing built-ins. Not used for behaviour, only
     * the {@code type} column in /tool output.
     */
    private static final Set<String> SYSTEM_TOOL_NAMES = Set.of(
            "Bash", "FileRead", "Read", "FileWrite", "Write", "FileEdit", "Edit",
            "Glob", "Grep",
            "Memory", "memory_search", "memory_detail",
            "WebFetch", "WebSearch",
            // additional built-ins the agent loop exposes (best-effort enumeration; missing
            // entries simply render as "user" which is harmless cosmetically)
            "CodeSandbox", "AgentDiscovery", "GetAgentConfig",
            "CreateAgent", "UpdateAgent",
            "Task", "TaskList", "TaskGet", "TaskUpdate", "TaskCreate",
            "TeamCreate", "TeamSend", "TeamKill", "TeamList",
            "ScheduleWakeup",
            "CronCreate", "CronDelete", "CronList",
            "Schedule", "PushNotification", "RemoteTrigger");

    private final SessionService sessionService;
    private final AgentService agentService;
    private final SkillRegistry skillRegistry;
    private final ObjectMapper objectMapper;

    public ToolCommandHandler(SessionService sessionService,
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
        return "tool";
    }

    @Override
    public String getDescription() {
        return "列出当前 agent 可调用的 tool（含系统内置工具）";
    }

    @Override
    public String getUsage() {
        return "/tool";
    }

    @Override
    public CommandResult execute(Long userId, String sessionId, String args, ExecutionContext context) {
        SessionEntity session = sessionService.getSession(sessionId);
        AgentEntity agent = agentService.getAgent(session.getAgentId());

        // Allow-list: parse agent.toolIds. Null/empty = no restriction (== all tools).
        Set<String> allowed = parseStringList(agent.getToolIds()).stream()
                .filter(s -> s != null && !s.isBlank())
                .collect(Collectors.toCollection(HashSet::new));
        boolean unrestricted = allowed.isEmpty();

        Collection<Tool> all = skillRegistry.getAllTools();
        // Sort by name for deterministic output (registry is a ConcurrentHashMap → unordered).
        List<Tool> ordered = all.stream()
                .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
                .collect(Collectors.toList());

        StringBuilder md = new StringBuilder();
        md.append("# Agent tools\n\n");
        md.append("**Agent**: ").append(agent.getName()).append("\n\n");
        if (unrestricted) {
            md.append("_未配置 toolIds 白名单 → 所有已注册 tool 均可使用。_\n\n");
        } else {
            md.append("_toolIds 白名单已配置（共 ")
                    .append(allowed.size()).append(" 个），仅允许使用列出的 tool。_\n\n");
        }
        md.append("| | Name | Type | Description |\n");
        md.append("|---|---|---|---|\n");

        int shown = 0;
        for (Tool t : ordered) {
            String name = t.getName();
            if (!unrestricted && !allowed.contains(name)) continue;
            shown++;
            String type = SYSTEM_TOOL_NAMES.contains(name) ? "system" : "user";
            String descRaw = t.getDescription();
            String desc = descRaw == null ? "" : descRaw;
            // Truncate long descriptions for table readability.
            if (desc.length() > 160) desc = desc.substring(0, 157) + "…";
            md.append("| 🔧 | `").append(escapePipe(name)).append("` | ")
                    .append(type).append(" | ")
                    .append(escapePipe(desc)).append(" |\n");
        }

        if (shown == 0) {
            md.append("\n_当前 agent 没有可用 tool。_\n");
        }
        return CommandResult.modal("Tool 数: " + shown, md.toString());
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

    private static String escapePipe(String s) {
        return s == null ? "" : s.replace("|", "\\|").replace("\n", " ");
    }
}
