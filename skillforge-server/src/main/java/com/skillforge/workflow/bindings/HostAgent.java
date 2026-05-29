package com.skillforge.workflow.bindings;

import com.skillforge.workflow.WorkflowAgentInvoker;
import com.skillforge.workflow.WorkflowContext;
import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.NativeObject;
import org.mozilla.javascript.Scriptable;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * The {@code agent(prompt, opts?)} host binding (plan §5.1). Context-sensitive
 * behaviour (plan §2.1):
 *
 * <ul>
 *   <li><b>Sequential context</b> (top-level / not inside {@code parallel}):
 *       invoke the sub-agent synchronously on the workflow thread and return the
 *       result to JS.</li>
 *   <li><b>parallel-collect context</b>: do NOT block — submit the (pure-Java)
 *       {@code invoke} to the sub-agent executor and return a
 *       {@link PendingAgentCall} placeholder. {@code HostParallel} barrier-joins
 *       these after evaluating all thunks.</li>
 * </ul>
 *
 * <p>Budget increment and step-index allocation happen <em>at invoke time</em> on
 * the workflow thread (single-threaded → deterministic order, plan §3.2) BEFORE
 * any offload.
 */
public final class HostAgent extends BaseFunction {

    private final transient WorkflowContext ctx;
    private final transient WorkflowAgentInvoker invoker;
    private final transient ExecutorService subAgentExecutor;

    public HostAgent(WorkflowContext ctx, WorkflowAgentInvoker invoker, ExecutorService subAgentExecutor) {
        this.ctx = ctx;
        this.invoker = invoker;
        this.subAgentExecutor = subAgentExecutor;
    }

    @Override
    public String getFunctionName() {
        return "agent";
    }

    @Override
    public int getArity() {
        return 2;
    }

    @Override
    public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
        if (args.length == 0 || args[0] == null) {
            throw Context.reportRuntimeError("agent(prompt) requires a prompt string");
        }
        String prompt = Context.toString(args[0]);
        Map<String, Object> opts = extractOpts(args);

        // Deterministic, on-the-workflow-thread bookkeeping (plan §3.2).
        ctx.getBudget().incrementAgentCalls();
        int stepIndex = ctx.nextStepIndex();
        ctx.recordInvokeThread(Thread.currentThread().getName());

        if (ctx.isInParallelCollect()) {
            // Offload the blocking engine.run to a worker thread. Pure Java — never
            // touches Rhino — so concurrent execution is safe (plan §2.1).
            CompletableFuture<Object> future = CompletableFuture.supplyAsync(
                    () -> invoker.invoke(prompt, opts, stepIndex), subAgentExecutor);
            return new PendingAgentCall(stepIndex, future);
        }

        // Sequential path: block on the workflow thread and return the value.
        Object out = invoker.invoke(prompt, opts, stepIndex);
        return Context.javaToJS(out, scope);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractOpts(Object[] args) {
        Map<String, Object> opts = new HashMap<>();
        if (args.length > 1 && args[1] instanceof NativeObject no) {
            for (Object id : no.getIds()) {
                String key = String.valueOf(id);
                opts.put(key, Context.jsToJava(no.get(key, no), Object.class));
            }
        }
        return opts;
    }
}
