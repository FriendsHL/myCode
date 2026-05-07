package com.skillforge.server.service.command;

import com.skillforge.server.dto.CommandResult;
import com.skillforge.server.service.CompactionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * P10 {@code /compact} — trigger a full context compact for the current session.
 *
 * <p>Delegates to {@link CompactionService#compact(String, String, String, String)}
 * with {@code level="full"} (INV-7 — reuses existing pipeline; does NOT create
 * a new compaction implementation).
 *
 * <p><b>Fire-and-forget</b> (PRD §9 — fixed in r2): the LLM-bound Phase 2 of
 * {@code CompactionService.compact()} runs 5–30 s. Running it on the HTTP /
 * channel-router thread blocked the FE input ({@code setExecuting(true)})
 * for that entire duration. We therefore submit the compact call to the
 * existing {@code chatLoopExecutor} and return the toast result immediately.
 * The FE relies on the existing {@code session_updated} WebSocket event for
 * completion notification — no polling required.
 *
 * <p>Concurrency safety: {@code CompactionService.compact()} is itself thread-safe
 * (per-session stripe lock + {@code fullCompactInFlight} dedup set), so two
 * back-to-back {@code /compact} taps from the same user collapse into a
 * single compact run; the second submission becomes a no-op inside the
 * service layer.
 */
@Component
public class CompactCommandHandler implements SlashCommandHandler {

    private static final Logger log = LoggerFactory.getLogger(CompactCommandHandler.class);

    private final CompactionService compactionService;
    private final ThreadPoolExecutor chatLoopExecutor;

    public CompactCommandHandler(CompactionService compactionService,
                                 @Qualifier("chatLoopExecutor") ThreadPoolExecutor chatLoopExecutor) {
        this.compactionService = compactionService;
        this.chatLoopExecutor = chatLoopExecutor;
    }

    @Override
    public String getName() {
        return "compact";
    }

    @Override
    public String getDescription() {
        return "压缩当前会话上下文（释放 token，保留摘要）";
    }

    @Override
    public String getUsage() {
        return "/compact";
    }

    @Override
    public CommandResult execute(Long userId, String sessionId, String args, ExecutionContext context) {
        try {
            chatLoopExecutor.submit(() -> {
                try {
                    compactionService.compact(sessionId, "full", "user-manual", "slash-command");
                } catch (IllegalStateException ise) {
                    // Running session — CompactionService refuses; this is normal.
                    // No toast path back to the user; they'll see runtime_status="running"
                    // in the UI anyway. Logged at info to avoid alarming the operator.
                    log.info("/compact skipped for session {}: {}", sessionId, ise.getMessage());
                } catch (RuntimeException ex) {
                    log.warn("/compact background task failed for session {}: {}",
                            sessionId, ex.toString(), ex);
                }
            });
        } catch (RejectedExecutionException rex) {
            // chatLoopExecutor queue is full (AbortPolicy) — surface as friendly error.
            log.warn("/compact rejected (executor saturated) for session {}", sessionId);
            return CommandResult.error("服务器繁忙，稍后重试");
        }
        return CommandResult.toast("已触发上下文压缩");
    }
}
