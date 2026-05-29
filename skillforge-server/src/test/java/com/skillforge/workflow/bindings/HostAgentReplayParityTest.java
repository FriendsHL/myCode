package com.skillforge.workflow.bindings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.workflow.WorkflowContext;
import com.skillforge.workflow.WorkflowJsonExtractor;
import com.skillforge.workflow.journal.JournalCache;
import com.skillforge.workflow.sandbox.BudgetTracker;
import com.skillforge.workflow.sandbox.L1SandboxFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression guard for the approve→resume crash: the live {@code agent()} path
 * and the journal-replay path ({@code HostAgent.cachedAgentResult}) MUST parse
 * the same raw cached {@code finalResponse} into the SAME object.
 *
 * <p>Before the fix, live used tolerant extraction (prose + {json} accepted →
 * schema passed → step completed) but replay used a strict {@code readTree} that
 * threw on the same string. This drives the REAL replay path (resume +
 * parallel-collect, which returns the cached value without a Rhino conversion)
 * and compares it to the live extraction of the identical raw string.
 */
@DisplayName("HostAgent journal-replay parity")
class HostAgentReplayParityTest {

    private final L1SandboxFactory factory = new L1SandboxFactory();
    private final ObjectMapper om = new ObjectMapper();

    /** The shape that broke prod: a reasoning prefix in front of the JSON answer. */
    private static final String RAW_CACHED =
            "Now let me analyze the data and produce the summaryJson.\n\n"
                    + "{\"topIssues\":[{\"id\":\"issue-1\",\"title\":\"ReadFile bug\"}],\"summary\":\"2 issues\"}";

    private BudgetTracker budget() {
        return new BudgetTracker(BudgetTracker.DEFAULT_INSTRUCTION_CAP,
                BudgetTracker.DEFAULT_AGENT_CALL_CAP,
                System.nanoTime(),
                BudgetTracker.DEFAULT_TIMEOUT_NANOS);
    }

    @Test
    @DisplayName("replay parse of a prose-wrapped cached response equals the live extraction")
    void replayParsesSameAsLive() throws Exception {
        JournalCache cache = mock(JournalCache.class);
        when(cache.getCachedAgentFinalResponse(eq("run-1"), eq(0)))
                .thenReturn(Optional.of(RAW_CACHED));

        WorkflowContext ctx = new WorkflowContext("run-1", null, budget());
        ctx.setObjectMapper(om);
        ctx.setJournalCache(cache);
        ctx.setResuming(true);
        ctx.setResumeFrontierIndex(10);     // stepIndex 0 < 10 → cache-hit replay path
        ctx.setInParallelCollect(true);     // returns the cached value un-Rhino-converted

        Context cx = factory.enter(budget());
        try {
            Scriptable scope = factory.createSafeScope(cx);
            // opts = { schema: "<any non-null>" } — cachedAgentResult only checks presence.
            Scriptable opts = cx.newObject(scope);
            opts.put("schema", opts, "object");

            HostAgent host = new HostAgent(ctx, /* invoker */ null, /* executor */ null);
            Object placeholder = host.call(cx, scope, scope, new Object[] {"analyze please", opts});

            assertThat(placeholder).isInstanceOf(PendingAgentCall.class);
            Object replayObj = ((PendingAgentCall) placeholder).getFuture().get();

            // Live path equivalent: tolerant extraction → convertValue.
            Object liveObj = om.convertValue(
                    WorkflowJsonExtractor.tolerantReadTree(RAW_CACHED, om), Object.class);

            assertThat(replayObj).isEqualTo(liveObj);
            // and it's the real parsed content, not the raw string
            assertThat(replayObj).isInstanceOf(java.util.Map.class);
            assertThat(((java.util.Map<?, ?>) replayObj).get("summary")).isEqualTo("2 issues");
        } finally {
            Context.exit();
        }
    }
}
