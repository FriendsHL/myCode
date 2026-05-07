package com.skillforge.server.service.command;

import com.skillforge.server.dto.CommandResult;
import com.skillforge.server.service.CompactionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("CompactCommandHandler — fire-and-forget (r2 B2)")
class CompactCommandHandlerTest {

    @Mock private CompactionService compactionService;

    private ThreadPoolExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new ThreadPoolExecutor(
                1, 1, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(8),
                new ThreadPoolExecutor.AbortPolicy());
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    @DisplayName("INV-7: delegates to CompactionService.compact(level=full, source=user-manual)")
    void inv7_delegatesToCompactionService() {
        CompactCommandHandler h = new CompactCommandHandler(compactionService, executor);
        CommandResult r = h.execute(7L, "sess-1", "", ExecutionContext.web());

        assertThat(r.success()).isTrue();
        assertThat(r.displayMode()).isEqualTo("toast");
        // Compact runs asynchronously — wait until the task drains.
        verify(compactionService, timeout(2_000))
                .compact("sess-1", "full", "user-manual", "slash-command");
    }

    @Test
    @DisplayName("B2: handler returns BEFORE the long compact finishes (fire-and-forget)")
    void b2_returnsBeforeCompactCompletes() throws Exception {
        // Block the compact call until the test thread says go.
        CountDownLatch hold = new CountDownLatch(1);
        AtomicReference<Long> compactStartedAt = new AtomicReference<>();
        doAnswer(inv -> {
            compactStartedAt.set(System.nanoTime());
            // Hold the executor thread for a measurable window.
            hold.await(2, TimeUnit.SECONDS);
            return null;
        }).when(compactionService)
                .compact(anyString(), anyString(), anyString(), anyString());

        CompactCommandHandler h = new CompactCommandHandler(compactionService, executor);

        long callStart = System.nanoTime();
        CommandResult r = h.execute(7L, "sess-1", "", ExecutionContext.web());
        long callElapsedMs = (System.nanoTime() - callStart) / 1_000_000;

        // Handler.execute must return promptly; not wait for compact to drain.
        assertThat(callElapsedMs)
                .as("execute() must return immediately while compact runs in background")
                .isLessThan(500);
        assertThat(r.success()).isTrue();
        assertThat(r.message()).contains("已触发");

        // Now release the held compact and confirm it actually ran.
        hold.countDown();
        verify(compactionService, timeout(2_000))
                .compact(eq("sess-1"), eq("full"), eq("user-manual"), eq("slash-command"));
        assertThat(compactStartedAt.get()).isNotNull();
    }

    @Test
    @DisplayName("running session → IllegalStateException is swallowed in background, handler still returns toast")
    void runningSession_swallowsInBackground() {
        doThrow(new IllegalStateException("Cannot compact while session is running"))
                .when(compactionService).compact(anyString(), anyString(), anyString(), anyString());

        CompactCommandHandler h = new CompactCommandHandler(compactionService, executor);
        CommandResult r = h.execute(7L, "sess-1", "", ExecutionContext.web());

        // Foreground response is still success — error happens in background log only.
        assertThat(r.success()).isTrue();
        verify(compactionService, timeout(2_000))
                .compact(eq("sess-1"), eq("full"), eq("user-manual"), eq("slash-command"));
    }

    @Test
    @DisplayName("executor saturated → returns 'busy' error (does NOT silently lose the request)")
    void executorRejected_returnsBusyError() {
        // Tiny, no-queue, single-thread executor; pre-fill it with a blocking task
        // so the next submit hits AbortPolicy.
        ThreadPoolExecutor blocked = new ThreadPoolExecutor(
                1, 1, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(1),
                new ThreadPoolExecutor.AbortPolicy());
        try {
            CountDownLatch ready = new CountDownLatch(1);
            CountDownLatch hold = new CountDownLatch(1);
            blocked.submit(() -> { ready.countDown(); try { hold.await(); } catch (InterruptedException ignored) {} });
            blocked.submit(() -> {}); // fills the queue
            ready.await();

            CompactCommandHandler h = new CompactCommandHandler(compactionService, blocked);
            CommandResult r;
            try {
                r = h.execute(7L, "sess-1", "", ExecutionContext.web());
            } catch (RejectedExecutionException unexpected) {
                throw new AssertionError(
                        "Handler must catch RejectedExecutionException and surface as error result", unexpected);
            }
            assertThat(r.success()).isFalse();
            assertThat(r.error()).contains("繁忙");

            hold.countDown();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } finally {
            blocked.shutdownNow();
        }
    }

    @Test
    @DisplayName("metadata: name=compact, description, usage")
    void metadata() {
        CompactCommandHandler h = new CompactCommandHandler(compactionService, executor);
        assertThat(h.getName()).isEqualTo("compact");
        assertThat(h.getDescription()).isNotBlank();
        assertThat(h.getUsage()).isEqualTo("/compact");
    }
}
