package com.skillforge.server.service.command;

import com.skillforge.server.dto.CommandResult;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * P10 {@code /help} — list every registered slash command with its
 * description and usage.
 *
 * <p>INV-11 (registry-based): the listing is built dynamically from
 * {@link SlashCommandService#registeredHandlers()} — DO NOT hardcode a static
 * list of command names anywhere in the FE or here. Adding a new
 * {@link SlashCommandHandler} component automatically extends {@code /help}
 * output.
 *
 * <p>Lazy / ObjectProvider injection: avoids circular dependency
 * ({@code SlashCommandService} → handlers → ... → SlashCommandService) at
 * bean wiring time. Resolved on first call.
 */
@Component
public class HelpCommandHandler implements SlashCommandHandler {

    private final ObjectProvider<SlashCommandService> serviceProvider;

    public HelpCommandHandler(@Lazy ObjectProvider<SlashCommandService> serviceProvider) {
        this.serviceProvider = serviceProvider;
    }

    @Override
    public String getName() {
        return "help";
    }

    @Override
    public String getDescription() {
        return "列出所有可用的斜杠命令";
    }

    @Override
    public String getUsage() {
        return "/help";
    }

    @Override
    public CommandResult execute(Long userId, String sessionId, String args, ExecutionContext context) {
        SlashCommandService svc = serviceProvider.getObject();
        // Sort by name for deterministic output, but keep "help" last (it's
        // self-referential and least useful at the top).
        List<SlashCommandHandler> all = new ArrayList<>(svc.registeredHandlers());
        all.sort((a, b) -> {
            if ("help".equals(a.getName()) && !"help".equals(b.getName())) return 1;
            if ("help".equals(b.getName()) && !"help".equals(a.getName())) return -1;
            return a.getName().compareToIgnoreCase(b.getName());
        });

        StringBuilder md = new StringBuilder();
        md.append("# 斜杠命令\n\n");
        md.append("| 命令 | 说明 | 用法 |\n");
        md.append("|---|---|---|\n");
        for (SlashCommandHandler h : all) {
            md.append("| `/").append(h.getName()).append("` | ")
                    .append(escapePipe(h.getDescription())).append(" | ")
                    .append("`").append(escapePipe(h.getUsage())).append("`")
                    .append(" |\n");
        }
        md.append("\n_未知命令会返回错误，不会被发送给模型。_\n");
        return CommandResult.modal("已注册命令: " + all.size(), md.toString());
    }

    private static String escapePipe(String s) {
        return s == null ? "" : s.replace("|", "\\|").replace("\n", " ");
    }
}
