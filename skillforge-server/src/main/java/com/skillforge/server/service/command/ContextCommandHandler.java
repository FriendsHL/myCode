package com.skillforge.server.service.command;

import com.skillforge.core.compact.TokenEstimator;
import com.skillforge.core.model.Message;
import com.skillforge.server.dto.CommandResult;
import com.skillforge.server.dto.ContextBreakdownDto;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.service.ContextBreakdownService;
import com.skillforge.server.service.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * P10 {@code /context} — show how much of the model context window is currently
 * spent and where.
 *
 * <p>Three sections, all derived from the existing {@code TokenEstimator}
 * (CTX-1) — INV-13 forbids a parallel token implementation.
 *
 * <ol>
 *   <li>Headline: total tokens / window / pct</li>
 *   <li>Segment breakdown (system_prompt / tool_schemas / messages /
 *       output_reservation) reused from {@link ContextBreakdownService}</li>
 *   <li>Top-5 largest individual messages — useful for users hunting "why is
 *       my context so full?".</li>
 * </ol>
 *
 * <p>INV-14 (read-only).
 */
@Component
public class ContextCommandHandler implements SlashCommandHandler {

    private static final Logger log = LoggerFactory.getLogger(ContextCommandHandler.class);
    private static final int TOP_MESSAGES_COUNT = 5;

    private final SessionService sessionService;
    private final ContextBreakdownService contextBreakdownService;

    public ContextCommandHandler(SessionService sessionService,
                                 ContextBreakdownService contextBreakdownService) {
        this.sessionService = sessionService;
        this.contextBreakdownService = contextBreakdownService;
    }

    @Override
    public String getName() {
        return "context";
    }

    @Override
    public String getDescription() {
        return "查看当前会话的 token 占比、分布和距压缩阈值的距离";
    }

    @Override
    public String getUsage() {
        return "/context";
    }

    @Override
    public CommandResult execute(Long userId, String sessionId, String args, ExecutionContext context) {
        SessionEntity session = sessionService.getSession(sessionId);
        ContextBreakdownDto breakdown = contextBreakdownService.breakdown(session, userId);
        List<Message> messages = sessionService.getContextMessages(sessionId);

        StringBuilder md = new StringBuilder();
        md.append("# Context usage\n\n");
        md.append(String.format(Locale.ROOT,
                "- Total: **%,d tokens** / window: **%,d** (**%d%%**)%n",
                breakdown.total(), breakdown.windowLimit(), breakdown.pct()));
        long remaining = Math.max(0, breakdown.windowLimit() - breakdown.total());
        md.append(String.format(Locale.ROOT,
                "- Remaining: **%,d tokens**%n", remaining));
        md.append("\n## Segments\n\n");
        md.append("| Segment | Tokens | % of total |\n");
        md.append("|---|---:|---:|\n");
        long total = Math.max(1, breakdown.total());
        for (ContextBreakdownDto.Segment s : breakdown.segments()) {
            int pct = (int) Math.round(s.tokens() * 100.0 / total);
            md.append("| ").append(s.label())
                    .append(" | ").append(formatNumber(s.tokens()))
                    .append(" | ").append(pct).append("% |\n");
        }

        // Top messages — direct TokenEstimator pass over the live context list.
        md.append("\n## Top ").append(TOP_MESSAGES_COUNT).append(" largest messages\n\n");
        List<MessageStat> stats = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            Message m = messages.get(i);
            int tokens = TokenEstimator.estimate(List.of(m));
            String role = m.getRole() != null ? m.getRole().name().toLowerCase(Locale.ROOT) : "";
            stats.add(new MessageStat(i, role, describeContent(m), tokens));
        }
        stats.sort(Comparator.comparingInt(MessageStat::tokens).reversed());

        if (stats.isEmpty()) {
            md.append("_会话尚无消息。_\n");
        } else {
            md.append("| # | Role | Tokens | Preview |\n");
            md.append("|---|---|---:|---|\n");
            int shown = 0;
            for (MessageStat ms : stats) {
                if (shown++ >= TOP_MESSAGES_COUNT) break;
                md.append("| ").append(ms.index()).append(" | ")
                        .append(ms.role() == null ? "" : ms.role()).append(" | ")
                        .append(formatNumber(ms.tokens())).append(" | ")
                        .append(escapePipe(ms.preview())).append(" |\n");
            }
        }

        String summary = String.format(Locale.ROOT,
                "Context: %,d / %,d tokens (%d%%)",
                breakdown.total(), breakdown.windowLimit(), breakdown.pct());
        return CommandResult.modal(summary, md.toString());
    }

    private record MessageStat(int index, String role, String preview, int tokens) {}

    /**
     * Best-effort textual preview of a message — first 80 chars of any text-type
     * content block (or the raw string content if not a list).
     */
    private static String describeContent(Message m) {
        if (m == null) return "";
        Object c = m.getContent();
        if (c == null) return "";
        if (c instanceof String s) {
            return truncate(s, 80);
        }
        if (c instanceof List<?> list) {
            for (Object o : list) {
                if (o == null) continue;
                String txt = extractText(o);
                if (txt != null && !txt.isBlank()) {
                    return truncate(txt, 80);
                }
            }
        }
        return c.toString().length() > 80 ? c.toString().substring(0, 77) + "…" : c.toString();
    }

    private static String extractText(Object block) {
        if (block instanceof com.skillforge.core.model.ContentBlock cb) {
            if (cb.getText() != null && !cb.getText().isBlank()) return cb.getText();
            if (cb.getName() != null) return "[tool_use " + cb.getName() + "]";
            if (cb.getContent() != null) return "[tool_result]";
            return null;
        }
        if (block instanceof java.util.Map<?, ?> map) {
            Object t = map.get("text");
            if (t instanceof String s && !s.isBlank()) return s;
            Object type = map.get("type");
            if (type != null) return "[" + type + "]";
        }
        return null;
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        String collapsed = s.replaceAll("\\s+", " ").trim();
        if (collapsed.length() <= n) return collapsed;
        return collapsed.substring(0, Math.max(0, n - 1)) + "…";
    }

    private static String formatNumber(long n) {
        return String.format(Locale.ROOT, "%,d", n);
    }

    private static String escapePipe(String s) {
        return s == null ? "" : s.replace("|", "\\|").replace("\n", " ");
    }
}
